@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

class CallRingingBudgetTest {
    @Test fun dialBudgetCoversRingingAndCancellationDrainWithoutChangingControlBudgets() {
        assertEquals(130_000L, CallProtocolCodec.DIAL_TIMEOUT_MILLIS)
        assertEquals(5_000L, CallProtocolCodec.CONTROL_TIMEOUT_MILLIS)
        assertEquals(13_000L, CallProtocolCodec.ANSWER_TIMEOUT_MILLIS)
    }

    @Test fun delayedAnswerBeyondOldAppDeadlineStillStartsAudioOnlyOnce() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val wire = FakeCallTransport(requestGate = gate, gatedMethods = setOf("dial"))
        val transport = object : EngineMessageTransport by wire {
            override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): EngineProtocolFrame.Response =
                withEngineRequestTimeout(timeoutMillis) { wire.request(method, params, timeoutMillis) }
        }
        val audio = FakeCallAudioController()
        val source = EngineCallSource(
            runtime = EngineMessageRuntime(listInstances = { listOf(callRunning(CALL_ICCID_A)) }, transportFor = { transport }, clock = { 0L }),
            audio = audio, parentContext = coroutineContext,
            control = CallControlTransport { _, method, params, timeout -> wire.request(method, params, timeout) },
        )
        try {
            source.dial(CALL_NUMBER)
            wire.emitCallState(CALL_TOKEN, "DIALING")
            advanceTimeBy(45_000)
            runCurrent()
            val ringing = assertIs<CallUiState.Ready>(source.state.value)
            assertEquals(CallPhase.Dialing, ringing.phase)
            assertTrue(ringing.dialBusy)
            assertEquals(CallSyncState.Ready, ringing.sync)
            assertEquals(0, audio.starts.size)
            wire.emitCallState(CALL_TOKEN, "IN_CALL")
            gate.complete(Unit)
            runCurrent()
            assertEquals(CallPhase.InCall, assertIs<CallUiState.Ready>(source.state.value).phase)
            assertEquals(1, audio.starts.size)
            assertEquals(1, wire.requests.count { it.method == "dial" })
        } finally { source.close(); gate.complete(Unit) }
    }
}
