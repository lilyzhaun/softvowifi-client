@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class CallCurrentActionIdentityTest {
    @Test fun implicitCancelResolvesCurrentBindingAfterQueuedTokenPromotion() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeCallTransport(requestGate = gate, gatedMethods = setOf("dial"))
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        try {
            runCurrent()
            source.dial(CALL_NUMBER)
            runCurrent()
            val before = (source.state.value as CallUiState.Ready).binding!!
            assertNull(before.token)
            transport.emitCallState(CALL_TOKEN, "DIALING")
            source.cancel()
            runCurrent()
            val after = source.state.value as CallUiState.Ready
            assertNotSame(before, after.binding)
            assertEquals(CallSyncState.Ready, after.sync)
            assertTrue(transport.requests.any { it.method == "cancelCall" })
        } finally { source.close(); gate.complete(Unit) }
    }

    @Test fun explicitOldBindingStillCannotCancelAfterTokenPromotion() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeCallTransport(requestGate = gate, gatedMethods = setOf("dial"))
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        try {
            runCurrent(); source.dial(CALL_NUMBER); runCurrent()
            val before = (source.state.value as CallUiState.Ready).binding!!
            transport.emitCallState(CALL_TOKEN, "DIALING")
            source.act(before, CallAction.Cancel)
            runCurrent()
            assertNotSame(before, (source.state.value as CallUiState.Ready).binding)
            assertEquals(listOf("dial"), transport.requests.map { it.method })
        } finally { source.close(); gate.complete(Unit) }
    }
}
