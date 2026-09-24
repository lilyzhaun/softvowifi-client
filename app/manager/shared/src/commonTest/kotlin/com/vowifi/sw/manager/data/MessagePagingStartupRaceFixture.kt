package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageUiState
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CompletableDeferred

internal class MessagePagingStartupRaceFixture {
    private val store = FakeMessageRepository()
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    val otherOwner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
    private val events = mutableListOf<String>()
    val recoveryEntered = CompletableDeferred<Unit>()
    val releaseRecovery = CompletableDeferred<Unit>()
    val appendEntered = CompletableDeferred<Unit>()
    val releaseAppend = CompletableDeferred<Unit>()
    val refreshEntered = CompletableDeferred<Unit>()
    val releaseRefresh = CompletableDeferred<Unit>()
    var cursorRequests = 0
        private set
    var cancelledCursorRequests = 0
        private set
    var cursorFailuresRemaining = 0
    var failedCursorRequests = 0
        private set
    private var requests = 0
    private var headRequests = 0
    private var transportCalls = 0
    private var runtimeLists = 0

    private val repository = object : MessageRepository by store {
        override suspend fun interruptLeftoverQueued(ownerId: SimOwnerId, nowMs: Long) {
            events += "recovery-enter"
            recoveryEntered.complete(Unit)
            releaseRecovery.await()
            store.interruptLeftoverQueued(ownerId, nowMs)
            events += "recovery-return"
        }

        override val history = object : MessageHistoryRepository by store.history {
            override suspend fun summaries(request: MessageSummaryRequest): MessageSummaryPage {
                val id = ++requests
                val cursor = request.cursor != null
                events += "request=$id cursor=$cursor enter"
                try {
                    if (cursor) {
                        cursorRequests++
                        appendEntered.complete(Unit)
                        releaseAppend.await()
                        if (cursorFailuresRemaining > 0) {
                            cursorFailuresRemaining--
                            failedCursorRequests++
                            events += "request=$id storage-failed"
                            throw MessageStorageException()
                        }
                    } else {
                        headRequests++
                        if (headRequests > 1) {
                            refreshEntered.complete(Unit)
                            releaseRefresh.await()
                        }
                    }
                    val page = store.history.summaries(request)
                    events += "request=$id return=${page.items.size} next=${page.next != null}"
                    return page
                } catch (cancelled: CancellationException) {
                    if (cursor) cancelledCursorRequests++
                    events += "request=$id cursor=$cursor cancelled"
                    throw cancelled
                }
            }
        }
    }

    suspend fun seed(includeOtherOwner: Boolean = false) {
        repeat(121) { index ->
            store.insertIncomingIfAbsent(owner, "row-$index", "engine-$index", "1555$index", "synthetic", index.toLong(), 1)
            store.insertIncomingIfAbsent(owner, "thread-$index", "thread-engine-$index", "1666", "long-window", index.toLong(), 1)
            if (includeOtherOwner) {
                store.insertIncomingIfAbsent(otherOwner, "other-$index", "other-engine-$index", "1555$index", "synthetic", index.toLong(), 1)
            }
        }
    }

    fun open(context: CoroutineContext) = EngineMessageSource(
        EngineMessageRuntime(
            listInstances = { runtimeLists++; events += "runtime-list-empty"; emptyList() },
            transportFor = { transportCalls++; error("No engine permitted in this test") },
            clock = { 1L },
        ), context, repository, { owner }, { "unused" },
    )

    fun observe(source: EngineMessageSource, checkpoint: String): MessageUiState.Inbox {
        val state = assertIs<MessageUiState.Inbox>(source.state.value)
        events += "$checkpoint items=${state.page.items.size} loading=${state.page.loading} " +
            "failed=${state.page.failed} next=${state.page.next != null} composer=${state.composerEnabled}"
        return state
    }

    fun assertFirstPage(source: EngineMessageSource) {
        val initial = observe(source, "first-page")
        assertEquals(50, initial.page.items.size)
        assertFalse(initial.page.loading)
        assertFalse(initial.page.failed)
        assertFalse(initial.composerEnabled)
        assertNotNull(initial.page.next)
        assertEquals(0, runtimeLists)
        assertEquals(0, transportCalls)
    }

    fun evidence() = events.joinToString("\n") +
        "\ncursorRequests=$cursorRequests cancelledCursorRequests=$cancelledCursorRequests failedCursorRequests=$failedCursorRequests " +
        "runtimeLists=$runtimeLists transportCalls=$transportCalls"

    fun close(source: EngineMessageSource) {
        source.close()
        store.close()
    }
}
