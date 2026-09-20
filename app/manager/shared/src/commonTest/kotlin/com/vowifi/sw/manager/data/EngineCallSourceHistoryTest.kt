@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallDirection
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallRecord
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.EngineInstanceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class EngineCallSourceHistoryTest {
    @Test
    fun outgoingPlanDialCreatesOneStartedDraftWithPrivateBoundedPeer() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val transport = FakeCallTransport(requestGate = gate)
            val repository = FakeCallHistoryRepository()
            val source = historySource(transport, repository)
            source.dial(CALL_NUMBER)

            val records = historyRecords(source)
            assertEquals(1, records.size)
            assertEquals(CallDirection.OUTGOING, records.single().direction)
            assertEquals(CallOutcome.STARTED, records.single().outcome)
            assertEquals(SYNTH_PHONE, records.single().peer?.digits())
            assertNull(records.single().connectedAtMs)
            val ready = assertIs<CallUiState.Ready>(source.state.value)
            assertEquals(CallPhase.Dialing, ready.phase)
            assertEquals(CALL_NUMBER, ready.peer?.text())
            assertFalse(ready.toString().contains(CALL_NUMBER))
            assertFalse(CallSession().toString().contains(CALL_NUMBER))
            assertCallPrivacy(ready, CALL_NUMBER, CALL_TOKEN)
            assertHistoryPrivacy(source.history.value, CALL_NUMBER, CALL_TOKEN)
            source.close()
            gate.complete(Unit)
        }

    @Test
    fun dialingAndInCallBeforeResponseBindSameRowAndLateResponseDoesNotDuplicate() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val transport = FakeCallTransport(requestGate = gate)
            val repository = FakeCallHistoryRepository()
            val clock = TestClock()
            val source = historySource(transport, repository, clock = clock)
            source.dial(CALL_NUMBER)
            transport.emitCallState(CALL_TOKEN, "DIALING")
            clock.now = T0 + 1_000
            transport.emitCallState(CALL_TOKEN, "IN_CALL")
            assertEquals(1, historyRecords(source).size)
            assertEquals(CallOutcome.CONNECTED, historyRecords(source).single().outcome)
            assertEquals(T0 + 1_000, historyRecords(source).single().connectedAtMs)

            gate.complete(Unit)
            assertEquals(1, historyRecords(source).size)
            assertEquals(CallOutcome.CONNECTED, historyRecords(source).single().outcome)
            assertEquals(CallPhase.InCall, assertIs<CallUiState.Ready>(source.state.value).phase)
            source.close()
        }

    @Test
    fun lateFailedDialResponseAfterEndedDoesNotResurrect() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeCallTransport(requestGate = gate)
        val repository = FakeCallHistoryRepository()
        val source = historySource(transport, repository)
        source.dial(CALL_NUMBER)
        transport.emitCallEnded(CALL_TOKEN, "failed")
        assertEquals(CallOutcome.FAILED, historyRecords(source).single().outcome)
        gate.complete(Unit)
        assertEquals(1, historyRecords(source).size)
        assertEquals(CallOutcome.FAILED, historyRecords(source).single().outcome)
        assertEquals(CallPhase.Idle, assertIs<CallUiState.Ready>(source.state.value).phase)
        source.close()
    }

    @Test
    fun duplicateRingingSameTokenIsOneIncomingRecordWithFirstWinsPeer() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = FakeCallTransport()
            val repository = FakeCallHistoryRepository()
            val source = historySource(transport, repository)
            transport.emitIncoming(CALL_TOKEN, "RINGING")
            transport.emitIncoming(CALL_TOKEN, "RINGING", peer = SYNTH_PHONE_PLUS)
            transport.emitIncoming(CALL_TOKEN, "RINGING", peer = "15550199")
            val records = historyRecords(source)
            assertEquals(1, records.size)
            assertEquals(CallDirection.INCOMING, records.single().direction)
            assertEquals(CallOutcome.STARTED, records.single().outcome)
            assertEquals(SYNTH_PHONE, records.single().peer?.digits())
            source.close()
        }

    @Test
    fun differentTokenCannotAlterCurrentIncomingRecord() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val repository = FakeCallHistoryRepository()
        val source = historySource(transport, repository)
        transport.emitIncoming(CALL_TOKEN, "RINGING", peer = SYNTH_PHONE_PLUS)
        transport.emitIncoming(CALL_OTHER, "RINGING", peer = "15550199")
        val records = historyRecords(source)
        assertEquals(1, records.size)
        assertEquals(SYNTH_PHONE, records.single().peer?.digits())
        assertEquals(CallPhase.Ringing, assertIs<CallUiState.Ready>(source.state.value).phase)
        source.close()
    }

    @Test
    fun answerResponseIsNotConnectedOnlyInCallSetsConnectedTime() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = FakeCallTransport()
            val repository = FakeCallHistoryRepository()
            val clock = TestClock()
            val source = historySource(transport, repository, clock = clock)
            transport.emitIncoming(CALL_TOKEN, "RINGING", peer = SYNTH_PHONE_PLUS)
            source.answer()
            val afterAnswer = historyRecords(source).single()
            assertEquals(CallOutcome.STARTED, afterAnswer.outcome)
            assertNull(afterAnswer.connectedAtMs)
            assertEquals(CallPhase.Connecting, assertIs<CallUiState.Ready>(source.state.value).phase)
            clock.now = T0 + 2_000
            transport.emitIncoming(CALL_TOKEN, "IN_CALL")
            val connected = historyRecords(source).single()
            assertEquals(CallOutcome.CONNECTED, connected.outcome)
            assertEquals(T0 + 2_000, connected.connectedAtMs)
            source.close()
        }

    @Test
    fun incomingAndOutgoingEndedUpdateSameRowOnceWithBoundedDuration() =
        runTest(UnconfinedTestDispatcher()) {
            val incomingTransport = FakeCallTransport()
            val incomingRepo = FakeCallHistoryRepository()
            val clock = TestClock()
            val incoming = historySource(incomingTransport, incomingRepo, clock = clock)
            incomingTransport.emitIncoming(CALL_TOKEN, "RINGING", peer = SYNTH_PHONE_PLUS)
            clock.now = T0 + 1_000
            incomingTransport.emitIncoming(CALL_TOKEN, "IN_CALL")
            clock.now = T0 + 8_000
            incomingTransport.emitIncoming(CALL_TOKEN, "ENDED", reason = "remote", durationSec = 7)
            val incomingRow = historyRecords(incoming).single()
            assertEquals(CallOutcome.COMPLETED, incomingRow.outcome)
            assertEquals(7, incomingRow.durationSec)
            assertEquals(1, incomingRepo.listByOwner(OWNER_A).size)
            incoming.close()

            val outgoingTransport = FakeCallTransport()
            val outgoingRepo = FakeCallHistoryRepository()
            val outgoingClock = TestClock()
            val outgoing = historySource(outgoingTransport, outgoingRepo, clock = outgoingClock)
            outgoing.dial(CALL_NUMBER)
            outgoingClock.now = T0 + 1_000
            outgoingTransport.emitCallState(CALL_TOKEN, "IN_CALL")
            outgoingClock.now = T0 + 4_000
            outgoingTransport.emitCallEnded(CALL_TOKEN, "hangup", durationSec = 3)
            val outgoingRow = historyRecords(outgoing).single()
            assertEquals(CallOutcome.COMPLETED, outgoingRow.outcome)
            assertEquals(3, outgoingRow.durationSec)
            assertEquals(1, outgoingRepo.listByOwner(OWNER_A).size)
            outgoing.close()
        }

    @Test
    fun unsafeIncomingPeerIsAbsentNeverRaw() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val repository = FakeCallHistoryRepository()
        val source = historySource(transport, repository)
        transport.emitIncoming(CALL_TOKEN, "RINGING", peer = "sip:alice@example.invalid")
        val row = historyRecords(source).single()
        assertNull(row.peer)
        assertFalse(row.toString().contains("raw:"))
        assertFalse(row.toString().contains("sip:"))
        assertHistoryPrivacy(source.history.value, "sip:alice@example.invalid", CALL_TOKEN)
        source.close()
    }
}

