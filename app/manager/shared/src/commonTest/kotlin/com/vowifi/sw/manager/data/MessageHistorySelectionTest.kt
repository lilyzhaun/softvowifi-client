@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class MessageHistorySelectionTest {
    private val ownerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val ownerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
    private val identity = StoredMessageConversation(ownerA, "155501")
    private val observer = MessagePersistenceObserver { _, _, _ -> }
    private val live = MessageUiState.Ready(emptyList(), MessageOrigin.ENGINE, composerEnabled = true, ownerId = ownerB)

    @Test fun lateLookupCannotReplaceNewerNavigationEvenWhenDatabaseIgnoresCancellation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val stored = FakeMessageRepository()
        stored.insertIncomingIfAbsent(ownerA, "a", "a", identity.key, "synthetic", 1, 1)
        lateinit var release: kotlin.coroutines.Continuation<MessageSummaryPage>
        val repository = object : MessageRepository by stored {
            override val history = object : MessageHistoryRepository by stored.history {
                override suspend fun summaries(request: MessageSummaryRequest): MessageSummaryPage {
                    if (request.owner != ownerA) return stored.history.summaries(request)
                    return kotlin.coroutines.suspendCoroutine { continuation ->
                        release = continuation
                        entered.complete(Unit)
                    }
                }
            }
        }
        val selection = MessageHistorySelection(repository, backgroundScope, observer)
        selection.publishLive(live)
        selection.select(identity.handle.value)
        runCurrent()
        entered.await()
        selection.select(null)
        release.resumeWith(Result.success(stored.history.summaries(MessageSummaryRequest(ownerA))))
        runCurrent()
        val root = assertIs<MessageUiState.Inbox>(selection.state.value)
        assertEquals(ownerB, root.ownerId)
        assertTrue(root.composerEnabled)
        assertTrue(root.page.items.isEmpty())
    }

    @Test fun forgedHandleCannotDisplayOrSendThroughLoadedOwner() = runTest {
        val selection = MessageHistorySelection(FakeMessageRepository(), backgroundScope, observer)
        selection.publishLive(live)
        selection.select("conversation-forged")
        runCurrent()
        assertIs<MessageUiState.Unavailable>(selection.state.value)
        assertFalse(selection.allowsSend(ownerB))
    }

    @Test fun historyReadPreservesUnobservedIdsAndDuplicateSelectionDoesNotReload() = runTest {
        val repository = FakeMessageRepository()
        repository.insertIncomingIfAbsent(ownerA, "seen", "seen", "155501", "synthetic", 1, 1)
        repository.insertIncomingIfAbsent(ownerA, "future", "future", "155501", "synthetic", 2, 2)
        val selection = MessageHistorySelection(repository, backgroundScope, observer)
        selection.publishLive(live)
        selection.select(identity.handle.value)
        runCurrent()
        selection.read(ownerA, IncomingReadUpdate.of(listOf("seen")), 100)
        selection.select(identity.handle.value)
        runCurrent()
        val displayed = assertIs<MessageUiState.Ready>(selection.state.value)
        assertEquals(ownerA, displayed.ownerId)
        assertFalse(displayed.composerEnabled)
        assertEquals(listOf("future"), displayed.records.filterIsInstance<MessageRecord.Incoming>().filter { !it.isRead }.map { it.id })
    }

    @Test fun permissionFailureDoesNotHidePersistedHistory() = runTest {
        val repository = FakeMessageRepository()
        repository.insertIncomingIfAbsent(ownerA, "one", "one", "155501", "synthetic", 1, 1)
        val source = EngineMessageSource(
            EngineMessageRuntime({ throw MessagePermissionException("synthetic denied") }, { error("No engine attachment") }, { 1 }),
            coroutineContext, repository, { ownerB }, { "never" },
        )
        runCurrent()
        source.selectConversation(identity.handle.value)
        runCurrent()
        val displayed = assertIs<MessageUiState.Ready>(source.state.value)
        assertEquals(ownerA, displayed.ownerId)
        assertFalse(displayed.composerEnabled)
        assertEquals(1L, repository.countUnreadIncoming())
        source.close()
    }

    private fun row(id: String) = MessageRecord.Incoming(id, "155501", "synthetic", 1)
}
