@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class EngineCallControlFlowTest {
    @Test
    fun rejectCanSupersedePendingAnswerAndLateAnswerCannotResurrectIt() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeCallTransport(requestGate = gate, gatedMethods = setOf("answer"))
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        try {
            transport.emitIncoming(CALL_TOKEN, "RINGING")
            source.answer()
            source.reject()
            assertEquals(CallPhase.Idle, source.ready().phase)
            gate.complete(Unit)
            assertEquals(CallPhase.Idle, source.ready().phase)
            assertEquals(listOf("answer", "reject"), transport.requests.map { it.method })
        } finally { source.close(); gate.complete(Unit) }
    }

    @Test
    fun unrelatedLegacyIncomingCannotChangeOutgoingOwnerOrPreventCancel() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val primary = FakeCallTransport(requestGate = gate, gatedMethods = setOf("dial"))
        var cancellations = 0
        val control = CallControlTransport { _, method, _, _ ->
            if (method == "getCallSnapshot") snapshot("IDLE") else { cancellations++; ok("accepted") }
        }
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), primary, control = control)
        try {
            source.dial("+12025550123")
            primary.emitIncoming(CALL_OTHER, "RINGING")
            assertEquals(CallDirection.OUTGOING, source.ready().direction)
            assertEquals(CallPhase.Dialing, source.ready().phase)
            source.cancel()
            primary.emitCallState(CALL_TOKEN, "DIALING")
            assertEquals(CallPhase.Canceling, source.ready().phase)
            assertEquals(1, cancellations)
        } finally { source.close(); gate.complete(Unit) }
    }

    @Test
    fun cancelBeforeTokenUsesControlLaneAndAcceptedDoesNotEndDial() = runTest(UnconfinedTestDispatcher()) {
        val dialGate = CompletableDeferred<Unit>()
        val primary = FakeCallTransport(
            responses = mapOf("dial" to protocolError(2005, "canceled")), requestGate = dialGate,
            gatedMethods = setOf("dial"),
        )
        var snapshot = snapshot("IDLE")
        val methods = mutableListOf<String>()
        val controls = CallControlTransport { _, method, params, _ ->
            methods += method
            if (method == "getCallSnapshot") snapshot else {
                assertEquals("cancelCall", method)
                assertEquals(CallProtocolCodec.params(CALL_TOKEN), params)
                ok("accepted")
            }
        }
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), primary, control = controls)
        try {
            source.dial("+12025550123")
            val initial = source.ready().binding!!
            source.cancel()
            source.act(initial, CallAction.Cancel)
            assertEquals(CallPhase.Canceling, source.ready().phase)
            assertEquals(listOf("getCallSnapshot"), methods)
            primary.emitCallState(CALL_TOKEN, "DIALING")
            assertEquals(1, methods.count { it == "cancelCall" })
            assertEquals(CallPhase.Canceling, source.ready().phase)
            assertEquals(listOf("dial"), primary.requests.map { it.method })
            snapshot = snapshot("CANCELING", revision = 2)
            dialGate.complete(Unit)
            assertEquals(CallPhase.Canceling, source.ready().phase)
            assertEquals(1, methods.count { it == "cancelCall" })
            primary.emitVersioned("callEnded", CALL_TOKEN, null, "epoch-a", 3, "canceled")
            assertEquals(CallPhase.Idle, source.ready().phase)
            assertNull(source.ready().clock)
        } finally { dialGate.complete(Unit); source.close() }
    }

    @Test
    fun dtmfHasOwnBusyGateAndNeverBlocksHangupOrRetries() = runTest(UnconfinedTestDispatcher()) {
        val primary = FakeCallTransport()
        val dtmfGate = CompletableDeferred<Unit>()
        val methods = mutableListOf<String>()
        val controls = CallControlTransport { _, method, params, _ ->
            methods += method
            when (method) {
                "getCallSnapshot" -> snapshot("IN_CALL", elapsed = 8000, dtmf = true)
                "sendDtmf" -> {
                    assertEquals(setOf("callId", "digit"), params.keys)
                    dtmfGate.await()
                    ok("sent")
                }
                "hangup" -> EngineProtocolFrame.Response(1, buildJsonObject { put("state", "IDLE") }, null)
                else -> error("unexpected method")
            }
        }
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), primary, control = controls)
        try {
            val binding = source.ready().binding!!
            source.sendDtmf(binding, '5')
            source.sendDtmf(binding, '6')
            assertTrue(source.ready().dtmfBusy)
            assertFalse(source.ready().busy)
            source.act(binding, CallAction.Hangup)
            assertEquals(CallPhase.Idle, source.ready().phase)
            dtmfGate.complete(Unit)
            assertEquals(1, methods.count { it == "sendDtmf" })
            assertEquals(1, methods.count { it == "hangup" })
            assertFalse(source.ready().dtmfBusy)
            assertNull(source.ready().operationMessage)
        } finally { dtmfGate.complete(Unit); source.close() }
    }

    @Test
    fun unsupportedDigitFeedbackHidesCapabilityWithoutEndingVoice() = runTest(UnconfinedTestDispatcher()) {
        var sends = 0
        val controls = CallControlTransport { _, method, _, _ ->
            if (method == "getCallSnapshot") snapshot("IN_CALL", dtmf = true)
            else { sends++; protocolError(2004, "untrusted digit details") }
        }
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), FakeCallTransport(), control = controls)
        try {
            val bound = source.ready().binding!!
            source.sendDtmf(bound, '1')
            source.sendDtmf(bound, '2')
            assertEquals(1, sends)
            assertFalse(source.ready().dtmfSupported)
            assertFalse(source.ready().dtmfBusy)
            assertEquals(CallPhase.InCall, source.ready().phase)
            assertEquals(CallFailureMessages.DTMF_UNSUPPORTED, source.ready().operationMessage)
        } finally { source.close() }
    }

    @Test
    fun changedOwnerPreservesOriginalCallButRejectsStaleActions() = runTest(UnconfinedTestDispatcher()) {
        var instances = listOf(callRunning(CALL_ICCID_A))
        val owners = mutableListOf<String>()
        val controls = CallControlTransport { owner, _, _, _ -> owners += owner; snapshot("IN_CALL") }
        val source = sourceOf(instances, FakeCallTransport(), control = controls, listInstances = { instances })
        try {
            val binding = source.ready().binding!!
            instances = listOf(callRunning(CALL_ICCID_B))
            source.refresh()
            source.act(binding, CallAction.Hangup)
            assertEquals(CallPhase.InCall, source.ready().phase)
            assertEquals(CallSyncState.OwnerChanged, source.ready().sync)
            assertEquals(listOf(CALL_ICCID_A), owners)
            instances = listOf(callRunning(CALL_ICCID_A))
            source.refresh()
            assertEquals(CallSyncState.Ready, source.ready().sync)
            source.act(binding, CallAction.Hangup)
            assertEquals(2, owners.size)
        } finally { source.close() }
    }
}

internal fun EngineCallSource.ready(): CallUiState.Ready = assertIs(state.value)

internal fun snapshot(
    stage: String, epoch: String = "epoch-a", revision: Long = 0,
    token: String = CALL_TOKEN, elapsed: Long = 0, dtmf: Boolean = false,
    direction: String = "OUTGOING",
) = EngineProtocolFrame.Response(1, buildJsonObject {
    put("epoch", epoch); put("revision", revision); put("stage", stage)
    put("confirmedElapsedMs", elapsed); put("dtmfSupported", dtmf)
    if (stage != "IDLE") {
        put("callId", token); put("direction", direction); put("peer", "+12025550123")
    }
}, null)

internal fun ok(field: String) = EngineProtocolFrame.Response(1, buildJsonObject {
    put(field, true); put("callId", CALL_TOKEN)
}, null)
