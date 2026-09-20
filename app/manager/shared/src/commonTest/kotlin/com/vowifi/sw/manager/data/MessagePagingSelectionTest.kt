package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.MessageOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessagePagingSelectionTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))

    @Test fun rootUsesSummariesAndSearchesBeyondFirstPage() = runTest {
        val repository = FakeMessageRepository()
        repeat(121) { index -> repository.insertIncomingIfAbsent(owner, "id-$index", "engine-$index", "1555$index", if (index == 0) "needle" else "synthetic", index.toLong(), 1) }
        val selection = MessageHistorySelection(repository, backgroundScope, MessagePersistenceObserver { _, _, _ -> })
        selection.refreshInbox()
        runCurrent()
        assertEquals(50, assertIs<MessageUiState.Inbox>(selection.state.value).page.items.size)
        selection.loadMoreInbox()
        runCurrent()
        assertEquals(100, assertIs<MessageUiState.Inbox>(selection.state.value).page.items.size)
        selection.searchInbox("NEEDLE")
        runCurrent()
        assertEquals(listOf("needle"), assertIs<MessageUiState.Inbox>(selection.state.value).page.items.map { it.latestPreview })
        assertEquals(0, repository.listByOwnerCalls)
    }

    @Test fun olderThreadPreservesUnreadOutsideWindowAndDoesNotReadOwnerBodies() = runTest {
        val repository = FakeMessageRepository()
        repeat(121) { index -> repository.insertIncomingIfAbsent(owner, "id-$index", "engine-$index", "1555", "synthetic", index.toLong(), 1) }
        var unread = emptySet<String>()
        val observer = object : MessagePersistenceObserver {
            override fun onSnapshot(owner: SimOwnerId, records: List<com.vowifi.sw.manager.model.MessageRecord>, freshIncomingId: String?) = error("partial owner snapshot")
            override fun onThreadSnapshot(conversation: StoredMessageConversation, records: List<com.vowifi.sw.manager.model.MessageRecord>) = error("partial thread snapshot")
            override fun onThreadUnread(conversation: StoredMessageConversation, ids: Set<String>) { unread = ids }
        }
        val selection = MessageHistorySelection(repository, backgroundScope, observer)
        selection.select(StoredMessageConversation(owner, "1555").handle.value)
        runCurrent()
        assertEquals(50, assertIs<MessageUiState.Ready>(selection.state.value).records.size)
        assertEquals(121, unread.size)
        selection.loadOlder()
        runCurrent()
        val ready = assertIs<MessageUiState.Ready>(selection.state.value)
        assertEquals(100, ready.records.size)
        assertEquals(100, ready.records.map { it.id }.distinct().size)
        assertEquals(121, unread.size)
        assertEquals(0, repository.listByOwnerCalls)
    }

    @Test fun delayedOldSearchCannotOverwriteNewQueryAndMoreFailureRetainsRows() = runTest {
        val base = FakeMessageRepository()
        repeat(70) { base.insertIncomingIfAbsent(owner, "id-$it", "engine-$it", "1555$it", "synthetic", it.toLong(), 1) }
        val delayed = CompletableDeferred<Unit>()
        var failMore = false
        val repository = object : MessageRepository by base {
            override val history = object : MessageHistoryRepository by base.history {
                override suspend fun summaries(request: MessageSummaryRequest): MessageSummaryPage {
                    if (request.query == "old") withContext(NonCancellable) { delayed.await() }
                    if (failMore && request.cursor != null) throw MessageStorageException()
                    return base.history.summaries(request)
                }
            }
        }
        val selection = MessageHistorySelection(repository, backgroundScope, MessagePersistenceObserver { _, _, _ -> })
        selection.refreshInbox(); runCurrent()
        selection.searchInbox("old"); runCurrent()
        selection.searchInbox(""); runCurrent()
        delayed.complete(Unit); runCurrent()
        assertEquals(50, assertIs<MessageUiState.Inbox>(selection.state.value).page.items.size)
        failMore = true
        selection.loadMoreInbox(); runCurrent()
        val failed = assertIs<MessageUiState.Inbox>(selection.state.value)
        assertEquals(50, failed.page.items.size)
        assertTrue(failed.page.failed)
        failMore = false
        selection.loadMoreInbox(); runCurrent()
        val retried = assertIs<MessageUiState.Inbox>(selection.state.value)
        assertEquals(70, retried.page.items.size)
        assertFalse(retried.page.failed)
    }

    @Test fun browsingOtherOwnerNeverEnablesSending() = runTest {
        val base = FakeMessageRepository()
        base.insertIncomingIfAbsent(owner, "id", "engine", "1555", "synthetic", 1, 1)
        val connected = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
        val selection = MessageHistorySelection(base, backgroundScope, MessagePersistenceObserver { _, _, _ -> })
        selection.publishLive(MessageUiState.Ready(emptyList(), MessageOrigin.ENGINE, ownerId = connected, composerEnabled = true))
        selection.refreshInbox(); runCurrent()
        selection.selectHistoryOwner(owner); runCurrent()
        assertFalse(selection.allowsSend(connected))
        assertFalse(selection.allowsSend(owner))
    }

    @Test fun liveRefreshKeepsOldestVisibleBoundaryAndOlderCursorDoesNotSkipRows() = runTest {
        val repository = FakeMessageRepository()
        repeat(121) { repository.insertIncomingIfAbsent(owner, "id-$it", "engine-$it", "1555", "synthetic", it.toLong(), 1) }
        val selection = MessageHistorySelection(repository, backgroundScope, MessagePersistenceObserver { _, _, _ -> })
        selection.select(StoredMessageConversation(owner, "1555").handle.value); runCurrent()
        val oldest = assertIs<MessageUiState.Ready>(selection.state.value).records.first().id
        repository.insertIncomingIfAbsent(owner, "new", "new-engine", "1555", "synthetic", 122, 1)
        selection.publishLive(MessageUiState.Ready(repository.listByOwner(owner), MessageOrigin.ENGINE, ownerId = owner, composerEnabled = true))
        runCurrent()
        val refreshed = assertIs<MessageUiState.Ready>(selection.state.value)
        assertEquals(51, refreshed.records.size)
        assertEquals(oldest, refreshed.records.first().id)
        selection.loadOlder(); runCurrent()
        val older = assertIs<MessageUiState.Ready>(selection.state.value)
        assertEquals(101, older.records.size)
        assertEquals(101, older.records.map { it.id }.distinct().size)
    }

    @Test fun delayedOlderPageForACannotReplaceNewThreadB() = runTest {
        val store = FakeMessageRepository()
        repeat(70) { store.insertIncomingIfAbsent(owner, "a-$it", "ea-$it", "1555", "synthetic", it.toLong(), 1) }
        store.insertIncomingIfAbsent(owner, "b", "eb", "1666", "synthetic", 1, 1)
        val gate = CompletableDeferred<Unit>()
        val repository = object : MessageRepository by store {
            override val history = object : MessageHistoryRepository by store.history {
                override suspend fun thread(identity: StoredMessageConversation, cursor: MessageThreadCursor?): MessageThreadSlice {
                    if (cursor != null) withContext(NonCancellable) { gate.await() }
                    return store.history.thread(identity, cursor)
                }
            }
        }
        val selection = MessageHistorySelection(repository, backgroundScope, MessagePersistenceObserver { _, _, _ -> })
        selection.select(StoredMessageConversation(owner, "1555").handle.value); runCurrent()
        selection.loadOlder(); runCurrent()
        selection.select(StoredMessageConversation(owner, "1666").handle.value); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("b"), assertIs<MessageUiState.Ready>(selection.state.value).records.map { it.id })
    }
}