internal class TestClock(var now: Long = T0) {
    operator fun invoke(): Long = now
}

internal suspend fun historySource(
    transport: FakeCallTransport,
    repository: FakeCallHistoryRepository,
    audio: FakeCallAudioController = FakeCallAudioController(),
    clock: TestClock = TestClock(),
    ownerIdFor: (String) -> SimOwnerId? = { OWNER_A },
    nextCorrelation: () -> String = { "out-synth-1" },
    instances: List<EngineInstanceState> = listOf(callRunning(CALL_ICCID_A)),
    listInstances: (suspend () -> List<EngineInstanceState>)? = null,
): EngineCallSource = sourceOf(
    instances = instances,
    transport = transport,
    audio = audio,
    history = repository,
    ownerIdFor = ownerIdFor,
    clock = { clock.now },
    nextCorrelation = nextCorrelation,
    listInstances = listInstances,
)

internal fun historyRecords(source: EngineCallSource): List<CallRecord> =
    when (val state = source.history.value) {
        is CallHistoryState.Ready -> state.records
        CallHistoryState.Empty -> emptyList()
        CallHistoryState.Loading -> emptyList()
        is CallHistoryState.Failure -> error("history failed")
    }

internal fun assertHistoryPrivacy(state: CallHistoryState, vararg forbidden: String) {
    val text = state.toString()
    forbidden.forEach { item ->
        assertFalse(text.contains(item), "leaked $item in $text")
    }
    assertPrivate(text)
    assertFalse(text.contains("sip:"))
    assertFalse(text.contains("raw:"))
}
