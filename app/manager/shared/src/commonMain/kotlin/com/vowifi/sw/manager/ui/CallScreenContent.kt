package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.*
import androidx.compose.foundation.shape.CircleShape

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
) = PhoneStyle {
    if (state.phase == CallPhase.InCall) {
        val binding = state.binding
        key(binding?.owner, binding?.epoch, binding?.token, binding?.takeIf { it.token == null }?.generation) {
            InCallScreenContent(state, line, audio, preparing, onAction, onMute, onSpeaker, onDigit,
                onRestoreAudio, onMinimize, onKeypad, ticking, onRefresh, onPermissions)
        }
        return@PhoneStyle
    }
    val palette = phoneColors()
    LaunchedEffect(Unit) { onKeypad(false) }
    val synchronized = state.sync == CallSyncState.Ready
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.canvas).safeDrawingPadding()) {
        val landscape = maxWidth > 600.dp && maxHeight < 550.dp
        val horizontalInset = if (maxWidth < 360.dp) 16.dp else 24.dp
        val minimumHeight = (maxHeight - 56.dp).coerceAtLeast(0.dp)
        Column(Modifier.fillMaxSize()) {
        CommunicationNavigation("", leading = { PhoneNavigation("返回应用", onMinimize, CallPhoneIcons.Minimize) },
            systemInset = false, background = false)
        Column(Modifier.weight(1f).fillMaxWidth().phoneOverscroll().verticalScroll(rememberScrollState(), overscrollEffect = null).heightIn(min = minimumHeight)
            .padding(horizontal = horizontalInset, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            val identity: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PhoneText(line, secondary = true, size = 16)
                        Box(Modifier.padding(vertical = 8.dp).size(88.dp).background(palette.tonal, CircleShape), contentAlignment = Alignment.Center) {
                            top.yukonga.miuix.kmp.basic.Icon(CallPhoneIcons.Phone, null, Modifier.size(40.dp), tint = palette.accent)
                        }
                    PhoneText(state.peer?.text()?.let { displayCallNumber(privateUiPhoneDisplay(it)) } ?: "未知号码", size = 28)
                    PhoneText(callSessionStatusTitle(state), size = 16, secondary = synchronized)
                }
            }
            val controls: @Composable () -> Unit = {
                Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    PhoneSessionActions(state, preparing, onAction)
                    if (state.sync != CallSyncState.Ready) PhoneButton("重新同步", onRefresh, wide = true, selected = true)
                    listOfNotNull(state.operationMessage, (state.audio as? CallAudioState.Failed)?.message, audio.message)
                        .distinct().takeIf { it.isNotEmpty() }?.let { PhoneText(it.joinToString("\n"), secondary = true) }
                    onPermissions?.let { PhoneNavigation("应用权限", it) }
                }
                }
            if (landscape) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { identity() }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { controls() }
            } else {
                identity()
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    controls()
                    PhoneText("通过 SoftVoWiFi", secondary = true, size = 14)
                }
            }
        }
        }
    }
}

@Composable private fun PhoneSessionActions(state: CallUiState.Ready, preparing: Boolean, action: (CallAction) -> Unit) {
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
