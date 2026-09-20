package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.CallAudioState
import com.vowifi.sw.manager.model.CallOrigin
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallsScreenTest {
    @Test
    fun phasesUseExactBoundedCopy() {
        assertEquals(
            listOf("空闲", "来电", "正在接通", "正在呼叫", "通话中", "正在取消", "正在挂断"),
            CallPhase.entries.map(::callPhaseTitle),
        )
        assertEquals("音频正在启动", callAudioText(CallAudioState.Idle))
        assertEquals("音频已连接", callAudioText(CallAudioState.Active))
        assertEquals("无法启动通话音频", callAudioText(CallAudioState.Failed("无法启动通话音频")))
    }

    @Test
    fun idleAndDialingUseDistinctBoundedPanels() {
        val idle = callStatePanelSpec(ready(CallPhase.Idle))
        val dialing = callStatePanelSpec(ready(CallPhase.Dialing))

        assertEquals("当前空闲", idle?.title)
        assertEquals("当前没有通话。", idle?.description)
        assertEquals("正在呼叫", dialing?.title)
        assertEquals("请稍候。", dialing?.description)
        assertEquals("正在接通", callStatePanelSpec(ready(CallPhase.Connecting))?.title)
    }

    @Test
    fun onlyRingingAndInCallRenderSessionControls() {
        assertTrue(showsCallSessionControls(CallPhase.Ringing))
        assertTrue(showsCallSessionControls(CallPhase.InCall))
        assertFalse(showsCallSessionControls(CallPhase.Idle))
        assertFalse(showsCallSessionControls(CallPhase.Connecting))
        assertFalse(showsCallSessionControls(CallPhase.Dialing))
    }

    @Test
    fun dialRequiresEngineIdleValidNumberMicrophoneAndFreeSource() {
        val idle = ready(CallPhase.Idle)

        assertTrue(canDialCall(idle, "+12345", microphonePermissionGranted = true))
        assertFalse(canDialCall(idle.copy(origin = CallOrigin.DESKTOP_FIXTURE), "+12345", true))
        assertFalse(canDialCall(idle, "invalid", true))
        assertFalse(canDialCall(idle, "+12345", false))
        assertFalse(canDialCall(idle.copy(busy = true), "+12345", true))
        listOf(CallPhase.Ringing, CallPhase.Connecting, CallPhase.Dialing, CallPhase.InCall).forEach { phase ->
            assertFalse(canDialCall(ready(phase), "+12345", true))
        }
    }

    @Test
    fun answerRequiresRingingPermissionAndIdleActionSlot() {
        val ringing = ready(CallPhase.Ringing)

        assertTrue(canAnswerCall(ringing, microphonePermissionGranted = true))
        assertFalse(canAnswerCall(ringing, microphonePermissionGranted = false))
        assertFalse(canAnswerCall(ringing.copy(busy = true), microphonePermissionGranted = true))
        assertFalse(canAnswerCall(ready(CallPhase.InCall), microphonePermissionGranted = true))
        assertEquals("授予麦克风权限", MICROPHONE_PERMISSION_ACTION)
        assertEquals("接听", callAnswerLabel(busy = false))
        assertEquals("拒接", callRejectLabel(busy = false))
        assertEquals("挂断", callHangupLabel(busy = false))
        assertEquals("呼叫", callDialLabel(busy = false))
        assertEquals("处理中", callAnswerLabel(busy = true))
        assertEquals("处理中", callRejectLabel(busy = true))
        assertEquals("处理中", callHangupLabel(busy = true))
        assertEquals("处理中", callDialLabel(busy = true))
        assertEquals("静音", callMuteLabel(muted = false))
        assertEquals("取消静音", callMuteLabel(muted = true))
        assertEquals("免提", callSpeakerLabel(speakerOn = false))
        assertEquals("听筒", callSpeakerLabel(speakerOn = true))
    }

    @Test
    fun onlyInCallShowsMuteAndSpeaker() {
        assertTrue(showsInCallAudioControls(CallPhase.InCall))
        assertFalse(showsInCallAudioControls(CallPhase.Ringing))
        assertFalse(showsInCallAudioControls(CallPhase.Idle))
        assertFalse(showsInCallAudioControls(CallPhase.Connecting))
        assertFalse(showsInCallAudioControls(CallPhase.Dialing))
    }

    @Test
    fun onlyTransitionIntoRingingAutoSelectsCalls() {
        assertTrue(shouldAutoSelectCalls(CallPhase.Idle, CallPhase.Ringing))
        assertTrue(shouldAutoSelectCalls(null, CallPhase.Ringing))
        assertFalse(shouldAutoSelectCalls(CallPhase.Ringing, CallPhase.Ringing))
        assertFalse(shouldAutoSelectCalls(CallPhase.Ringing, CallPhase.Connecting))
    }

    @Test
    fun nonSessionStatesNeverExposeRawModelErrors() {
        val states = listOf(
            CallUiState.Loading,
            CallUiState.Failure("raw token iccid sip endpoint"),
            CallUiState.PermissionDenied("raw permission payload"),
            CallUiState.Unavailable("raw unavailable payload"),
        )

        val panels = states.mapNotNull(::callStatePanelSpec)
        assertTrue(panels.all { "raw" !in it.description })
        assertEquals("正在读取通话状态", panels[0].title)
        assertEquals("通话读取失败", panels[1].title)
        assertEquals("通话权限不足", panels[2].title)
        assertEquals("通话不可用", panels[3].title)
        assertNull(callStatePanelSpec(ready(CallPhase.Ringing)))
        assertNull(callStatePanelSpec(ready(CallPhase.InCall)))
    }

    private fun ready(phase: CallPhase) = CallUiState.Ready(
        phase = phase,
        audio = CallAudioState.Idle,
        origin = CallOrigin.ENGINE,
    )
}
