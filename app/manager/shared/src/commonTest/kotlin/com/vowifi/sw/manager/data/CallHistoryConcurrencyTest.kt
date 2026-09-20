@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class CallHistoryConcurrencyTest {
    @Test
    fun terminalPersistsOnceWhenRefreshFloodsBlockedWrite() = runTest(UnconfinedTestDispatcher()) {
        val saved = FakeCallHistoryRepository()
        val gate = CompletableDeferred<Unit>()
        val repository = object : CallHistoryRepository by saved {
            override suspend fun upsert(ownerId: SimOwnerId, write: CallHistoryWrite) {
                gate.await()
                saved.upsert(ownerId, write)
            }
        }
        val transport = FakeCallTransport()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, history = repository, clock = { T0 })
        try {
            transport.emitIncoming(CALL_TOKEN, "RINGING")

            repeat(64) { source.refresh() }
            transport.emitIncoming(CALL_TOKEN, "ENDED", reason = "rejected")
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(CallOutcome.REJECTED, saved.listByOwner(OWNER_A).single().outcome)
            assertEquals(2, saved.upsertCount)
            assertIs<CallHistoryState.Ready>(source.history.value)
        } finally {
            gate.complete(Unit)
            source.close()
            advanceUntilIdle()
        }
    }

    @Test
    fun publicRefreshAndIncomingWaitBehindStartupRecovery() = runTest(UnconfinedTestDispatcher()) {
        val saved = FakeCallHistoryRepository()
        val recovery = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val repository = object : CallHistoryRepository by saved {
            override suspend fun interruptOpen(endedAtMs: Long) {
                order += "recover-start"
                recovery.await()
                saved.interruptOpen(endedAtMs)
                order += "recover-end"
            }
            override suspend fun upsert(ownerId: SimOwnerId, write: CallHistoryWrite) {
                order += "write"
                saved.upsert(ownerId, write)
            }
            override suspend fun listByOwner(ownerId: SimOwnerId) = saved.listByOwner(ownerId).also { order += "list" }
        }
        val transport = FakeCallTransport()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, history = repository, clock = { T0 })
        try {
            source.refresh()
            transport.emitIncoming(CALL_TOKEN, "RINGING")
            val beforeRelease = order.toList()

            recovery.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("recover-start"), beforeRelease)
            assertEquals(listOf("recover-start", "recover-end"), order.take(2))
            assertEquals(CallOutcome.STARTED, saved.listByOwner(OWNER_A).single().outcome)
            assertEquals(CallPhase.Ringing, assertIs<CallUiState.Ready>(source.state.value).phase)
        } finally {
            recovery.complete(Unit)
            source.close()
            advanceUntilIdle()
        }
    }

    @Test
    fun recoveryFailureDoesNotPublishReadyOrInterruptLiveCalls() = runTest(UnconfinedTestDispatcher()) {
        val saved = FakeCallHistoryRepository()
        val repository = object : CallHistoryRepository by saved {
            override suspend fun interruptOpen(endedAtMs: Long) { throw CallHistoryStorageException() }
        }
        val transport = FakeCallTransport()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, history = repository)
        try {
            source.refresh()
            transport.emitIncoming(CALL_TOKEN, "RINGING")
            advanceUntilIdle()

            assertIs<CallHistoryState.Failure>(source.history.value)
            assertEquals(0, saved.upsertCount)
            assertEquals(CallPhase.Ringing, assertIs<CallUiState.Ready>(source.state.value).phase)
        } finally { source.close(); advanceUntilIdle() }
    }

    @Test
    fun failedWriteCannotBeMaskedByLaterRefresh() = runTest(UnconfinedTestDispatcher()) {
        val saved = FakeCallHistoryRepository()
        val store = CallHistoryStore(saved, coroutineContext)
        try {
            store.select(OWNER_A)
            saved.failNextUpsert = true

            store.write(CallHistoryEffect(1, OWNER_A, historyWrite()))
            repeat(32) { store.select(OWNER_A) }
            advanceUntilIdle()

            assertIs<CallHistoryState.Failure>(store.state.value)
        } finally { store.close(); advanceUntilIdle() }
    }

    @Test
    fun repeatedClearCannotEvictTerminalBehindBlockedWrite() = runTest(UnconfinedTestDispatcher()) {
        val saved = FakeCallHistoryRepository()
        saved.upsert(OWNER_A, historyWrite(outcome = CallOutcome.REJECTED, endedAtMs = T0 + 1))
        val store = CallHistoryStore(saved, coroutineContext)
        val gate = CompletableDeferred<Unit>()
        try {
            store.select(OWNER_A)
            val confirmation = assertIs<CallHistoryState.Ready>(store.state.value)
            saved.upsertGate = gate
            store.write(CallHistoryEffect(1, OWNER_A, historyWrite(correlation = "pending-call")))

            repeat(64) { store.clear(confirmation) }
            store.write(CallHistoryEffect(2, OWNER_A, historyWrite(correlation = "pending-call",
                outcome = CallOutcome.REJECTED, endedAtMs = T0 + 1)))
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(2, saved.listByOwner(OWNER_A).size)
            assertTrue(saved.listByOwner(OWNER_A).all { it.outcome == CallOutcome.REJECTED })
            assertEquals(3, saved.upsertCount)
        } finally { gate.complete(Unit); store.close(); advanceUntilIdle() }
    }

    @Test
    fun closeDrainsAcceptedTerminalWhenIoCompletesWithinDeadline() = runTest(UnconfinedTestDispatcher()) {
        val saved = FakeCallHistoryRepository()
        val gate = CompletableDeferred<Unit>()
        val store = CallHistoryStore(saved, coroutineContext)
        saved.upsertGate = gate
        store.write(CallHistoryEffect(1, OWNER_A, historyWrite()))
        store.write(CallHistoryEffect(2, OWNER_A, historyWrite(outcome = CallOutcome.REJECTED, endedAtMs = T0 + 1)))

        store.close()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(CallOutcome.REJECTED, saved.listByOwner(OWNER_A).single().outcome)
        assertEquals(2, saved.upsertCount)
    }

    @Test
    fun closeCancelsBlockedIoWithinDrainDeadlineWithoutBlockingCaller() = runTest(UnconfinedTestDispatcher()) {
        val saved = FakeCallHistoryRepository()
        val blocked = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var canceled = false
        val repository = object : CallHistoryRepository by saved {
            override suspend fun upsert(ownerId: SimOwnerId, write: CallHistoryWrite) {
                entered.complete(Unit)
                try { blocked.await() } finally { canceled = true }
            }
        }
        val store = CallHistoryStore(repository, coroutineContext)
        try {
            store.write(CallHistoryEffect(1, OWNER_A, historyWrite()))
            entered.await()

            store.close()
            assertFalse(canceled)
            advanceTimeBy(5_001)
            runCurrent()

            assertTrue(canceled)
            assertIs<CallHistoryState.Failure>(store.state.value)
        } finally {
            blocked.complete(Unit)
            store.close()
            advanceUntilIdle()
        }
    }
}
