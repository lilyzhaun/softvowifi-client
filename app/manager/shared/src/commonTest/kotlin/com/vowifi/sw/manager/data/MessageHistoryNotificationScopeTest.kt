@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import kotlinx.coroutines.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.*

class MessageHistoryNotificationScopeTest {
    private val ownerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val ownerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
    private val first = StoredMessageConversation(ownerA, "155501")
    private val second = StoredMessageConversation(ownerA, "155502")

    @Test fun openingAndReadingA1HistoryPreservesA2NotificationAndUnread() = runTest {
        val store = FakeMessageRepository()
        var threadExists = true
        val repository = object : MessageRepository by store {
            override val history = object : MessageHistoryRepository by store.history {
                override suspend fun thread(identity: StoredMessageConversation, cursor: MessageThreadCursor?): MessageThreadSlice {
                    assertEquals(first, identity)
                    return if (threadExists) store.history.thread(identity, cursor) else MessageThreadSlice(identity, emptyList(), null)
                }
            }
        }
        val active = mutableMapOf<ConversationHandle, Int>()
        val cancelled = mutableListOf<ConversationHandle>()
        val alerts = mutableListOf<ConversationHandle>()
        val policy = MessageNotificationPolicy(
            { handle, count, alert -> active[handle] = count; if (alert) alerts += handle },
            { handle -> active.remove(handle); cancelled += handle },
            { it in active },
        )
        val observer = object : MessagePersistenceObserver {
            override fun onSnapshot(owner: SimOwnerId, records: List<MessageRecord>, freshIncomingId: String?) = policy.snapshot(owner, records, freshIncomingId)
            override fun onRead(owner: SimOwnerId, ids: Set<String>) = policy.read(owner, ids)
            override fun onThreadSnapshot(conversation: StoredMessageConversation, records: List<MessageRecord>) = policy.threadSnapshot(conversation, records)
            override fun onThreadUnread(conversation: StoredMessageConversation, ids: Set<String>) = policy.threadUnread(conversation, ids)
        }
        var instances = listOf(EngineInstanceState("8986001", EngineLifecycle.RUNNING, pid = 7))
        val transportA = FakeEngineMessageTransport()
        val transportB = FakeEngineMessageTransport()
        var next = 0
        val source = EngineMessageSource(
            EngineMessageRuntime({ instances }, { if (it == "8986001") transportA else transportB }, { 1000 }),
            coroutineContext, repository, { if (it == "8986001") ownerA else ownerB }, { "id-${++next}" }, observer,
        )
        runCurrent()
        transportA.emitReceived("a1", "155501", "synthetic first", 1)
        transportA.emitReceived("a2", "155502", "synthetic unrelated", 2)
        runCurrent()
        assertEquals(setOf(first.handle, second.handle), active.keys)
        instances = listOf(EngineInstanceState("8986002", EngineLifecycle.RUNNING, pid = 8))
        source.refresh()
        runCurrent()
        source.selectConversation(first.handle.value)
        runCurrent()
        assertEquals(setOf(first.handle, second.handle), active.keys)
        assertEquals(emptyList(), cancelled)
        assertEquals(2L, store.countUnreadIncoming())
        assertEquals(listOf("id-1"), assertIs<MessageUiState.Ready>(source.state.value).records.map { it.id })
        source.selectConversation(null)
        threadExists = false
        source.selectConversation(first.handle.value)
        runCurrent()
        assertIs<MessageUiState.Unavailable>(source.state.value)
        assertEquals(emptyList(), cancelled)
        source.selectConversation(null)
        threadExists = true
        source.selectConversation(first.handle.value)
        runCurrent()
        source.markVisibleRead(requireNotNull(ObservedIncomingReadRequest.create(ownerA, first.key, listOf("id-1"))))
        runCurrent()
        assertEquals(listOf(first.handle), cancelled)
        assertEquals(mapOf(second.handle to 1), active)
        assertEquals(1L, store.countUnreadIncoming())
        source.selectConversation(first.handle.value)
        source.selectConversation("conversation-forged")
        runCurrent()
        assertIs<MessageUiState.Unavailable>(source.state.value)
        assertEquals(listOf(first.handle), cancelled)
        assertEquals(listOf(first.handle, second.handle), alerts)
        source.close()
    }

