package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.model.CallAudioControls
import com.vowifi.sw.manager.model.CallAudioState
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallOrigin
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FixtureCallSourceTest {
    @Test
    fun exposesEveryBoundedFixtureModeWithFixtureOrigin() {
        listOf("idle", "ringing", "dialing", "connecting", "incall", "audio-failed").forEach { mode ->
            val ready = assertIs<CallUiState.Ready>(FixtureCallSource(mode).state.value)
            assertEquals(CallOrigin.DESKTOP_FIXTURE, ready.origin)
        }
        assertIs<CallUiState.Loading>(FixtureCallSource("loading").state.value)
        assertIs<CallUiState.PermissionDenied>(FixtureCallSource("permission").state.value)
        assertIs<CallUiState.Unavailable>(FixtureCallSource("unavailable").state.value)
        assertIs<CallUiState.Failure>(FixtureCallSource("failure").state.value)
        assertFailsWith<IllegalArgumentException> { FixtureCallSource("unknown") }
    }

    @Test
    fun realActionsMoveFixtureThroughSafeStates() {
        val ringing = FixtureCallSource("ringing")
        ringing.answer()
        assertEquals(CallPhase.Connecting, assertIs<CallUiState.Ready>(ringing.state.value).phase)

        val rejected = FixtureCallSource("ringing")
        rejected.reject()
        assertEquals(CallPhase.Idle, assertIs<CallUiState.Ready>(rejected.state.value).phase)

        val inCall = FixtureCallSource("incall")
        val ready = assertIs<CallUiState.Ready>(inCall.state.value)
        assertEquals(CallAudioState.Active, ready.audio)
        inCall.hangup()
        assertEquals(CallPhase.Idle, assertIs<CallUiState.Ready>(inCall.state.value).phase)

        val dialing = FixtureCallSource("idle")
        dialing.dial("+12345")
        val dialingState = assertIs<CallUiState.Ready>(dialing.state.value)
        assertEquals(CallPhase.Dialing, dialingState.phase)
        assertTrue(dialingState.busy)
        dialing.hangup()
        assertEquals(CallPhase.Dialing, assertIs<CallUiState.Ready>(dialing.state.value).phase)
    }

    @Test
    fun muteAndSpeakerOnlyToggleDuringInCallAndResetOnHangup() {
        val idle = FixtureCallSource("idle")
        idle.mute()
        idle.speaker()
        assertEquals(CallAudioControls(), idle.audioControls.value)

        val inCall = FixtureCallSource("incall")
        assertEquals(CallAudioControls(), inCall.audioControls.value)
        inCall.mute()
        assertEquals(CallAudioControls(muted = true), inCall.audioControls.value)
        inCall.speaker()
        assertEquals(CallAudioControls(muted = true, speakerOn = true), inCall.audioControls.value)
        inCall.hangup()
        assertEquals(CallAudioControls(), inCall.audioControls.value)
        assertEquals(CallPhase.Idle, assertIs<CallUiState.Ready>(inCall.state.value).phase)
    }

    @Test
    fun fixtureHistoryStaysHonestlyEmpty() {
        val fixture = FixtureCallSource("idle")
        assertIs<CallHistoryState.Empty>(fixture.history.value)
        fixture.dial("+12345")
        assertIs<CallHistoryState.Empty>(fixture.history.value)
    }
}
