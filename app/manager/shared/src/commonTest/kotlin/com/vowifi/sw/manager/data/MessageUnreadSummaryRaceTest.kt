@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageUnreadSummary
import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.MessageUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class MessageUnreadSummaryRaceTest {
    @Test
    fun staleQueryIsDiscardedAndRequeriedWhenOwnerBArrivesDuringOwnerAReadSummary() = runTest {
        val backing = seededUnreadRepository()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var holdNextCount = false
        val repository = object : MessageRepository by backing {
            override suspend fun countUnreadIncoming(): Long {
                val snapshot = backing.countUnreadIncoming()
                if (holdNextCount) {
                    holdNextCount = false
                    started.complete(Unit)
                    release.await()
                }
                return snapshot
            }
        }
        val source = unreadSource(repository, this)
        val observed = mutableListOf<MessageUnreadSummary>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.unreadSummary.collect { observed += it } }
        try {
            runCurrent()
            holdNextCount = true
            source.markVisibleRead(unreadRequest(OWNER_A, "a-1"))
            runCurrent()
            started.await()
            backing.insertIncomingIfAbsent(OWNER_B, "b-later", "engine-b-later", "+155501", "synthetic", 10, 10)
            source.refresh()
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(MessageUnreadSummary.Ready(5), source.unreadSummary.value)
            assertFalse(MessageUnreadSummary.Ready(4) in observed)
        } finally { source.close() }
    }

    @Test
    fun readAwaitDoesNotChangeBadgeBeforeCommitAndOtherOwnerArrivalIsIncluded() = runTest {
        val backing = seededUnreadRepository()
        val release = CompletableDeferred<Unit>()
        val repository = object : MessageRepository by backing {
            override suspend fun markIncomingRead(request: ObservedIncomingReadRequest, nowMs: Long): IncomingReadUpdate {
                release.await()
                return backing.markIncomingRead(request, nowMs)
            }
        }
        var iccid = "8986001"
        val transport = FakeEngineMessageTransport()
        val source = EngineMessageSource(
            EngineMessageRuntime(
                listInstances = { listOf(EngineInstanceState(iccid, EngineLifecycle.RUNNING, pid = 7)) },
                transportFor = { transport }, clock = { 9000L },
                activeIccids = { listOf(iccid) },
            ),
            coroutineContext, repository, ::ownerFor, sequentialIds("late-b"),
        )
        try {
            runCurrent()
            source.markVisibleRead(unreadRequest(OWNER_A, "a-1"))
            runCurrent()
            assertEquals(MessageUnreadSummary.Ready(5), source.unreadSummary.value)
            iccid = "8986002"
            source.refresh()
            runCurrent()
            transport.emitReceived("engine-b-later", "+155501", "synthetic", 10)
            runCurrent()
            assertEquals(MessageUnreadSummary.Ready(6), source.unreadSummary.value)
            release.complete(Unit)
            runCurrent()
            assertEquals(MessageUnreadSummary.Ready(5), source.unreadSummary.value)
            val selected = assertIs<MessageUiState.Inbox>(source.state.value)
            assertEquals(OWNER_B, selected.ownerId)
            assertEquals(4L, selected.page.items.sumOf { it.messageCount })
        } finally { source.close() }
    }

    @Test
    fun failedReadDoesNotDecrementKnownTotal() = runTest {
        val repository = seededUnreadRepository().also { it.failNextMarkRead = true }
        val source = unreadSource(repository, this)
        try {
            runCurrent()
            source.markVisibleRead(unreadRequest(OWNER_A, "a-1"))
            runCurrent()
            assertEquals(MessageUnreadSummary.Ready(5), source.unreadSummary.value)
            assertEquals(5L, repository.countUnreadIncoming())
        } finally { source.close() }
    }
}
