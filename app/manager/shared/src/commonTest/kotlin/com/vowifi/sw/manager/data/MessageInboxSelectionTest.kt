@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlin.test.*

class MessageInboxSelectionTest {
    private val ownerA = requireNotNull(ownerFor(EngineMessagePersistenceFixtures.ICCID_A))
    private val ownerB = requireNotNull(ownerFor(EngineMessagePersistenceFixtures.ICCID_B))
    private val live = MessageUiState.Ready(emptyList(), MessageOrigin.ENGINE, composerEnabled = true, ownerId = ownerB)

    @Test fun parentFallbackReloadsPersistedMessagesAfterSameOwnerWasOnline() = runTest {
        val repository = FakeMessageRepository()
        repository.insertIncomingIfAbsent(ownerA, "a1", "a1", "155501", "synthetic", 1, 1)
        val selection = MessageHistorySelection(repository, backgroundScope, MessagePersistenceObserver { _, _, _ -> })
        try {
            selection.publishLive(MessageUiState.Unavailable("offline")); selection.refreshInbox(); runCurrent()
            assertEquals("a1", assertIs<MessageUiState.Inbox>(selection.state.value).page.items.single().latestMessageId)
            selection.publishLive(MessageUiState.Ready(repository.listByOwner(ownerA), MessageOrigin.ENGINE, composerEnabled = true, ownerId = ownerA))
            repository.insertIncomingIfAbsent(ownerA, "a2", "a2", "155501", "synthetic", 2, 2)
            selection.publishLive(MessageUiState.Ready(repository.listByOwner(ownerA), MessageOrigin.ENGINE, composerEnabled = true, ownerId = ownerA)); runCurrent()
            assertTrue(assertIs<MessageUiState.Inbox>(selection.state.value).composerEnabled)
            selection.publishLive(MessageUiState.PermissionDenied("denied")); runCurrent()
            val fallback = assertIs<MessageUiState.Inbox>(selection.state.value)
            assertEquals(ownerA, fallback.ownerId)
            assertFalse(fallback.composerEnabled)
            assertEquals(2L, fallback.page.items.single().messageCount)
            assertEquals("a2", fallback.page.items.single().latestMessageId)
        } finally { selection.close() }
    }

    @Test fun parentFallbackRejectsOldLoadCompletionAfterSameOwnerWasOnline() = runTest {
        val stored = FakeMessageRepository()
        stored.insertIncomingIfAbsent(ownerA, "a1", "a1", "155501", "synthetic", 1, 1)
        lateinit var resume: Continuation<MessageSummaryPage>
        var reads = 0
        val repository = object : MessageRepository by stored {
            override val history = object : MessageHistoryRepository by stored.history {
                override suspend fun summaries(request: MessageSummaryRequest): MessageSummaryPage {
                    reads++
                    return if (reads == 1) suspendCoroutine { resume = it } else stored.history.summaries(request)
                }
            }
        }
        val selection = MessageHistorySelection(repository, backgroundScope, MessagePersistenceObserver { _, _, _ -> })
        try {
            selection.publishLive(MessageUiState.Unavailable("offline")); selection.refreshInbox(); runCurrent()
            val oldPage = stored.history.summaries(MessageSummaryRequest(ownerA))
            assertTrue(assertIs<MessageUiState.Inbox>(selection.state.value).page.loading)
            selection.publishLive(MessageUiState.Ready(stored.listByOwner(ownerA), MessageOrigin.ENGINE, composerEnabled = true, ownerId = ownerA))
            stored.insertIncomingIfAbsent(ownerA, "a2", "a2", "155501", "synthetic", 2, 2)
            selection.publishLive(MessageUiState.Ready(stored.listByOwner(ownerA), MessageOrigin.ENGINE, composerEnabled = true, ownerId = ownerA)); runCurrent()
            assertTrue(assertIs<MessageUiState.Inbox>(selection.state.value).composerEnabled)
            selection.publishLive(MessageUiState.Failure("list failed")); runCurrent()
            resume.resumeWith(Result.success(oldPage)); runCurrent()
            val fallback = assertIs<MessageUiState.Inbox>(selection.state.value)
            assertEquals(ownerA, fallback.ownerId)
            assertFalse(fallback.composerEnabled)
            assertEquals(2L, fallback.page.items.single().messageCount)
            assertEquals("a2", fallback.page.items.single().latestMessageId)
        } finally { selection.close() }
    }

