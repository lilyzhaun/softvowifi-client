package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.delay

@Composable fun CallLoadingContent(onRefresh: () -> Unit, onBack: () -> Unit, message: String = "正在确认通话") {
    PhoneResultContent("通话状态", message, CallPhoneIcons.Information) {
        PhoneButton("刷新", onRefresh, wide = true, selected = true)
        PhoneButton("返回", onBack, wide = true)
    }
}

@Composable fun CallEndContent(message: String?, onReturn: () -> Unit) {
    PhoneResultContent("通话已结束", message, CallPhoneIcons.End) {
        PhoneButton("返回通话记录", onReturn, wide = true, selected = true)
    }
}

@Composable fun CallScreenContent(
    state: CallUiState.Ready, line: String, audio: CallAudioControls, preparing: Boolean = false,
    onAction: (CallAction) -> Unit, onMute: () -> Unit, onSpeaker: () -> Unit,
    onDigit: (Char) -> Unit, onRestoreAudio: () -> Unit, onMinimize: () -> Unit,
    onKeypad: (Boolean) -> Unit = {}, ticking: Boolean = true,
    onRefresh: () -> Unit = {},
    onPermissions: (() -> Unit)? = null,
) {
    val palette = phoneColors()
    var keypad by remember(state.binding) { mutableStateOf(false) }
    var digits by remember(state.binding) { mutableStateOf("") }
    val keypadAlpha = remember { Animatable(1f) }
    LaunchedEffect(keypad) { if (keypad) { keypadAlpha.snapTo(0f); keypadAlpha.animateTo(1f, tween(160)) } }
    LaunchedEffect(keypad) { onKeypad(keypad) }
    LaunchedEffect(state.dtmfSupported, state.phase) {
        if (!state.dtmfSupported || state.phase != CallPhase.InCall) { keypad = false; digits = "" }
    }
    val clock = state.clock
    val synchronized = state.sync == CallSyncState.Ready
    val duration by produceState(clock?.formatted().orEmpty(), clock, ticking, synchronized) {
        while (clock != null && ticking && synchronized) { value = clock.formatted(); delay(1000) }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.canvas).safeDrawingPadding()) {
        val landscape = maxWidth > 600.dp && maxHeight < 550.dp
        val minimumHeight = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = minimumHeight)
            .padding(horizontal = if (maxWidth < 360.dp) 16.dp else 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            val identity: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!keypad) PhoneEmblem(CallPhoneIcons.Phone)
                    PhoneText(line, secondary = true, size = 14)
                    PhoneText(state.peer?.text()?.let { displayCallNumber(privateUiPhoneDisplay(it)) } ?: "未知号码", size = 28)
                    val status = when (state.sync) {
                        CallSyncState.Ready -> if (state.phase == CallPhase.InCall && duration.isNotEmpty()) "通话中 · $duration" else callPhaseTitle(state.phase)
                        CallSyncState.Recovering -> "正在恢复通话状态"
                        CallSyncState.Unavailable -> "通话状态待确认"
                        CallSyncState.Unregistered -> "线路未注册"
                        CallSyncState.OwnerChanged -> "通话线路已变化"
                    }
                    PhoneStatusRow(status, when {
                        !synchronized -> CallPhoneIcons.Information
                        state.phase == CallPhase.InCall -> CallPhoneIcons.Clock
                        state.phase == CallPhase.Ending || state.phase == CallPhase.Idle -> CallPhoneIcons.End
                        else -> CallPhoneIcons.Phone
                    })
                }
            }
            val controls: @Composable () -> Unit = {
                Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (keypad) {
                        Column(Modifier.graphicsLayer { alpha = keypadAlpha.value }, horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PhoneText(digits.ifEmpty { "通话按键" }, size = 24)
                        CallKeypad(state.phase == CallPhase.InCall && synchronized && state.dtmfSupported && !state.dtmfBusy,
                            { digits = (digits + it).takeLast(20); onDigit(it) })
                        PhoneButton("关闭键盘", { keypad = false; digits = "" }, wide = true, toggle = true, selected = true)
                        }
                    }
                    PhoneSessionActions(state, audio, preparing, keypad, onAction, onMute, onSpeaker,
                        { keypad = !keypad; if (!keypad) digits = "" }, onRestoreAudio)
                    if (state.sync != CallSyncState.Ready) PhoneButton("重新同步", onRefresh, wide = true, selected = true)
                    listOfNotNull(state.operationMessage, (state.audio as? CallAudioState.Failed)?.message, audio.message)
                        .distinct().takeIf { it.isNotEmpty() }?.let { PhoneText(it.joinToString("\n"), secondary = true) }
                    onPermissions?.let { PhoneNavigation("应用权限", it) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PhoneNavigation("返回应用", onMinimize, CallPhoneIcons.Minimize) }
                PhoneText("电话", Modifier.padding(horizontal = 8.dp), secondary = true, size = 14)
            }
            if (landscape) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { identity() }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { controls() }
            } else {
                identity()
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    controls()
                    PhoneText("通过 SoftVoWiFi", secondary = true, size = 14)
                }
            }
        }
    }
}

