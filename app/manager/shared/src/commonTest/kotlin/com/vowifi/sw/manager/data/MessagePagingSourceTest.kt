package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessagePagingSourceTest {
    private val ownerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val ownerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))

    @Test fun healthyAndUnchangedOfflineWatcherDoNotReloadOwnerBodies() = runTest {
        val repository = FakeMessageRepository()
        var running = true
        val transport = FakeEngineMessageTransport()
        val source = EngineMessageSource(EngineMessageRuntime(
            { if (running) listOf(EngineInstanceState("synthetic", EngineLifecycle.RUNNING, pid = 7)) else emptyList() },
            { transport }, { 1L }), coroutineContext, repository, { ownerB }, { "outgoing" })
        source.watchAttachment()
        runCurrent()
        val initial = repository.listByOwnerCalls
        advanceTimeBy(9_001); runCurrent()
        assertEquals(initial, repository.listByOwnerCalls)
        running = false
        advanceTimeBy(3_000); runCurrent()
        val offline = repository.listByOwnerCalls
        assertEquals(initial + 1, offline)
        advanceTimeBy(9_000); runCurrent()
        assertEquals(offline, repository.listByOwnerCalls)
        source.close()
    }

    @Test fun browsingADoesNotSendOnBAndOnlyBoundSubmissionPublishesCompletion() = runTest {
        val repository = FakeMessageRepository()
        repository.insertIncomingIfAbsent(ownerA, "a", "a-engine", "1555", "synthetic", 1, 1)
        val gate = CompletableDeferred<Unit>()
        val transport = FakeEngineMessageTransport(requestGate = gate, sendResult = EngineProtocolFrame.Response(20, buildJsonObject {
            put("smsId", "accepted-engine"); put("statusCode", 202); put("reference", 1)
        }, null))
        val source = EngineMessageSource(EngineMessageRuntime(
            { listOf(EngineInstanceState("synthetic", EngineLifecycle.RUNNING, pid = 7)) }, { transport }, { 2L }),
            coroutineContext, repository, { ownerB }, { "bound-app-id" })
        runCurrent()
        source.selectHistoryOwner(ownerA); runCurrent()
        source.sendFromDisplayedOwner(ownerA, "1555", "draft"); runCurrent()
        assertTrue(transport.requests.isEmpty())
        transport.emitReceived("new-b", "1666", "synthetic b", 3); runCurrent()
        assertEquals(ownerA, assertIs<MessageUiState.Inbox>(source.state.value).ownerId)
        assertEquals(2L, assertIs<MessageUnreadSummary.Ready>(source.unreadSummary.value).total)
        source.selectHistoryOwner(ownerB); runCurrent()
        source.sendFromDisplayedOwner(ownerB, "1555", "draft"); runCurrent()
        val pending = assertIs<MessageUiState.Inbox>(source.state.value).sendAttempt
        assertEquals("bound-app-id", pending?.messageId)
        assertEquals(OutgoingMessageStatus.QUEUED, pending?.status)
        source.selectHistoryOwner(ownerA); runCurrent()
        gate.complete(Unit); runCurrent()
        assertNull(assertIs<MessageUiState.Inbox>(source.state.value).sendAttempt)
        source.selectHistoryOwner(ownerB); runCurrent()
        val accepted = assertIs<MessageUiState.Inbox>(source.state.value).sendAttempt
        assertEquals(ownerB, accepted?.owner)
        assertEquals("bound-app-id", accepted?.messageId)
        assertEquals(OutgoingMessageStatus.ACCEPTED, accepted?.status)
        source.close()
    }

    @Test fun loadingPartialThreadDoesNotRemoveUnloadedOrSiblingNotifications() = runTest {
        val repository = FakeMessageRepository()
        repeat(121) { repository.insertIncomingIfAbsent(ownerA, "id-$it", "engine-$it", "1555", "synthetic", it.toLong(), 1) }
        repository.insertIncomingIfAbsent(ownerA, "sibling", "other-engine", "1666", "synthetic", 1, 1)
        val posted = mutableMapOf<String, Int>()
        val policy = MessageNotificationPolicy({ handle, count, _ -> posted[handle.value] = count }, { posted.remove(it.value) })
        policy.snapshot(ownerA, repository.listByOwner(ownerA)); policy.reconcile()
        val observer = object : MessagePersistenceObserver {
            override fun onSnapshot(owner: SimOwnerId, records: List<MessageRecord>, freshIncomingId: String?) { policy.snapshot(owner, records, freshIncomingId) }
            override fun onThreadUnread(conversation: StoredMessageConversation, ids: Set<String>) { policy.threadUnread(conversation, ids) }
        }
        val selection = MessageHistorySelection(repository, backgroundScope, observer)
        val identity = StoredMessageConversation(ownerA, "1555")
        selection.select(identity.handle.value); runCurrent()
        val window = assertIs<MessageUiState.Ready>(selection.state.value)
        assertEquals(50, window.records.size)
        assertEquals(121, posted[identity.handle.value])
        assertEquals(1, posted[StoredMessageConversation(ownerA, "1666").handle.value])
        policy.read(ownerA, window.records.map { it.id }.toSet())
        assertEquals(71, posted[identity.handle.value])
        assertEquals(1, posted[StoredMessageConversation(ownerA, "1666").handle.value])
    }
}
