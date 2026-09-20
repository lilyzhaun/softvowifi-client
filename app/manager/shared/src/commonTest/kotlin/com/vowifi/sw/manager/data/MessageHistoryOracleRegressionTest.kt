@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

class MessageHistoryOracleRegressionTest {
    private val ownerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val ownerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
    private val identity = StoredMessageConversation(ownerA, "155501")

    @Test fun initialTextReadRequeriesWhenTargetReceivesNewMtBeforeFirstWindowCommits() = runTest {
        val stored = seed(1)
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var reads = 0
        val repository = object : MessageRepository by stored {
            override val history = object : MessageHistoryRepository by stored.history {
                override suspend fun thread(identity: StoredMessageConversation, cursor: MessageThreadCursor?): MessageThreadSlice {
                    val snapshot = stored.history.thread(identity, cursor)
                    reads++
                    if (reads == 1) { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
                    return snapshot
                }
            }
        }
        val selection = MessageHistorySelection(repository, backgroundScope, MessagePersistenceObserver { _, _, _ -> })
        selection.publishLive(live(stored)); selection.select(identity.handle.value); runCurrent()
        assertTrue(entered.isCompleted)
        assertIs<MessageUiState.Loading>(selection.state.value)
        stored.insertIncomingIfAbsent(ownerA, "id-2", "engine-2", identity.key, "synthetic", 2, 2)
        selection.publishLive(live(stored)); runCurrent()
        release.complete(Unit); runCurrent()
        val displayed = assertIs<MessageUiState.Ready>(selection.state.value)
        assertEquals(listOf("id-1", "id-2"), displayed.records.map { it.id })
        assertTrue(reads >= 2)
        selection.close()
    }

    @Test fun staleUnreadSnapshotCannotClearNewTargetNotificationAfterActiveOwnerChanges() = runTest {
        val stored = seed(1)
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var reads = 0
        val repository = object : MessageRepository by stored {
            override val history = object : MessageHistoryRepository by stored.history {
                override suspend fun unreadIds(identity: StoredMessageConversation): List<String> {
                    val snapshot = stored.history.unreadIds(identity)
                    reads++
                    if (reads == 1) { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
                    return snapshot
                }
            }
        }
        val notifications = mutableMapOf<String, Int>()
        val callbacks = mutableListOf<Set<String>>()
        val policy = MessageNotificationPolicy({ handle, count, _ -> notifications[handle.value] = count }, { notifications.remove(it.value) })
        val observer = object : MessagePersistenceObserver {
            override fun onSnapshot(owner: SimOwnerId, records: List<MessageRecord>, freshIncomingId: String?) = error("History must not publish owner snapshots")
            override fun onThreadUnread(conversation: StoredMessageConversation, ids: Set<String>) {
                callbacks += ids
                policy.threadUnread(conversation, ids)
            }
        }
        val selection = MessageHistorySelection(repository, backgroundScope, observer)
        policy.snapshot(ownerA, stored.listByOwner(ownerA), "id-1")
        selection.publishLive(live(stored)); selection.select(identity.handle.value); runCurrent()
        assertTrue(entered.isCompleted)
        assertIs<MessageUiState.Loading>(selection.state.value)
        stored.insertIncomingIfAbsent(ownerA, "id-2", "engine-2", identity.key, "synthetic", 2, 2)
        policy.snapshot(ownerA, stored.listByOwner(ownerA), "id-2")
        selection.publishLive(live(stored))
        selection.publishLive(MessageUiState.Ready(emptyList(), MessageOrigin.ENGINE, ownerId = ownerB, composerEnabled = true))
        runCurrent()
        release.complete(Unit); runCurrent()
        assertEquals(2, notifications[identity.handle.value])
        assertTrue(callbacks.isNotEmpty())
        assertTrue(callbacks.all { it == setOf("id-1", "id-2") })
        val displayed = assertIs<MessageUiState.Ready>(selection.state.value)
        assertEquals(listOf("id-1", "id-2"), displayed.records.map { it.id })
        assertFalse(displayed.composerEnabled)
        selection.close()
    }

    @Test fun removedVisibleWindowResetsToLatestRemainingPageWithoutSkippingOlderRows() = runTest {
        val original = seed(120)
        val previous = original.history.thread(identity)
        assertEquals((71..120).map { "id-$it" }, previous.records.map { it.id })
        val remaining = seed(70)
        val refreshed = remaining.history.refreshThread(identity, previous.records.first())
        assertEquals((21..70).map { "id-$it" }, refreshed.records.map { it.id })
        assertNotNull(refreshed.older)
        val older = remaining.history.thread(identity, refreshed.older)
        assertEquals((1..20).map { "id-$it" }, older.records.map { it.id })
        assertNull(older.older)
        assertEquals(70, (older.records + refreshed.records).map { it.id }.distinct().size)
    }

    @Test fun removedEntireThreadReturnsEmptySliceAndUnavailableWithoutUnreadReconciliation() = runTest {
        val original = seed(120)
        val previous = original.history.thread(identity)
        val empty = FakeMessageRepository()
        val gone = empty.history.refreshThread(identity, previous.records.first())
        assertTrue(gone.records.isEmpty())
        assertNull(gone.older)
        var backing: MessageHistoryRepository = original.history
        val repository = object : MessageRepository by original {
            override val history = object : MessageHistoryRepository by original.history {
                override suspend fun thread(identity: StoredMessageConversation, cursor: MessageThreadCursor?) = backing.thread(identity, cursor)
                override suspend fun unreadIds(identity: StoredMessageConversation) = backing.unreadIds(identity)
            }
        }
        var reconciliations = 0
        val observer = object : MessagePersistenceObserver {
            override fun onSnapshot(owner: SimOwnerId, records: List<MessageRecord>, freshIncomingId: String?) = error("History must not publish owner snapshots")
            override fun onThreadUnread(conversation: StoredMessageConversation, ids: Set<String>) { reconciliations++ }
        }
        val selection = MessageHistorySelection(repository, backgroundScope, observer)
        selection.publishLive(live(original)); selection.select(identity.handle.value); runCurrent()
        assertEquals(50, assertIs<MessageUiState.Ready>(selection.state.value).records.size)
        backing = empty.history
        selection.publishLive(live(empty)); runCurrent()
        assertIs<MessageUiState.Unavailable>(selection.state.value)
        assertEquals(0, reconciliations)
        selection.close()
    }

    private suspend fun seed(count: Int) = FakeMessageRepository().also { store ->
        for (index in 1..count) store.insertIncomingIfAbsent(ownerA, "id-$index", "engine-$index", identity.key, "synthetic", index.toLong(), index.toLong())
    }

    private suspend fun live(store: FakeMessageRepository) = MessageUiState.Ready(
        store.listByOwner(ownerA), MessageOrigin.ENGINE, ownerId = ownerA, composerEnabled = true,
    )
}
