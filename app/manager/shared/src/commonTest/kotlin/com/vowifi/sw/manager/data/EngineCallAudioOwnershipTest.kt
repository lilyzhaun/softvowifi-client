@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallAudioState
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class EngineCallAudioOwnershipTest {
    @Test
    fun delayedAudioEndedFromADoesNotClearB() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, audio)
        transport.enqueue(dialOk(CALL_TOKEN))
        transport.enqueue(dialOk(CALL_OTHER))
        source.dial(CALL_NUMBER)
        assertEquals(CallPhase.InCall, ready(source).phase)
        assertEquals(CALL_TOKEN, audio.activeToken)

        transport.emitCallEnded(CALL_TOKEN, "remote")
        assertEquals(CallPhase.Idle, ready(source).phase)

        source.dial(CALL_NUMBER)
        val liveB = ready(source)
        assertEquals(CallPhase.InCall, liveB.phase)
        assertEquals(CallAudioState.Active, liveB.audio)
        assertEquals(CALL_OTHER, audio.activeToken)
        val stopsBefore = audio.stopCalls

        audio.emitEnded(CALL_TOKEN)
        val afterDelayed = ready(source)
        assertEquals(CallPhase.InCall, afterDelayed.phase)
        assertEquals(CallAudioState.Active, afterDelayed.audio)
        assertEquals(CALL_OTHER, audio.activeToken)
        assertEquals(stopsBefore, audio.stopCalls)
        assertEquals(CALL_TOKEN, audio.lastEndedToken)
        assertCallPrivacy(afterDelayed, CALL_TOKEN, CALL_OTHER, CALL_NUMBER)
        source.close()
    }

    private fun ready(source: EngineCallSource): CallUiState.Ready = assertIs(source.state.value)
}
