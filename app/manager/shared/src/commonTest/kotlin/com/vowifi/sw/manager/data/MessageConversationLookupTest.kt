@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.data

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*

class MessageConversationLookupTest {
    @Test fun explicitLookupDistinguishesMissingUnreadableAndClosedWithoutReadingOrSelecting() = runTest {
        val stored = FakeMessageRepository()
        var fail = false
        val repository = object : MessageRepository by stored {
            override val history = object : MessageHistoryRepository by stored.history {
                override suspend fun hasConversation(identity: StoredMessageConversation): Boolean {
                    if (fail) throw MessageStorageException()
                    return identity == StoredMessageConversation(OWNER_A, "155501")
                }
            }
        }
        val source = EngineMessagePersistenceFixtures.sourceOf(emptyList(), FakeEngineMessageTransport(), repository)
        try {
            runCurrent()
            val before = source.state.value
            assertEquals(MessageConversationLookup.Found(StoredMessageConversation(OWNER_A, "155501").handle),
                source.lookupConversation(OWNER_A, "+155501"))
            assertEquals(MessageConversationLookup.Missing, source.lookupConversation(OWNER_B, "+155501"))
            fail = true
            assertEquals(MessageConversationLookup.Unavailable, source.lookupConversation(OWNER_A, "+155501"))
            assertSame(before, source.state.value)
            source.close()
            assertEquals(MessageConversationLookup.Unavailable, source.lookupConversation(OWNER_A, "+155501"))
        } finally { source.close(); runCurrent(); stored.close() }
    }

    @Test fun lookupUsesExistenceWithoutReadingTheThreadBody() = runTest {
        val stored = FakeMessageRepository()
        val calls = mutableListOf<StoredMessageConversation>()
        var threadCalls = 0
        val repository = object : MessageRepository by stored {
            override val history = object : MessageHistoryRepository by stored.history {
                override suspend fun hasConversation(identity: StoredMessageConversation): Boolean {
                    calls += identity
                    return identity == StoredMessageConversation(OWNER_A, "155501")
                }
                override suspend fun thread(identity: StoredMessageConversation, cursor: MessageThreadCursor?): MessageThreadSlice {
                    threadCalls++
                    throw MessageStorageException()
                }
            }
        }
        val source = EngineMessagePersistenceFixtures.sourceOf(emptyList(), FakeEngineMessageTransport(), repository)
        try {
            runCurrent()
            assertEquals(StoredMessageConversation(OWNER_A, "155501").handle, source.findConversation(OWNER_A, "+155501"))
            assertNull(source.findConversation(OWNER_B, "+155501"))
            assertEquals(listOf(StoredMessageConversation(OWNER_A, "155501"), StoredMessageConversation(OWNER_B, "155501")), calls)
            assertEquals(0, threadCalls, "存在性建议不读取消息正文")
        } finally { source.close(); runCurrent(); stored.close() }
    }

    @Test fun cancelledExistenceLookupPropagatesCancellationRatherThanReportingAnAbsentConversation() = runTest {
        val stored = FakeMessageRepository()
        val cancelled = CancellationException("synthetic lookup cancellation")
        val repository = object : MessageRepository by stored {
            override val history = object : MessageHistoryRepository by stored.history {
                override suspend fun hasConversation(identity: StoredMessageConversation): Boolean = throw cancelled
            }
        }
        val source = EngineMessagePersistenceFixtures.sourceOf(emptyList(), FakeEngineMessageTransport(), repository)
        try {
            runCurrent()
            assertSame(cancelled, assertFailsWith<CancellationException> { source.findConversation(OWNER_A, "+155501") })
        } finally { source.close(); runCurrent(); stored.close() }
    }

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
                override suspend fun hasConversation(identity: StoredMessageConversation): Boolean =
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
