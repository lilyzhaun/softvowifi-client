@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallAudioState
import com.vowifi.sw.manager.model.CallFailureMessages
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class EngineCallSourceDialTest {
    @Test
    fun dialSendsExactRecipientWithRingingBudgetAndStartsAudioFromResponseOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = FakeCallTransport()
            val audio = FakeCallAudioController()
            val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, audio)

            source.dial(CALL_NUMBER)

            val request = transport.requests.single()
            assertEquals("dial", request.method)
            assertEquals("tel:$CALL_NUMBER", request.params.getValue("recipient").jsonPrimitive.content)
            assertEquals(setOf("recipient"), request.params.keys)
            assertEquals(130_000L, request.timeoutMillis)
            val inCall = assertIs<CallUiState.Ready>(source.state.value)
            assertEquals(CallPhase.InCall, inCall.phase)
            assertEquals(CallAudioState.Active, inCall.audio)
            assertFalse(inCall.busy)
            assertEquals(1, audio.starts.size)
            assertEquals(CALL_ICCID_A, audio.starts.single().iccid)
            assertEquals(CALL_TOKEN, audio.starts.single().token)
            assertTrue(audio.starts.single().transport === transport)
            assertNoGetStatus(transport)
            assertCallPrivacy(inCall, CALL_TOKEN, CALL_NUMBER)
            source.close()
        }

    @Test
    fun invalidDialNumberSendsNothingAndPublishesInvalid() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)

        source.dial("")
        source.dial("sip:bob@example.com")
        source.dial("tel:+15550101")
        source.dial("++1")

        assertTrue(transport.requests.isEmpty())
        val idle = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Idle, idle.phase)
        assertEquals(CallFailureMessages.INVALID, idle.operationMessage)
        assertFalse(idle.busy)
        assertCallPrivacy(idle, "sip:bob@example.com", "tel:+15550101", CALL_NUMBER)
        source.close()
    }

    @Test
    fun dialingEventBeforeResponseAdoptsTokenWithoutStartingAudio() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeCallTransport(requestGate = gate)
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, audio)
        source.dial(CALL_NUMBER)

        transport.emitCallState(CALL_TOKEN, "DIALING")

        val dialing = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Dialing, dialing.phase)
        assertTrue(dialing.busy)
        assertEquals(0, audio.starts.size)
        assertCallPrivacy(dialing, CALL_TOKEN, CALL_NUMBER)
        source.close()
        gate.complete(Unit)
    }

    @Test
    fun inCallEventBeforeResponseStartsAudioOnceAndLateResponseDoesNotRestart() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val transport = FakeCallTransport(requestGate = gate)
            val audio = FakeCallAudioController()
            val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, audio)
            source.dial(CALL_NUMBER)

            transport.emitCallState(CALL_TOKEN, "IN_CALL")
            assertEquals(CallPhase.InCall, assertIs<CallUiState.Ready>(source.state.value).phase)
            assertEquals(1, audio.starts.size)

            gate.complete(Unit)
            val ready = assertIs<CallUiState.Ready>(source.state.value)
            assertEquals(CallPhase.InCall, ready.phase)
            assertFalse(ready.busy)
            assertEquals(1, audio.starts.size)
            source.close()
        }

    @Test
    fun callEndedBeforeResponseStaysIdleAndLateResponseDoesNotResurrect() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeCallTransport(requestGate = gate)
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, audio)
        source.dial(CALL_NUMBER)

        transport.emitCallEnded(CALL_TOKEN, "failed")
        val idle = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Idle, idle.phase)
        assertEquals(0, audio.starts.size)

        gate.complete(Unit)
        val late = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Idle, late.phase)
        assertEquals(0, audio.starts.size)
        assertEquals(null, late.operationMessage)
        source.close()
    }

    @Test
    fun dialingThenResponseThenDuplicateInCallStartsAudioOnce() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeCallTransport(requestGate = gate)
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, audio)
        source.dial(CALL_NUMBER)
        transport.emitCallState(CALL_TOKEN, "DIALING")
        assertEquals(0, audio.starts.size)

        gate.complete(Unit)
        transport.emitCallState(CALL_TOKEN, "IN_CALL")

        assertEquals(CallPhase.InCall, assertIs<CallUiState.Ready>(source.state.value).phase)
        assertEquals(1, audio.starts.size)
        source.close()
    }

    @Test
    fun dialFailuresAreBoundedAndDoNotExposeRecipient() = runTest(UnconfinedTestDispatcher()) {
        val invalidTransport = FakeCallTransport(
            responses = mapOf("dial" to protocolError(1001, "invalid call request sip:+15550101")),
        )
        val invalidSource = sourceOf(listOf(callRunning(CALL_ICCID_A)), invalidTransport)
        invalidSource.dial(CALL_NUMBER)
        val invalid = assertIs<CallUiState.Ready>(invalidSource.state.value)
        assertEquals(CallPhase.Idle, invalid.phase)
        assertEquals(CallFailureMessages.INVALID, invalid.operationMessage)
        assertCallPrivacy(invalid, CALL_NUMBER, "sip:+15550101")
        invalidSource.close()

        val networkTransport = FakeCallTransport(throwOnRequest = true)
        val networkSource = sourceOf(listOf(callRunning(CALL_ICCID_A)), networkTransport)
        networkSource.dial(CALL_NUMBER)
        val network = assertIs<CallUiState.Ready>(networkSource.state.value)
        assertEquals(CallPhase.Idle, network.phase)
        assertEquals(CallFailureMessages.NETWORK, network.operationMessage)
        assertCallPrivacy(network, CALL_NUMBER, "socket reset")
        networkSource.close()

        val malformedTransport = FakeCallTransport(
            responses = mapOf(
                "dial" to EngineProtocolFrame.Response(
                    id = 20,
                    result = buildJsonObject { put("state", "DIALING") },
                    error = null,
                ),
            ),
        )
        val malformedSource = sourceOf(listOf(callRunning(CALL_ICCID_A)), malformedTransport)
        malformedSource.dial(CALL_NUMBER)
        val malformed = assertIs<CallUiState.Ready>(malformedSource.state.value)
        assertEquals(CallPhase.Idle, malformed.phase)
        assertEquals(CallFailureMessages.INVALID, malformed.operationMessage)
        assertCallPrivacy(malformed, CALL_NUMBER)
        malformedSource.close()
    }

    @Test
    fun dialingHangupSendsNoRequestMatchingU1ProceedingCancelGap() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeCallTransport(requestGate = gate, gatedMethods = setOf("dial"))
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        source.dial(CALL_NUMBER)
        transport.emitCallState(CALL_TOKEN, "DIALING")

        source.hangup()

        assertTrue(transport.requests.none { request -> request.method == "hangup" })
        val dialing = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Dialing, dialing.phase)
        assertCallPrivacy(dialing, CALL_TOKEN, CALL_NUMBER)
        source.close()
        gate.complete(Unit)
    }

    @Test
    fun dialIsIgnoredWithoutSingleEngineOrOutsideIdle() = runTest(UnconfinedTestDispatcher()) {
        val stopped = FakeCallTransport()
        val stoppedSource = sourceOf(listOf(callStopped(CALL_ICCID_A)), stopped)
        stoppedSource.dial(CALL_NUMBER)
        assertTrue(stopped.requests.isEmpty())
        assertIs<CallUiState.Unavailable>(stoppedSource.state.value)
        stoppedSource.close()

        val multi = FakeCallTransport()
        val multiSource = sourceOf(listOf(callRunning(CALL_ICCID_A), callRunning(CALL_ICCID_B)), multi)
        multiSource.dial(CALL_NUMBER)
        assertTrue(multi.requests.isEmpty())
        multiSource.close()

        val ringing = FakeCallTransport()
        val ringingSource = sourceOf(listOf(callRunning(CALL_ICCID_A)), ringing)
        ringing.emitIncoming(CALL_TOKEN, "RINGING")
        ringingSource.dial(CALL_NUMBER)
        assertTrue(ringing.requests.isEmpty())
        assertEquals(CallPhase.Ringing, assertIs<CallUiState.Ready>(ringingSource.state.value).phase)
        ringingSource.close()

        val gate = CompletableDeferred<Unit>()
        val busy = FakeCallTransport(requestGate = gate)
        val busySource = sourceOf(listOf(callRunning(CALL_ICCID_A)), busy)
        busySource.dial(CALL_NUMBER)
        busySource.dial(CALL_NUMBER)
        assertEquals(1, busy.requests.size)
        gate.complete(Unit)
        busySource.close()
    }
}
