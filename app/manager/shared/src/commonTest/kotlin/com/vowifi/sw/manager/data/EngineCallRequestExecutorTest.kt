@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallFailureMessages
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class EngineCallRequestExecutorTest {
    @Test
    fun staleDialResponseDoesNotStealNewerDialingCall() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, audio)
        val gateA = transport.enqueueGate()
        val gateB = transport.enqueueGate()
        transport.enqueue(dialOk(CALL_TOKEN))
        transport.enqueue(dialOk(CALL_OTHER))
        source.dial(CALL_NUMBER)
        transport.emitCallEnded(CALL_TOKEN, "failed")
        source.dial(CALL_NUMBER)
        assertEquals(CallPhase.Dialing, ready(source).phase)

        gateA.complete(Unit)
        val afterStale = ready(source)
        assertEquals(CallPhase.Dialing, afterStale.phase)
        assertTrue(audio.starts.none { start -> start.token == CALL_TOKEN })
        assertCallPrivacy(afterStale, CALL_TOKEN, CALL_OTHER, CALL_NUMBER)

        gateB.complete(Unit)
        val afterB = ready(source)
        assertEquals(CallPhase.InCall, afterB.phase)
        assertEquals(listOf(CALL_OTHER), audio.starts.map { start -> start.token })
        assertCallPrivacy(afterB, CALL_TOKEN, CALL_OTHER, CALL_NUMBER)
        source.close()
    }

    @Test
    fun olderAnswerResponseDoesNotClearNewerHangupBusy() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        val answerGate = transport.enqueueGate()
        val hangupGate = transport.enqueueGate()
        transport.emitIncoming(CALL_TOKEN, "RINGING")
        source.answer()
        transport.emitIncoming(CALL_TOKEN, "IN_CALL")
        source.hangup()
        assertTrue(ready(source).busy)
        assertEquals(CallPhase.Ending, ready(source).phase)

        answerGate.complete(Unit)
        val afterAnswer = ready(source)
        assertTrue(afterAnswer.busy)
        assertEquals(CallPhase.Ending, afterAnswer.phase)
        assertCallPrivacy(afterAnswer, CALL_TOKEN)

        hangupGate.complete(Unit)
        assertEquals(CallPhase.Idle, ready(source).phase)
        source.close()
    }

    @Test
    fun staleAudioStartDoesNotStopAfterNewerStart() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, audio)
        val startA = audio.enqueueStartGate()
        transport.enqueue(dialOk(CALL_TOKEN))
        transport.enqueue(dialOk(CALL_OTHER))
        source.dial(CALL_NUMBER)
        transport.emitCallEnded(CALL_TOKEN, "remote")
        source.dial(CALL_NUMBER)

        startA.complete(Unit)
        assertEquals(CALL_OTHER, audio.activeToken)
        assertEquals(CallPhase.InCall, ready(source).phase)
        assertCallPrivacy(ready(source), CALL_TOKEN, CALL_OTHER, CALL_NUMBER)
        source.close()
    }

    @Test
    fun malformedErrorFreeControlResultsMapToInvalid() = runTest {
        CallControlAction.entries.forEach { action ->
            val transport = FakeCallTransport()
            transport.enqueue(emptySuccess())
            val result = EngineCallRequestExecutor.execute(
                PlannedCallRequest.Control(transport, action, CALL_TOKEN, TEST_OWNER),
            )
            assertEquals(CallFailureMessages.INVALID, result.operationMessage, action.name)
            assertEquals(null, result.resultToken)
        }
    }

    @Test
    fun malformedAnswerRejectHangupKeepPhaseAndPublishInvalid() = runTest(UnconfinedTestDispatcher()) {
        val answerTransport = FakeCallTransport()
        answerTransport.enqueue(emptySuccess())
        val answerSource = sourceOf(listOf(callRunning(CALL_ICCID_A)), answerTransport)
        answerTransport.emitIncoming(CALL_TOKEN, "RINGING")
        answerSource.answer()
        val afterAnswer = ready(answerSource)
        assertEquals(CallPhase.Ringing, afterAnswer.phase)
        assertEquals(CallFailureMessages.INVALID, afterAnswer.operationMessage)
        answerSource.close()

        val rejectTransport = FakeCallTransport()
        rejectTransport.enqueue(emptySuccess())
        val rejectSource = sourceOf(listOf(callRunning(CALL_ICCID_A)), rejectTransport)
        rejectTransport.emitIncoming(CALL_TOKEN, "RINGING")
        rejectSource.reject()
        val afterReject = ready(rejectSource)
        assertEquals(CallPhase.Ringing, afterReject.phase)
        assertEquals(CallFailureMessages.INVALID, afterReject.operationMessage)
        rejectSource.close()

        val hangupTransport = FakeCallTransport()
        val hangupSource = sourceOf(listOf(callRunning(CALL_ICCID_A)), hangupTransport)
        hangupTransport.emitIncoming(CALL_TOKEN, "RINGING")
        hangupSource.answer()
        hangupTransport.emitIncoming(CALL_TOKEN, "IN_CALL")
        hangupTransport.enqueue(emptySuccess())
        hangupSource.hangup()
        val afterHangup = ready(hangupSource)
        assertEquals(CallPhase.InCall, afterHangup.phase)
        assertEquals(CallFailureMessages.INVALID, afterHangup.operationMessage)
        hangupSource.close()
    }

    @Test
    fun dialNoAnswerMapsToBoundedUnansweredMessage() = runTest {
        val transport = FakeCallTransport(
            responses = mapOf("dial" to protocolError(2002, "no_answer")),
        )
        val result = EngineCallRequestExecutor.execute(PlannedCallRequest.Dial(transport, CALL_NUMBER, TEST_OWNER))
        assertEquals("无人接听", result.operationMessage)
        assertEquals(null, result.resultToken)
    }

    private fun ready(source: EngineCallSource): CallUiState.Ready = assertIs(source.state.value)
}

private val TEST_OWNER = CallRequestOwner(generation = 1, operationId = 1)
