package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.*

@Composable fun CallLoadingContent(onRefresh: () -> Unit, onBack: () -> Unit, message: String = "正在确认通话") {
    PhoneResultContent("通话状态", message, CallPhoneIcons.Information) {
        PhoneButton("刷新", onRefresh, Modifier.width(240.dp), wide = true, selected = true)
        CommunicationTextAction("返回", onBack)
    }
}

@Composable fun CallEndContent(message: String?, onReturn: () -> Unit) {
    PhoneResultContent("通话已结束", message, CallPhoneIcons.End) {
        PhoneButton("返回通话记录", onReturn, Modifier.width(240.dp), wide = true, selected = true)
    }
}

@Composable fun CallScreenContent(
    state: CallUiState.Ready, line: String, audio: CallAudioControls, preparing: Boolean = false,
    onAction: (CallAction) -> Unit, onMute: () -> Unit, onSpeaker: () -> Unit,
    onDigit: (Char) -> Unit, onRestoreAudio: () -> Unit, onMinimize: () -> Unit,
    onKeypad: (Boolean) -> Unit = {}, ticking: Boolean = true,
    onRefresh: () -> Unit = {},
    onPermissions: (() -> Unit)? = null,
    onReturn: () -> Unit = onMinimize,
) = PhoneStyle {
    val binding = if (state.phase == CallPhase.Idle) state.terminal?.binding else state.binding
    key(binding?.owner, binding?.epoch, binding?.token, binding?.takeIf { it.token == null }?.generation) {
        InCallScreenContent(state, line, audio, preparing, onAction, onMute, onSpeaker, onDigit,
            onRestoreAudio, onMinimize, onKeypad, ticking, onRefresh, onPermissions, onReturn)
    }
}

@Composable internal fun PhoneSessionActions(state: CallUiState.Ready, preparing: Boolean, action: (CallAction) -> Unit) {
    val ready = state.sync == CallSyncState.Ready
    when (state.phase) {
        CallPhase.Ringing -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            PhoneButton("拒接", { action(CallAction.Reject) }, Modifier.weight(1f), icon = CallPhoneIcons.End, destructive = true,
                enabled = ready, busy = state.pendingAction == CallAction.Reject)
            PhoneButton("接听", { action(CallAction.Answer) }, Modifier.weight(1f), icon = CallPhoneIcons.Phone, selected = true, prominent = true,
                enabled = ready && state.pendingAction != CallAction.Reject, busy = preparing || state.pendingAction == CallAction.Answer)
        }
        CallPhase.InCall -> Unit // Dedicated persistent InCall scene above owns these actions.
        CallPhase.Dialing, CallPhase.Canceling -> PhoneButton(if (state.cancelPending) "正在取消" else "取消",
            { action(CallAction.Cancel) }, icon = CallPhoneIcons.End, destructive = true, enabled = ready && !state.cancelPending,
            busy = state.cancelPending)
        CallPhase.Ending -> PhoneButton("正在挂断", {}, icon = CallPhoneIcons.End, destructive = true, busy = true)
        CallPhase.Connecting -> PhoneButton("挂断", { action(CallAction.Hangup) }, icon = CallPhoneIcons.End,
            destructive = true, enabled = ready, busy = state.pendingAction == CallAction.Hangup)
        CallPhase.Idle -> PhoneText("通话已结束", size = 24)
    }
}
