@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallAudioState
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

class EngineCallAudioInterruptionTest {
    @Test
    fun pcmFailureKeepsConfirmedCallAndItsHangupToken() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, audio)
        try {
            source.dial("+12025550123")
            assertEquals(CallPhase.InCall, assertIs<CallUiState.Ready>(source.state.value).phase)
            val stopsBefore = audio.stopCalls

            audio.emitEnded()

            val interrupted = assertIs<CallUiState.Ready>(source.state.value)
            assertEquals(CallPhase.InCall, interrupted.phase)
            assertIs<CallAudioState.Failed>(interrupted.audio)
            assertTrue(audio.stopCalls > stopsBefore)
            assertEquals(1, audio.starts.size)
            source.hangup()
            val hangup = transport.requests.last()
            assertEquals("hangup", hangup.method)
            assertEquals(CALL_TOKEN, hangup.params.getValue("callId").jsonPrimitive.content)
            assertEquals(CallPhase.Idle, assertIs<CallUiState.Ready>(source.state.value).phase)
        } finally {
            source.close()
        }
    }

    @Test
    fun remoteTerminalStillEndsInterruptedCallWithoutRestartingAudio() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, audio)
        try {
            source.dial("+12025550123")
            audio.emitEnded()
            transport.emitCallState(CALL_TOKEN, "IN_CALL")
            assertIs<CallAudioState.Failed>(assertIs<CallUiState.Ready>(source.state.value).audio)
            assertEquals(1, audio.starts.size)
            transport.emitCallEnded(CALL_TOKEN, "remote")
            val ended = assertIs<CallUiState.Ready>(source.state.value)
            assertEquals(CallPhase.Idle, ended.phase)
            assertEquals(CallAudioState.Idle, ended.audio)
        } finally {
            source.close()
        }
    }
}
