@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class EngineCallInvalidControlTest {
    @Test fun staleDialerOwnerCannotSubmitThroughNewOwner() = runTest(UnconfinedTestDispatcher()) {
        var instances = listOf(callRunning(CALL_ICCID_A))
        val primary = FakeCallTransport()
        val controls = CallControlTransport { _, _, _, _ -> snapshot("IDLE") }
        val source = sourceOf(instances, primary, control = controls, listInstances = { instances })
        try {
            val old = source.ready().binding!!
            instances = listOf(callRunning(CALL_ICCID_B))
            source.refresh()
            source.dial(old, "+12025550123")
            assertTrue(primary.requests.isEmpty())
            assertEquals(CallPhase.Idle, source.ready().phase)
            assertNotSame(old, source.ready().binding)
        } finally { source.close() }
    }

    @Test fun expiredDtmfStopsCommandsWhileSnapshotIsPendingAndCannotCrossNewToken() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        var queries = 0
        var digits = 0
        val control = CallControlTransport { _, method, _, _ ->
            if (method == "getCallSnapshot") {
                if (++queries == 1) snapshot("IN_CALL", dtmf = true)
                else { gate.await(); snapshot("IN_CALL", token = CALL_OTHER, revision = 3, dtmf = true) }
            } else { digits++; protocolError(1001, "expired") }
        }
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), FakeCallTransport(), control = control)
        try {
            val old = source.ready().binding!!
            source.sendDtmf(old, '1')
            source.sendDtmf(old, '2')
            assertEquals(CallSyncState.Recovering, source.ready().sync)
            assertEquals(1, digits)
            gate.complete(Unit)
            source.sendDtmf(old, '3')
            assertEquals(1, digits)
            assertFalse(source.ready().dtmfBusy)
        } finally { gate.complete(Unit); source.close() }
    }

    @Test fun confirmationWinsCancelAndRequiresExplicitHangup() = runTest(UnconfinedTestDispatcher()) {
        var current = snapshot("DIALING")
        val methods = mutableListOf<String>()
        val control = CallControlTransport { _, method, _, _ ->
            methods += method
            when (method) {
                "getCallSnapshot" -> current
                "cancelCall" -> { current = snapshot("IN_CALL", revision = 2); protocolError(1001, "already confirmed") }
                "hangup" -> snapshot("IDLE").copy(result = kotlinx.serialization.json.buildJsonObject { put("state", kotlinx.serialization.json.JsonPrimitive("IDLE")) })
                else -> error("unexpected method")
            }
        }
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), FakeCallTransport(), control = control)
        try {
            source.cancel()
            assertEquals(CallPhase.InCall, source.ready().phase)
            assertEquals(0, methods.count { it == "hangup" })
            source.hangup()
            assertEquals(CallPhase.Idle, source.ready().phase)
            assertEquals(1, methods.count { it == "hangup" })
        } finally { source.close() }
    }
}