    @Test fun multipleBWatchCyclesAndIngressPreserveReadonlyAAndGlobalCount() = runTest {
        val store = FakeMessageRepository()
        store.insertIncomingIfAbsent(ownerA, "a1", "a1", first.key, "synthetic A", 1, 1)
        store.insertIncomingIfAbsent(ownerA, "a2", "a2", second.key, "synthetic unrelated", 2, 2)
        val active = mutableMapOf<ConversationHandle, Int>()
        val alerts = mutableListOf<ConversationHandle>()
        val policy = MessageNotificationPolicy(
            { handle, count, alert -> active[handle] = count; if (alert) alerts += handle },
            { active.remove(it) }, { it in active },
        )
        policy.snapshot(ownerA, store.listByOwner(ownerA), "a1")
        policy.snapshot(ownerA, store.listByOwner(ownerA), "a2")
        val observer = object : MessagePersistenceObserver {
            override fun onSnapshot(owner: SimOwnerId, records: List<MessageRecord>, freshIncomingId: String?) = policy.snapshot(owner, records, freshIncomingId)
            override fun onThreadSnapshot(conversation: StoredMessageConversation, records: List<MessageRecord>) = policy.threadSnapshot(conversation, records)
            override fun onThreadUnread(conversation: StoredMessageConversation, ids: Set<String>) = policy.threadUnread(conversation, ids)
            override fun onRead(owner: SimOwnerId, ids: Set<String>) = policy.read(owner, ids)
        }
        val transport = FakeEngineMessageTransport()
        var lists = 0
        var next = 0
        val source = EngineMessageSource(
            EngineMessageRuntime({ lists++; listOf(EngineInstanceState("8986002", EngineLifecycle.RUNNING, pid = 8)) }, { transport }, { 1000 }),
            coroutineContext, store, { ownerB }, { "b-${++next}" }, observer,
        )
        source.watchAttachment()
        runCurrent()
        source.selectConversation(first.handle.value)
        runCurrent()
        repeat(3) { cycle ->
            transport.emitReceived("b-mt-$cycle", "155503", "synthetic B", cycle.toLong() + 3)
            runCurrent()
            advanceTimeBy(3000)
            runCurrent()
            val displayed = assertIs<MessageUiState.Ready>(source.state.value)
            assertEquals(ownerA, displayed.ownerId)
            assertEquals(listOf("a1"), displayed.records.map { it.id })
            assertFalse(displayed.composerEnabled)
            assertEquals(cycle.toLong() + 3, assertIs<MessageUnreadSummary.Ready>(source.unreadSummary.value).total)
            assertEquals(1, active[second.handle])
        }
        val bHandle = StoredMessageConversation(ownerB, "155503").handle
        assertEquals(3, active[bHandle])
        assertEquals(listOf(first.handle, second.handle, bHandle, bHandle, bHandle), alerts)
        assertEquals(4, lists)
        assertEquals(5L, store.countUnreadIncoming())
        source.close()
    }

    @Test fun renderedOwnerACapturedBeforeMutexCannotSendAfterBCommits() = runTest {
        val store = FakeMessageRepository()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : MessageRepository by store {
            override suspend fun setLastOwner(ownerId: SimOwnerId) {
                if (ownerId == ownerB) { entered.complete(Unit); release.await() }
                store.setLastOwner(ownerId)
            }
        }
        var instance = EngineInstanceState("8986001", EngineLifecycle.RUNNING, pid = 7)
        val transportA = FakeEngineMessageTransport()
        val transportB = FakeEngineMessageTransport()
        val source = EngineMessageSource(
            EngineMessageRuntime({ listOf(instance) }, { if (it == "8986001") transportA else transportB }, { 1000 }, { listOf(instance.iccid) }),
            coroutineContext, repository, { if (it == "8986001") ownerA else ownerB }, { "must-not-insert" },
        )
        runCurrent()
        val renderedOwner = assertIs<MessageUiState.Inbox>(source.state.value).ownerId
        instance = EngineInstanceState("8986002", EngineLifecycle.RUNNING, pid = 8)
        source.refresh()
        runCurrent()
        entered.await()
        source.sendFromDisplayedOwner(renderedOwner, "155501", "synthetic stale callback")
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(ownerB, assertIs<MessageUiState.Inbox>(source.state.value).ownerId)
        assertEquals(0, store.insertQueuedCalls)
        assertEquals(0, transportA.requests.size)
        assertEquals(0, transportB.requests.size)
        source.close()
    }
}
