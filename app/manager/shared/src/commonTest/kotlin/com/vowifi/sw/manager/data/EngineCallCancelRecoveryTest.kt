@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallAction
import com.vowifi.sw.manager.model.CallFailureMessages
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallSyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class EngineCallCancelRecoveryTest {
    @Test
    fun udpUnsupportedCancelResyncsSameVersionDialingWithoutIdleOrAutomaticRetry() = runTest(UnconfinedTestDispatcher()) {
        val snapshotGate = CompletableDeferred<Unit>()
        val methods = mutableListOf<String>()
        var queries = 0
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), FakeCallTransport(),
            control = CallControlTransport { _, method, params, _ ->
                methods += method
                when (method) {
                    "getCallSnapshot" -> {
                        if (++queries > 1) snapshotGate.await()
                        snapshot("DIALING", revision = 7)
                    }
                    "cancelCall" -> {
                        assertEquals(CallProtocolCodec.params(CALL_TOKEN), params)
                        protocolError(2003, "network")
                    }
                    else -> error("unexpected method: $method")
                }
            })
        try {
            val binding = source.ready().binding!!
            assertEquals(CallPhase.Dialing, source.ready().phase)
            source.act(binding, CallAction.Cancel)
            assertEquals(CallPhase.Canceling, source.ready().phase)
            assertEquals(CallSyncState.Recovering, source.ready().sync)
            assertEquals(CallFailureMessages.NETWORK, source.ready().operationMessage)
            source.act(binding, CallAction.Cancel)
            assertEquals(listOf("getCallSnapshot", "cancelCall", "getCallSnapshot"), methods)
            snapshotGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(CallPhase.Dialing, source.ready().phase)
            assertEquals(CallSyncState.Ready, source.ready().sync)
            assertEquals(CallFailureMessages.NETWORK, source.ready().operationMessage)
            assertFalse(source.ready().busy)
            assertSame(binding, source.ready().binding)
            source.refresh()
            advanceUntilIdle()
            assertEquals(CallPhase.Dialing, source.ready().phase)
            assertEquals(listOf("getCallSnapshot", "cancelCall", "getCallSnapshot", "getCallSnapshot"), methods)
        } finally { source.close(); snapshotGate.complete(Unit) }
    }

    @Test
    fun authoritativeInCallSnapshotWinsLateCancelNetworkErrorWithoutSecondCancel() = runTest(UnconfinedTestDispatcher()) {
        val cancelGate = CompletableDeferred<Unit>()
        val methods = mutableListOf<String>()
        var current = snapshot("DIALING", revision = 7)
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), FakeCallTransport(),
            control = CallControlTransport { _, method, params, _ ->
                methods += method
                when (method) {
                    "getCallSnapshot" -> current
                    "cancelCall" -> {
                        assertEquals(CallProtocolCodec.params(CALL_TOKEN), params)
                        cancelGate.await()
                        protocolError(2003, "network")
                    }
                    else -> error("unexpected method: $method")
                }
            })
        try {
            val binding = source.ready().binding!!
            source.act(binding, CallAction.Cancel)
            assertEquals(CallPhase.Canceling, source.ready().phase)
            current = snapshot("IN_CALL", revision = 8, elapsed = 4000, dtmf = true)
            source.refresh()
            assertEquals(CallPhase.InCall, source.ready().phase)
            assertSame(binding, source.ready().binding)
            cancelGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(CallPhase.InCall, source.ready().phase)
            assertEquals(CallSyncState.Ready, source.ready().sync)
            assertEquals(CallFailureMessages.NETWORK, source.ready().operationMessage)
            assertFalse(source.ready().busy)
            assertSame(binding, source.ready().binding)
            source.act(binding, CallAction.Cancel)
            source.refresh()
            advanceUntilIdle()
            assertEquals(CallPhase.InCall, source.ready().phase)
            assertEquals(listOf("getCallSnapshot", "cancelCall", "getCallSnapshot", "getCallSnapshot", "getCallSnapshot"), methods)
        } finally { source.close(); cancelGate.complete(Unit) }
    }
}
