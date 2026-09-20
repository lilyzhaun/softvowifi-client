@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallAudioState
import com.vowifi.sw.manager.model.CallFailureMessages
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

class EngineCallSourceTest {
    @Test
    fun ringingEventMovesToRingingWithoutExposingToken() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)

        transport.emitIncoming(CALL_TOKEN, "RINGING")

        val ready = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Ringing, ready.phase)
        assertFalse(ready.busy)
        assertCallPrivacy(ready, CALL_TOKEN)
        source.close()
    }

    @Test
    fun answerSendsExactParamsAndStaysConnectingUntilInCall() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, audio)
        transport.emitIncoming(CALL_TOKEN, "RINGING")

        source.answer()

        val request = transport.requests.single()
        assertEquals("answer", request.method)
        assertEquals(CALL_TOKEN, request.params.getValue("callId").jsonPrimitive.content)
        assertEquals(setOf("callId"), request.params.keys)
        assertEquals(13_000L, request.timeoutMillis)
        val connecting = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Connecting, connecting.phase)
        assertFalse(connecting.busy)
        assertEquals(null, connecting.operationMessage)
        assertEquals(0, audio.starts.size)

        transport.emitIncoming(CALL_TOKEN, "IN_CALL")
        val inCall = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.InCall, inCall.phase)
        assertEquals(CallAudioState.Active, inCall.audio)
        assertEquals(1, audio.starts.size)
        assertEquals(CALL_ICCID_A, audio.starts.single().iccid)
        assertEquals(CALL_TOKEN, audio.starts.single().token)
        assertTrue(audio.starts.single().transport === transport)
        assertNoGetStatus(transport)
        assertCallPrivacy(inCall, CALL_TOKEN)
        source.close()
    }

    @Test
    fun duplicateInCallStartsAudioOnce() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, audio)
        transport.emitIncoming(CALL_TOKEN, "RINGING")
        source.answer()
        transport.emitIncoming(CALL_TOKEN, "IN_CALL")
        transport.emitIncoming(CALL_TOKEN, "IN_CALL")

        assertEquals(1, audio.starts.size)
        assertEquals(CallPhase.InCall, assertIs<CallUiState.Ready>(source.state.value).phase)
        source.close()
    }

    @Test
    fun mismatchedAndUnknownEventsAreIgnored() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        transport.emitIncoming(CALL_TOKEN, "RINGING")
        val ringing = source.state.value

        transport.emitIncoming(CALL_OTHER, "RINGING")
        transport.emitIncoming(CALL_OTHER, "IN_CALL")
        transport.emitIncoming(CALL_OTHER, "ENDED", "remote")
        transport.emitIncoming(CALL_TOKEN, "DIALING")
        transport.emitIncoming(CALL_TOKEN, "ENDED", "busy")
        transport.emitSms()

        assertEquals(ringing, source.state.value)
        source.close()
    }

    @Test
    fun rejectOnlyFromRingingWithDefaultTimeout() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)

        source.reject()
        assertTrue(transport.requests.isEmpty())

        transport.emitIncoming(CALL_TOKEN, "RINGING")
        source.reject()
        val request = transport.requests.single()
        assertEquals("reject", request.method)
        assertEquals(CALL_TOKEN, request.params.getValue("callId").jsonPrimitive.content)
        assertEquals(5_000L, request.timeoutMillis)
        val idle = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Idle, idle.phase)
        assertCallPrivacy(idle, CALL_TOKEN)
        source.close()
    }

    @Test
    fun confirmedHangupStopsAudioAndRingingStillRejectsHangup() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, audio)
        transport.emitIncoming(CALL_TOKEN, "RINGING")
        source.hangup()
        assertTrue(transport.requests.isEmpty())

        source.answer()
        assertEquals(CallPhase.Connecting, assertIs<CallUiState.Ready>(source.state.value).phase)
        assertEquals(listOf("answer"), transport.requests.map { it.method })

        transport.emitIncoming(CALL_TOKEN, "IN_CALL")
        val beforeHangupStops = audio.stopCalls
        source.hangup()
        val hangup = transport.requests.last()
        assertEquals("hangup", hangup.method)
        assertEquals(CALL_TOKEN, hangup.params.getValue("callId").jsonPrimitive.content)
        assertEquals(5_000L, hangup.timeoutMillis)
        val idle = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Idle, idle.phase)
        assertEquals(CallAudioState.Idle, idle.audio)
        assertTrue(audio.stopCalls > beforeHangupStops)
        assertNoGetStatus(transport)
        source.close()
    }

    @Test
    fun endedFromAnyPhaseStopsAudioAndReturnsIdle() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, audio)
        transport.emitIncoming(CALL_TOKEN, "RINGING")
        source.answer()
        transport.emitIncoming(CALL_TOKEN, "IN_CALL")
        val stopsBefore = audio.stopCalls

        transport.emitIncoming(CALL_TOKEN, "ENDED", "remote")
        val idle = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Idle, idle.phase)
        assertEquals(CallAudioState.Idle, idle.audio)
        assertTrue(audio.stopCalls > stopsBefore)

        transport.emitIncoming(CALL_TOKEN, "ENDED", "hangup")
        assertEquals(CallPhase.Idle, assertIs<CallUiState.Ready>(source.state.value).phase)
        source.close()
    }

    @Test
    fun answerFailureStaysRingingWithoutRawProtocolError() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport(
            responses = mapOf("answer" to protocolError(1001, "invalid call request sip:bob@example.com")),
        )
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        transport.emitIncoming(CALL_TOKEN, "RINGING")

        source.answer()
        val ringing = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Ringing, ringing.phase)
        assertFalse(ringing.busy)
        assertEquals(CallFailureMessages.INVALID, ringing.operationMessage)
        assertCallPrivacy(ringing, CALL_TOKEN, "invalid call request", "sip:bob@example.com")
        source.reject()
        assertEquals("reject", transport.requests.last().method)
        source.close()
    }

    @Test
    fun answerTransportExceptionUsesNetworkMessageAndKeepsRinging() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport(throwOnRequest = true)
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        transport.emitIncoming(CALL_TOKEN, "RINGING")

        source.answer()
        val ringing = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Ringing, ringing.phase)
        assertEquals(CallFailureMessages.NETWORK, ringing.operationMessage)
        assertCallPrivacy(ringing, CALL_TOKEN, "socket reset", "sip:bob@example.com")
        source.close()
    }

    @Test
    fun hangupProtocolErrorKeepsInCallAndRejectErrorKeepsRinging() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport(
            responses = mapOf(
                "hangup" to protocolError(2001, "busy sip:bob@example.com"),
                "reject" to protocolError(3002, "IMS 未注册"),
            ),
        )
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, audio)
        transport.emitIncoming(CALL_TOKEN, "RINGING")
        source.reject()
        val ringing = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Ringing, ringing.phase)
        assertEquals(CallFailureMessages.IMS, ringing.operationMessage)
        assertCallPrivacy(ringing, CALL_TOKEN)

        source.answer()
        transport.emitIncoming(CALL_TOKEN, "IN_CALL")
        source.hangup()
        val inCall = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.InCall, inCall.phase)
        assertEquals(CallFailureMessages.BUSY, inCall.operationMessage)
        assertEquals(CallAudioState.Active, inCall.audio)
        assertCallPrivacy(inCall, CALL_TOKEN, "busy sip:bob@example.com")
        source.close()
    }

    @Test
    fun successfulActionAndEndedEventClearOperationMessage() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        transport.enqueue(protocolError(1001, "invalid call request sip:bob@example.com"))
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        transport.emitIncoming(CALL_TOKEN, "RINGING")
        source.answer()
        assertEquals(
            CallFailureMessages.INVALID,
            assertIs<CallUiState.Ready>(source.state.value).operationMessage,
        )

        source.answer()
        val connecting = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Connecting, connecting.phase)
        assertEquals(null, connecting.operationMessage)
        source.close()

        val endedTransport = FakeCallTransport()
        endedTransport.enqueue(protocolError(1001, "invalid call request"))
        val endedSource = sourceOf(listOf(callRunning(CALL_ICCID_A)), endedTransport)
        endedTransport.emitIncoming(CALL_TOKEN, "RINGING")
        endedSource.answer()
        endedTransport.emitIncoming(CALL_TOKEN, "ENDED", "canceled")
        val idle = assertIs<CallUiState.Ready>(endedSource.state.value)
        assertEquals(CallPhase.Idle, idle.phase)
        assertEquals(null, idle.operationMessage)
        endedSource.close()
    }

    @Test
    fun duplicateAnswerWhileBusyIsIgnored() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeCallTransport(requestGate = gate)
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        transport.emitIncoming(CALL_TOKEN, "RINGING")

        source.answer()
        val busy = assertIs<CallUiState.Ready>(source.state.value)
        assertTrue(busy.busy)
        assertEquals(CallPhase.Ringing, busy.phase)
        source.answer()
        assertEquals(listOf("answer"), transport.requests.map { it.method })

        gate.complete(Unit)
        val connecting = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Connecting, connecting.phase)
        assertFalse(connecting.busy)
        source.close()
    }
}
