@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.data

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.*

class MessageConversationLookupTest {
    @Test fun lookupUsesTheExplicitOwnerAndNormalizedNumberWithoutSelectingReadingOrSending() = runTest {
        val repository = FakeMessageRepository()
        repository.insertIncomingIfAbsent(OWNER_A, "lookup-incoming", "lookup-engine", "+155501", "synthetic lookup", 1, 1)
        val transport = FakeEngineMessageTransport()
        val source = EngineMessagePersistenceFixtures.sourceOf(emptyList(), transport, repository)
        try {
            runCurrent()
            val state = source.state.value
            val choices = source.historyChoices.value
            val unread = repository.countUnreadIncoming()
            val lastOwner = repository.lastOwner()
            assertEquals(StoredMessageConversation(OWNER_A, "155501").handle, source.findConversation(OWNER_A, "155501"))
            assertEquals(source.findConversation(OWNER_A, "155501"), source.findConversation(OWNER_A, "+155501"))
            assertNull(source.findConversation(OWNER_B, "+155501"))
            assertNull(source.findConversation(OWNER_A, "+155502"))
            assertSame(state, source.state.value)
            assertEquals(choices, source.historyChoices.value)
            assertEquals(lastOwner, repository.lastOwner())
            assertEquals(unread, repository.countUnreadIncoming())
            assertTrue(transport.requests.isEmpty())
            source.close()
            assertNull(source.findConversation(OWNER_A, "+155501"))
        } finally { source.close(); runCurrent(); repository.close() }
    }

    @Test fun optionalLookupStorageFailureDoesNotMutateTheSourceOrEscapeAsAnUncaughtUiError() = runTest {
        val stored = FakeMessageRepository()
        val repository = object : MessageRepository by stored {
            override val history = object : MessageHistoryRepository by stored.history {
                override suspend fun thread(identity: StoredMessageConversation, cursor: MessageThreadCursor?): MessageThreadSlice =
                    throw MessageStorageException()
            }
        }
        val source = EngineMessagePersistenceFixtures.sourceOf(emptyList(), FakeEngineMessageTransport(), repository)
        try {
            runCurrent()
            val state = source.state.value
            assertNull(source.findConversation(OWNER_A, "+155501"))
            assertSame(state, source.state.value)
        } finally { source.close(); runCurrent(); stored.close() }
    }
}
