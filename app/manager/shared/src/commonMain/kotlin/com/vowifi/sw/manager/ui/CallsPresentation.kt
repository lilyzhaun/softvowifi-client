package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.CallProtocolCodec
import com.vowifi.sw.manager.model.CallAudioState
import com.vowifi.sw.manager.model.CallOrigin
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.CallSyncState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.Refresh

internal fun callStatePanelSpec(state: CallUiState): StatePanelSpec? = when (state) {
    CallUiState.Loading -> StatePanelSpec(
        MiuixIcons.Refresh,
        StatePanelTone.INTERACTIVE,
        "正在读取通话状态",
        "完成后将显示当前通话状态。",
        loading = true,
    )
    is CallUiState.Ready -> when (state.phase) {
        CallPhase.Idle -> StatePanelSpec(MiuixIcons.Info, StatePanelTone.NEUTRAL, "当前空闲", "当前没有通话。")
        CallPhase.Connecting -> StatePanelSpec(MiuixIcons.Phone, StatePanelTone.INTERACTIVE, "正在接通", "请稍候。")
        CallPhase.Dialing -> StatePanelSpec(MiuixIcons.Phone, StatePanelTone.INTERACTIVE, "正在呼叫", "请稍候。")
        CallPhase.Canceling -> StatePanelSpec(MiuixIcons.Phone, StatePanelTone.INTERACTIVE, "正在取消", "等待通话结束确认。")
        CallPhase.Ending -> StatePanelSpec(MiuixIcons.Phone, StatePanelTone.INTERACTIVE, "正在挂断", "等待通话结束确认。")
        CallPhase.Ringing,
        CallPhase.InCall,
        -> null
    }
    is CallUiState.Failure -> StatePanelSpec(MiuixIcons.Close, StatePanelTone.ERROR, "通话读取失败", "无法读取通话状态，请稍后刷新。")
    is CallUiState.PermissionDenied -> StatePanelSpec(MiuixIcons.Close, StatePanelTone.ERROR, "通话权限不足", "请在 KernelSU Manager 授权后刷新。")
    is CallUiState.Unavailable -> StatePanelSpec(MiuixIcons.Info, StatePanelTone.NEUTRAL, "通话不可用", "引擎通话会话不可用")
}

internal fun callPhaseTitle(phase: CallPhase): String = when (phase) {
    CallPhase.Idle -> "空闲"
    CallPhase.Ringing -> "来电"
    CallPhase.Connecting -> "正在接通"
    CallPhase.Dialing -> "正在呼叫"
    CallPhase.InCall -> "通话中"
    CallPhase.Canceling -> "正在取消"
    CallPhase.Ending -> "正在挂断"
}

internal fun callSessionStatusTitle(state: CallUiState.Ready): String = when (state.sync) {
    CallSyncState.Ready -> callPhaseTitle(state.phase)
    CallSyncState.Recovering -> "正在恢复通话状态"
    CallSyncState.Unavailable -> "通话状态待确认"
    CallSyncState.Unregistered -> "线路未注册"
    CallSyncState.OwnerChanged -> "通话线路已变化"
}

internal fun canRefreshCallState(state: CallUiState): Boolean = when (state) {
    CallUiState.Loading -> false
    is CallUiState.Ready -> !state.busy && state.sync != CallSyncState.Recovering &&
        (state.sync != CallSyncState.Ready || state.operationMessage != null)
    else -> true
}

internal fun callAudioText(audio: CallAudioState): String = when (audio) {
    CallAudioState.Idle -> "音频正在启动"
    CallAudioState.Active -> "音频已连接"
    is CallAudioState.Failed -> audio.message
}

internal fun canAnswerCall(state: CallUiState.Ready, microphonePermissionGranted: Boolean): Boolean =
    state.phase == CallPhase.Ringing && microphonePermissionGranted && !state.busy && state.sync == com.vowifi.sw.manager.model.CallSyncState.Ready

internal fun showsCallSessionControls(phase: CallPhase): Boolean =
    phase == CallPhase.Ringing || phase == CallPhase.InCall

internal fun showsInCallAudioControls(phase: CallPhase): Boolean =
    phase == CallPhase.InCall

internal fun canDialCall(
    state: CallUiState.Ready,
    number: String,
    microphonePermissionGranted: Boolean,
): Boolean = state.origin == CallOrigin.ENGINE &&
    state.phase == CallPhase.Idle &&
    state.sync == com.vowifi.sw.manager.model.CallSyncState.Ready &&
    !state.busy &&
    microphonePermissionGranted &&
    CallProtocolCodec.isValidDialNumber(number)

internal fun callAnswerLabel(busy: Boolean): String = if (busy) "处理中" else "接听"

internal fun callRejectLabel(busy: Boolean): String = if (busy) "处理中" else "拒接"

internal fun callHangupLabel(busy: Boolean): String = if (busy) "处理中" else "挂断"

internal fun callMuteLabel(muted: Boolean): String = if (muted) "取消静音" else "静音"

internal fun callSpeakerLabel(speakerOn: Boolean): String = if (speakerOn) "听筒" else "免提"

internal fun callDialLabel(busy: Boolean): String = if (busy) "处理中" else "呼叫"

internal fun shouldAutoSelectCalls(previous: CallPhase?, current: CallPhase?): Boolean =
    previous != CallPhase.Ringing && current == CallPhase.Ringing
