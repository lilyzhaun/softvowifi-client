@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.messageProjectionOwner
import com.vowifi.sw.manager.ui.projectMessageConversations
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MessageOfflineInboxTest {
    private val ownerA = ownerFor(EngineMessagePersistenceFixtures.ICCID_A)!!
    private val ownerB = ownerFor(EngineMessagePersistenceFixtures.ICCID_B)!!

    @Test fun allStoredOwnersAreDiscoverableWhenEngineIsAbsentAndLastOwnerIsUnset() = runTest {
        val repository = history()
        val source = EngineMessagePersistenceFixtures.sourceOf(emptyList(), FakeEngineMessageTransport(), repository)
        try {
        runCurrent()
        val state = assertIs<MessageUiState.Inbox>(source.state.value)
        assertEquals(2, source.historyChoices.value.lines.size)
        assertEquals(1, projectMessageConversations(messageProjectionOwner(state), state).rows.size)
        assertFalse(state.composerEnabled)
        source.historyChoices.value.lines.forEach { line ->
            source.selectHistoryOwner(line.owner)
            runCurrent()
            assertEquals(line.owner, assertIs<MessageUiState.Inbox>(source.state.value).ownerId)
            assertEquals(1, projectMessageConversations(messageProjectionOwner(source.state.value), source.state.value).rows.size)
        }
        assertEquals(2L, repository.countUnreadIncoming())
        } finally { source.close() }
    }

    @Test fun localHistoryRemainsDiscoverableWhenRootPermissionIsDenied() = runTest {
        val repository = history()
        val source = EngineMessageSource(
            EngineMessageRuntime({ throw MessagePermissionException("denied") }, { error("No transport") }, { 1 }),
            coroutineContext, repository, { ownerB }, { "unused" },
        )
        runCurrent()
        source.close()
        val state = assertIs<MessageUiState.Inbox>(source.state.value)
        assertEquals(2, source.historyChoices.value.lines.size)
        assertEquals(1, projectMessageConversations(messageProjectionOwner(state), state).rows.size)
        assertFalse(state.composerEnabled)
    }

    @Test fun browsingOwnerAWhileBIsAttachedNeverSendsAThroughBAndReturnRestoresInbox() = runTest {
        val transport = FakeEngineMessageTransport()
        val source = EngineMessagePersistenceFixtures.sourceOf(
            listOf(EngineMessagePersistenceFixtures.running(EngineMessagePersistenceFixtures.ICCID_B)), transport, history(),
        )
        try {
        runCurrent()
        source.selectConversation(StoredMessageConversation(ownerA, "155501").handle.value)
        runCurrent()
        assertEquals(ownerA, assertIs<MessageUiState.Ready>(source.state.value).ownerId)
        source.sendFromDisplayedOwner(ownerA, "155501", "synthetic")
        runCurrent()
        assertTrue(transport.requests.isEmpty())
        source.selectConversation(null)
        runCurrent()
        val state = assertIs<MessageUiState.Inbox>(source.state.value)
        assertEquals(ownerB, state.ownerId)
        assertTrue(state.composerEnabled)
        assertEquals(2, source.historyChoices.value.lines.size)
        assertEquals(1, projectMessageConversations(messageProjectionOwner(state), state).rows.size)
        source.selectHistoryOwner(ownerA)
        runCurrent()
        val historical = assertIs<MessageUiState.Inbox>(source.state.value)
        assertEquals(ownerA, historical.ownerId)
        assertFalse(historical.composerEnabled)
        source.sendFromDisplayedOwner(ownerA, "155501", "synthetic")
        runCurrent()
        assertTrue(transport.requests.isEmpty())
        source.selectHistoryOwner(ownerB)
        runCurrent()
        assertTrue(assertIs<MessageUiState.Inbox>(source.state.value).composerEnabled)
        } finally {
            source.close()
        }
    }

    @Test fun attachmentWatcherDoesNotRescanAllHistoricalOwners() = runTest {
        var scans = 0
        val stored = history()
        val repository = object : MessageRepository by stored {
            override val history = object : MessageHistoryRepository by stored.history {
                override suspend fun owners(cursor: MessageOwnerCursor?): MessageOwnerPage {
                    scans++
                    return stored.history.owners(cursor)
                }
            }
        }
        val source = EngineMessagePersistenceFixtures.sourceOf(emptyList(), FakeEngineMessageTransport(), repository)
        try {
            source.watchAttachment()
            runCurrent()
            advanceTimeBy(9_001)
            runCurrent()
            assertEquals(1, scans)
        } finally {
            source.close()
        }
    }

    @Test fun storageFailureIsNotPresentedAsAnEmptyInbox() = runTest {
        val stored = FakeMessageRepository()
        val repository = object : MessageRepository by stored {
            override val history = object : MessageHistoryRepository by stored.history {
                override suspend fun owners(cursor: MessageOwnerCursor?): MessageOwnerPage = throw MessageStorageException()
            }
        }
        val source = EngineMessagePersistenceFixtures.sourceOf(emptyList(), FakeEngineMessageTransport(), repository)
        runCurrent()
        source.close()
        assertIs<MessageUiState.Failure>(source.state.value)
        assertFalse(source.historyChoices.value.storageAvailable)
    }

    @Test fun emptyLocalStorageIsDistinguishedFromUnavailableTransport() = runTest {
        val source = EngineMessagePersistenceFixtures.sourceOf(emptyList(), FakeEngineMessageTransport(), FakeMessageRepository())
        runCurrent()
        source.close()
        assertIs<MessageUiState.Unavailable>(source.state.value)
        assertTrue(source.historyChoices.value.storageAvailable)
        assertTrue(source.historyChoices.value.lines.isEmpty())
    }

    @Test fun localReadLoadingDoesNotMasqueradeAsAnEmptyInbox() = runTest {
        val release = CompletableDeferred<Unit>()
        val stored = history()
        val repository = object : MessageRepository by stored {
            override val history = object : MessageHistoryRepository by stored.history {
                override suspend fun owners(cursor: MessageOwnerCursor?): MessageOwnerPage {
                    release.await()
                    return stored.history.owners(cursor)
                }
            }
        }
        val source = EngineMessagePersistenceFixtures.sourceOf(emptyList(), FakeEngineMessageTransport(), repository)
        try {
            runCurrent()
            assertIs<MessageUiState.Loading>(source.state.value)
            release.complete(Unit)
            runCurrent()
            assertEquals(2, source.historyChoices.value.lines.size)
            assertEquals(1, projectMessageConversations(messageProjectionOwner(source.state.value), source.state.value).rows.size)
        } finally {
            source.close()
        }
    }

    private suspend fun history(): FakeMessageRepository = FakeMessageRepository().also {
        it.insertIncomingIfAbsent(ownerA, "a", "a", "155501", "synthetic", 1, 1)
        it.insertIncomingIfAbsent(ownerB, "b", "b", "155501", "synthetic", 2, 2)
    }
}
