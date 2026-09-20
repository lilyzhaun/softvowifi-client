package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface

@Composable fun CallLoadingContent(onRefresh: () -> Unit, onBack: () -> Unit, message: String = "正在确认通话") {
    Column(Modifier.fillMaxSize().background(phoneColors().canvas).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        PhoneText(message)
        PhoneButton("刷新", onRefresh, wide = true, selected = true)
        PhoneButton("返回", onBack, wide = true)
    }
}

@Composable fun CallEndContent(message: String?, onReturn: () -> Unit) {
    Column(Modifier.fillMaxSize().background(phoneColors().canvas).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
        PhoneText("通话已结束", size = 24)
        message?.let { PhoneText(it, secondary = true) }
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
    LaunchedEffect(keypad) { onKeypad(keypad) }
    LaunchedEffect(state.dtmfSupported) { if (!state.dtmfSupported) { keypad = false; digits = "" } }
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
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(Modifier.fillMaxWidth()) { PhoneNavigation("返回应用", onMinimize, CallPhoneIcons.Minimize) }
            val identity: @Composable () -> Unit = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    PhoneText(line, secondary = true)
                    PhoneText(state.peer?.text()?.let { displayCallNumber(privateUiPhoneDisplay(it)) } ?: "未知号码", size = 28)
                    val status = when (state.sync) {
                        CallSyncState.Ready -> if (state.phase == CallPhase.InCall && duration.isNotEmpty()) "通话中 · $duration" else callPhaseTitle(state.phase)
                        CallSyncState.Recovering -> "正在恢复通话状态"
                        CallSyncState.Unavailable -> "通话状态待确认"
                        CallSyncState.Unregistered -> "线路未注册"
                        CallSyncState.OwnerChanged -> "通话线路已变化"
                    }
                    PhoneText(status, size = 16)
                    if (!keypad) Surface(shape = CircleShape, color = palette.surface) {
                        Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
                            Icon(CallPhoneIcons.Phone, null, tint = palette.accent, modifier = Modifier.size(56.dp))
                        }
                    }
                }
            }
            val controls: @Composable () -> Unit = {
                Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (keypad) {
                        PhoneText(digits.ifEmpty { "通话按键" }, size = 24)
                        CallKeypad(state.dtmfSupported && !state.dtmfBusy, { digits = (digits + it).takeLast(20); onDigit(it) })
                        PhoneNavigation("关闭键盘", { keypad = false; digits = "" })
                    }
                    PhoneSessionActions(state, audio, preparing, keypad, onAction, onMute, onSpeaker,
                        { keypad = !keypad; if (!keypad) digits = "" }, onRestoreAudio)
                    if (state.sync != CallSyncState.Ready) PhoneButton("重新同步", onRefresh, wide = true, selected = true)
                    state.operationMessage?.let { PhoneText(it, secondary = true) }
                    (state.audio as? CallAudioState.Failed)?.message?.let { PhoneText(it, secondary = true) }
                    audio.message?.let { PhoneText(it, secondary = true) }
                    onPermissions?.let { PhoneNavigation("应用权限", it) }
                }
            }
            if (landscape) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { identity() }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { controls() }
            } else {
                identity()
                Spacer(Modifier.weight(if (state.phase == CallPhase.Ringing) .6f else 1f).heightIn(min = 16.dp))
                controls()
                if (state.phase == CallPhase.Ringing) Spacer(Modifier.weight(.4f))
            }
            PhoneText("通过 SoftVoWiFi", secondary = true, size = 14)
        }
    }
}

@Composable private fun PhoneSessionActions(state: CallUiState.Ready, audio: CallAudioControls, preparing: Boolean,
    keypad: Boolean, action: (CallAction) -> Unit, mute: () -> Unit, speaker: () -> Unit,
    toggleKeypad: () -> Unit, restore: () -> Unit) {
    val ready = state.sync == CallSyncState.Ready
    when (state.phase) {
        CallPhase.Ringing -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            PhoneButton("拒接", { action(CallAction.Reject) }, icon = CallPhoneIcons.End, destructive = true,
                enabled = ready, busy = state.pendingAction == CallAction.Reject)
            PhoneButton("接听", { action(CallAction.Answer) }, icon = CallPhoneIcons.Phone, selected = true,
                enabled = ready && state.pendingAction != CallAction.Reject, busy = preparing || state.pendingAction == CallAction.Answer)
        }
        CallPhase.InCall -> {
            if (!keypad) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PhoneButton("静音", mute, icon = CallPhoneIcons.Microphone, selected = audio.muted, enabled = ready && state.audio == CallAudioState.Active)
                PhoneButton("免提", speaker, icon = CallPhoneIcons.Speaker, selected = audio.speakerOn, enabled = ready && state.audio == CallAudioState.Active)
                if (state.dtmfSupported) PhoneButton("拨号键盘", toggleKeypad, icon = CallPhoneIcons.Keypad, enabled = ready)
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