@Composable private fun PhoneSessionActions(state: CallUiState.Ready, audio: CallAudioControls, preparing: Boolean,
    keypad: Boolean, action: (CallAction) -> Unit, mute: () -> Unit, speaker: () -> Unit,
    toggleKeypad: () -> Unit, restore: () -> Unit) {
    val ready = state.sync == CallSyncState.Ready
    when (state.phase) {
        CallPhase.Ringing -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            PhoneButton("拒接", { action(CallAction.Reject) }, Modifier.weight(1f), icon = CallPhoneIcons.End, destructive = true,
                enabled = ready, busy = state.pendingAction == CallAction.Reject)
            PhoneButton("接听", { action(CallAction.Answer) }, Modifier.weight(1f), icon = CallPhoneIcons.Phone, selected = true, prominent = true,
                enabled = ready && state.pendingAction != CallAction.Reject, busy = preparing || state.pendingAction == CallAction.Answer)
        }
        CallPhase.InCall -> {
            if (!keypad) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PhoneButton("静音", mute, Modifier.weight(1f), icon = if (audio.muted) CallPhoneIcons.MicrophoneOff else CallPhoneIcons.Microphone,
                    toggle = true, selected = audio.muted, enabled = ready && state.audio == CallAudioState.Active)
                PhoneButton("免提", speaker, Modifier.weight(1f), icon = CallPhoneIcons.Speaker, toggle = true, selected = audio.speakerOn, enabled = ready && state.audio == CallAudioState.Active)
                if (state.dtmfSupported) PhoneButton("拨号键盘", toggleKeypad, Modifier.weight(1f), icon = CallPhoneIcons.Keypad,
                    toggle = true, selected = keypad, enabled = ready)
            }
            if (state.audio is CallAudioState.Failed) PhoneButton("恢复音频", restore, wide = true, selected = true, enabled = ready, busy = preparing)
            PhoneButton("挂断", { action(CallAction.Hangup) }, icon = CallPhoneIcons.End, destructive = true, enabled = ready,
                busy = state.pendingAction == CallAction.Hangup)
        }
        CallPhase.Dialing, CallPhase.Canceling -> PhoneButton(if (state.cancelPending) "正在取消" else "取消",
            { action(CallAction.Cancel) }, icon = CallPhoneIcons.End, destructive = true, enabled = ready && !state.cancelPending,
            busy = state.cancelPending)
        CallPhase.Ending -> PhoneButton("正在挂断", {}, icon = CallPhoneIcons.End, destructive = true, busy = true)
        CallPhase.Connecting -> PhoneButton("挂断", { action(CallAction.Hangup) }, icon = CallPhoneIcons.End,
            destructive = true, enabled = ready, busy = state.pendingAction == CallAction.Hangup)
        CallPhase.Idle -> PhoneText("通话已结束", size = 24)
    }
}
