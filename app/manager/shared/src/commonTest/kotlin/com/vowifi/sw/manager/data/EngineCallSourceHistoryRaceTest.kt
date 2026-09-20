@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallDirection
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class EngineCallSourceHistoryRaceTest {
    @Test
    fun detachAndCloseInterruptHistoryButAudioEofWaitsForEngineTerminal() =
        runTest(UnconfinedTestDispatcher()) {
            val closeTransport = FakeCallTransport()
            val closeRepo = FakeCallHistoryRepository()
            val closeSource = historySource(closeTransport, closeRepo)
            closeTransport.emitIncoming(CALL_TOKEN, "RINGING")
            closeSource.close()
            assertEquals(1, closeRepo.listByOwner(OWNER_A).size)
            assertEquals(CallOutcome.INTERRUPTED, closeRepo.listByOwner(OWNER_A).single().outcome)

            val eofTransport = FakeCallTransport()
            val eofRepo = FakeCallHistoryRepository()
            val eofAudio = FakeCallAudioController()
            val eofSource = historySource(eofTransport, eofRepo, audio = eofAudio)
            eofTransport.emitIncoming(CALL_TOKEN, "RINGING")
            eofSource.answer()
            eofTransport.emitIncoming(CALL_TOKEN, "IN_CALL")
            val beforeAudioFailure = historyRecords(eofSource)
            eofAudio.emitEnded()
            assertEquals(beforeAudioFailure, historyRecords(eofSource))
            assertEquals(CallPhase.InCall, assertIs<CallUiState.Ready>(eofSource.state.value).phase)
            eofTransport.emitIncoming(CALL_TOKEN, "ENDED", "remote")
            assertEquals(CallOutcome.COMPLETED, historyRecords(eofSource).single().outcome)
            assertEquals(CallPhase.Idle, assertIs<CallUiState.Ready>(eofSource.state.value).phase)
            eofSource.close()

            var instances = listOf(callRunning(CALL_ICCID_A))
            val failTransport = FakeCallTransport()
            val failRepo = FakeCallHistoryRepository()
            val failSource = historySource(
                failTransport,
                failRepo,
                listInstances = { instances },
            )
            failTransport.emitIncoming(CALL_TOKEN, "RINGING")
            instances = listOf(callStopped(CALL_ICCID_A))
            failSource.refresh()
            assertEquals(CallOutcome.STARTED, failRepo.listByOwner(OWNER_A).single().outcome)
            assertEquals(CallPhase.Ringing, assertIs<CallUiState.Ready>(failSource.state.value).phase)
            assertEquals(com.vowifi.sw.manager.model.CallSyncState.Unavailable, assertIs<CallUiState.Ready>(failSource.state.value).sync)
            failSource.close()
            assertEquals(CallOutcome.INTERRUPTED, failRepo.listByOwner(OWNER_A).single().outcome)
        }

    @Test
    fun closeDrainsBlockedStartedThenInterruptedExactlyOnce() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeCallHistoryRepository().also { it.upsertGate = gate }
        val transport = FakeCallTransport()
        val source = historySource(transport, repository)
        transport.emitIncoming(CALL_TOKEN, "RINGING")

        source.close()
        source.close()
        repository.upsertGate = null
        gate.complete(Unit)
        advanceUntilIdle()

        val rows = repository.listByOwner(OWNER_A)
        assertEquals(1, rows.size)
        assertEquals(CallOutcome.INTERRUPTED, rows.single().outcome)
        assertEquals(2, repository.upsertCount)
    }

    @Test
    fun processRestartMarksLeftoverOpenInterruptedWithoutSecondRow() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = FakeCallHistoryRepository()
            repository.upsert(OWNER_A, historyWrite(outcome = CallOutcome.STARTED))
            val transport = FakeCallTransport()
            val source = historySource(transport, repository)
            val listed = repository.listByOwner(OWNER_A)
            assertEquals(1, listed.size)
            assertEquals(CallOutcome.INTERRUPTED, listed.single().outcome)
            source.close()
        }

    @Test
    fun historyStorageFailureSetsBoundedFailureAndLeavesLiveCallUsable() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = FakeCallTransport()
            val repository = FakeCallHistoryRepository().also { it.failAllUpserts = true }
            val audio = FakeCallAudioController()
            val source = historySource(transport, repository, audio = audio)
            source.dial(CALL_NUMBER)
            val live = assertIs<CallUiState.Ready>(source.state.value)
            assertEquals(CallPhase.InCall, live.phase)
            assertEquals(1, audio.starts.size)
            val history = assertIs<CallHistoryState.Failure>(source.history.value)
            assertEquals(CALL_HISTORY_STORAGE_NOTICE, history.message)
            assertEquals("Failure", history.toString())
            source.hangup()
            assertEquals(CallPhase.Idle, assertIs<CallUiState.Ready>(source.state.value).phase)
            assertTrue(repository.listByOwner(OWNER_A).isEmpty())
            source.close()
        }

    @Test
    fun ownersAndCallsNeverCrossAndPruneStaysTodo4() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeCallHistoryRepository()
        val transportA = FakeCallTransport()
        val transportB = FakeCallTransport()
        val sourceA = historySource(transportA, repository, ownerIdFor = { OWNER_A })
        val sourceB = historySource(
            transportB,
            repository,
            ownerIdFor = { OWNER_B },
            nextCorrelation = { "out-synth-b" },
        )
        sourceA.dial(CALL_NUMBER)
        sourceB.dial(CALL_NUMBER)
        assertEquals(1, repository.listByOwner(OWNER_A).size)
        assertEquals(1, repository.listByOwner(OWNER_B).size)
        assertEquals(CallDirection.OUTGOING, repository.listByOwner(OWNER_A).single().direction)
        sourceA.close()
        sourceB.close()

        val pruneRepo = FakeCallHistoryRepository()
        for (index in 1..500) {
            pruneRepo.upsert(OWNER_A, endedWrite(corr(index), T0 + index))
        }
        pruneRepo.upsert(OWNER_B, endedWrite(corr(1), T0 + 1))
        val pruneTransport = FakeCallTransport()
        val pruneSource = historySource(
            pruneTransport,
            pruneRepo,
            nextCorrelation = { "out-synth-501" },
            clock = TestClock(T0 + 1_000),
        )
        pruneSource.dial(CALL_NUMBER)
        assertEquals(500, pruneRepo.listByOwner(OWNER_A).size)
        assertEquals(T0 + 1_000, pruneRepo.listByOwner(OWNER_A).first().startedAtMs)
        assertEquals(1, pruneRepo.listByOwner(OWNER_B).size)
        pruneSource.close()
    }

    @Test
    fun staleACompletionAfterBStartCannotAffectB() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val repository = FakeCallHistoryRepository()
        val ids = ArrayDeque(listOf("out-call-a", "out-call-b"))
        val source = historySource(transport, repository, nextCorrelation = { ids.removeFirst() })
        val gateA = transport.enqueueGate()
        val gateB = transport.enqueueGate()
        transport.enqueue(dialOk(CALL_TOKEN))
        transport.enqueue(dialOk(CALL_OTHER))
        source.dial(CALL_NUMBER)
        transport.emitCallEnded(CALL_TOKEN, "failed")
        source.dial(CALL_NUMBER)
        assertEquals(CallPhase.Dialing, assertIs<CallUiState.Ready>(source.state.value).phase)

        gateA.complete(Unit)
        val afterStale = repository.listByOwner(OWNER_A)
        assertEquals(2, afterStale.size)
        val bAfterStale = afterStale.single { it.outcome == CallOutcome.STARTED }
        assertEquals(CallOutcome.STARTED, bAfterStale.outcome)
        assertEquals(CallPhase.Dialing, assertIs<CallUiState.Ready>(source.state.value).phase)

        gateB.complete(Unit)
        val finalRows = repository.listByOwner(OWNER_A)
        assertEquals(2, finalRows.size)
        assertEquals(1, finalRows.count { it.outcome == CallOutcome.FAILED })
        assertEquals(1, finalRows.count { it.outcome == CallOutcome.CONNECTED })
        assertEquals(CallPhase.InCall, assertIs<CallUiState.Ready>(source.state.value).phase)
        source.close()
    }

    @Test
    fun crossedPersistOrderKeepsBIsolated() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val repository = FakeCallHistoryRepository()
        val ids = ArrayDeque(listOf("out-call-a", "out-call-b"))
        val source = historySource(transport, repository, nextCorrelation = { ids.removeFirst() })
        val gateA = transport.enqueueGate()
        val gateB = transport.enqueueGate()
        transport.enqueue(dialOk(CALL_TOKEN))
        transport.enqueue(dialOk(CALL_OTHER))
        source.dial(CALL_NUMBER)
        transport.emitCallEnded(CALL_TOKEN, "failed")
        source.dial(CALL_NUMBER)
        gateB.complete(Unit)
        val afterB = repository.listByOwner(OWNER_A)
        val bRow = afterB.single { it.outcome == CallOutcome.CONNECTED || it.outcome == CallOutcome.STARTED }
        gateA.complete(Unit)
        val finalRows = repository.listByOwner(OWNER_A)
        assertEquals(2, finalRows.size)
        val bFinal = finalRows.single { row ->
            row.outcome == CallOutcome.CONNECTED || row.outcome == CallOutcome.STARTED
        }
        assertEquals(bRow.startedAtMs, bFinal.startedAtMs)
        assertEquals(bRow.peer, bFinal.peer)
        assertFalse(finalRows.all { it.outcome == CallOutcome.FAILED })
        source.close()
    }

    @Test
    fun rejectTimeoutCanceledAndHangupMapExhaustively() = runTest(UnconfinedTestDispatcher()) {
        suspend fun endedOutcome(reason: String, connected: Boolean): CallOutcome {
            val transport = FakeCallTransport()
            val repository = FakeCallHistoryRepository()
            val clock = TestClock()
            val source = historySource(transport, repository, clock = clock)
            transport.emitIncoming(CALL_TOKEN, "RINGING")
            if (connected) {
                clock.now = T0 + 1_000
                source.answer()
                transport.emitIncoming(CALL_TOKEN, "IN_CALL")
            }
            clock.now = T0 + 2_000
            transport.emitIncoming(CALL_TOKEN, "ENDED", reason = reason)
            val outcome = historyRecords(source).single().outcome
            source.close()
            return outcome
        }
        assertEquals(CallOutcome.REJECTED, endedOutcome("rejected", false))
        assertEquals(CallOutcome.CANCELED, endedOutcome("canceled", false))
        assertEquals(CallOutcome.MISSED, endedOutcome("timeout", false))
        assertEquals(CallOutcome.CANCELED, endedOutcome("hangup", false))
        assertEquals(CallOutcome.COMPLETED, endedOutcome("hangup", true))
        assertEquals(CallOutcome.CANCELED, endedOutcome("remote", false))
        assertEquals(CallOutcome.COMPLETED, endedOutcome("remote", true))
        assertEquals(CallOutcome.FAILED, endedOutcome("failed", false))
    }

}

private fun corr(index: Int): String = "corr-${index.toString().padStart(4, '0')}"

private fun endedWrite(correlation: String, startedAtMs: Long) = historyWrite(
    correlation = correlation,
    startedAtMs = startedAtMs,
    connectedAtMs = startedAtMs + 1,
    endedAtMs = startedAtMs + 2,
    durationSec = 1,
    outcome = CallOutcome.COMPLETED,
)