    @Test fun lateOwnerLoadCannotReplaceNewerSelectionEvenWhenStorageIgnoresCancellation() = runTest {
        val stored = FakeMessageRepository()
        stored.insertIncomingIfAbsent(ownerA, "a", "a", "155501", "synthetic", 1, 1)
        lateinit var resume: Continuation<MessageSummaryPage>
        val repository = object : MessageRepository by stored {
            override val history = object : MessageHistoryRepository by stored.history {
                override suspend fun summaries(request: MessageSummaryRequest): MessageSummaryPage =
                    if (request.owner == ownerA) suspendCoroutine { resume = it } else stored.history.summaries(request)
            }
        }
        val selection = MessageHistorySelection(repository, backgroundScope, MessagePersistenceObserver { _, _, _ -> })
        val offlineA = MessageUiState.Ready(emptyList(), MessageOrigin.ENGINE, ownerId = ownerA)
        selection.publishLive(live, sessions = mapOf(ownerA to offlineA, ownerB to live)); selection.refreshInbox(); runCurrent()
        selection.selectHistoryOwner(ownerA); runCurrent()
        assertTrue(assertIs<MessageUiState.Inbox>(selection.state.value).page.loading)
        selection.selectHistoryOwner(ownerB); runCurrent()
        resume.resumeWith(Result.success(stored.history.summaries(MessageSummaryRequest(ownerA)))); runCurrent()
        val displayed = assertIs<MessageUiState.Inbox>(selection.state.value)
        assertEquals(ownerB, displayed.ownerId)
        assertTrue(displayed.composerEnabled)
        assertTrue(displayed.page.items.isEmpty())
        assertEquals(ownerB, selection.historyChoices.value.selectedOwner)
        selection.close()
    }

    @Test fun observedReadDuringHistoryLoadOnlyUpdatesObservedIds() = runTest {
        val stored = FakeMessageRepository()
        stored.insertIncomingIfAbsent(ownerA, "seen", "seen", "155501", "synthetic", 1, 1)
        stored.insertIncomingIfAbsent(ownerA, "future", "future", "155501", "synthetic", 2, 2)
        lateinit var resume: Continuation<MessageSummaryPage>
        var reads = 0
        val repository = object : MessageRepository by stored {
            override val history = object : MessageHistoryRepository by stored.history {
                override suspend fun summaries(request: MessageSummaryRequest): MessageSummaryPage {
                    reads++
                    return if (reads == 1) suspendCoroutine { resume = it } else stored.history.summaries(request)
                }
            }
        }
        val selection = MessageHistorySelection(repository, backgroundScope, MessagePersistenceObserver { _, _, _ -> })
        selection.refreshInbox(); runCurrent()
        val before = stored.history.summaries(MessageSummaryRequest(ownerA))
        val update = stored.markIncomingRead(requireNotNull(ObservedIncomingReadRequest.create(ownerA, "155501", listOf("seen"))), 10)
        selection.read(ownerA, update, 10); runCurrent()
        resume.resumeWith(Result.success(before)); runCurrent()
        val inbox = assertIs<MessageUiState.Inbox>(selection.state.value)
        assertEquals(1L, inbox.page.items.single().unreadCount)
        val records = stored.history.thread(StoredMessageConversation(ownerA, "155501")).records
        assertTrue(assertIs<MessageRecord.Incoming>(records[0]).isRead)
        assertFalse(assertIs<MessageRecord.Incoming>(records[1]).isRead)
        assertFalse(inbox.composerEnabled)
        selection.close()
    }
}
