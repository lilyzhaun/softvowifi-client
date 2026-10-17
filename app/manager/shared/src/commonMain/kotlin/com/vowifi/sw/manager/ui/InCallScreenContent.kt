package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.vowifi.sw.manager.model.*
import top.yukonga.miuix.kmp.basic.Icon

/** One measured scene and one reversible draw progress. Busy snapshots never recreate the keypad. */
@Composable internal fun InCallScreenContent(
    state: CallUiState.Ready, line: String, audio: CallAudioControls, preparing: Boolean,
    onAction: (CallAction) -> Unit, onMute: () -> Unit, onSpeaker: () -> Unit,
    onDigit: (Char) -> Unit, onRestoreAudio: () -> Unit, onMinimize: () -> Unit,
    onKeypad: (Boolean) -> Unit, ticking: Boolean, onRefresh: () -> Unit, onPermissions: (() -> Unit)?,
    onReturn: () -> Unit,
) {
    val palette = phoneColors()
    val active = LocalAppRouteActive.current
    val ready = state.sync == CallSyncState.Ready
    val inCall = state.phase == CallPhase.InCall
    val ended = state.phase == CallPhase.Idle
    var retainedIdentity by remember { mutableStateOf(line to state.peer) }
    SideEffect { if (!ended) retainedIdentity = line to (state.peer ?: retainedIdentity.second) }
    var keypad by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    val progress = animateFloatAsState(if (keypad) 1f else 0f,
        spring(dampingRatio = 1f, stiffness = 500f, visibilityThreshold = .001f), label = "incall-keypad")
    val atRest by remember { derivedStateOf { progress.value == 0f } }
    val atOpen by remember { derivedStateOf { progress.value == 1f } }
    val presented = keypad || !atRest
    val sendEnabled = active && inCall && keypad && atOpen && ready && state.dtmfSupported && !state.dtmfBusy
    val sendEnabledNow by rememberUpdatedState(sendEnabled)
    val keypadScroll = rememberScrollState()
    val currentVisibility by rememberUpdatedState(onKeypad)
    LaunchedEffect(presented) { currentVisibility(presented) }
    DisposableEffect(Unit) { onDispose { currentVisibility(false) } }
    LaunchedEffect(keypad, atRest) { if (!keypad && atRest) { digits = ""; keypadScroll.scrollTo(0) } }
    // During resynchronization, capability is unknown rather than a confirmed loss.
    LaunchedEffect(inCall, ready, state.dtmfSupported) { if (inCall && ready && !state.dtmfSupported) keypad = false }
    if (LocalNavigationEventDispatcherOwner.current != null) {
        NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = active && presented, onBackCompleted = { keypad = false })
    }
    CompositionLocalProvider(LocalCommunicationBackdrop provides null) {
        BoxWithConstraints(Modifier.fillMaxSize().background(palette.canvas).safeDrawingPadding()
            .then(if (ended) Modifier.clickable(interactionSource = null, indication = null, onClick = onReturn) else Modifier)) {
            val landscape = maxWidth > 600.dp && maxHeight < 550.dp
            val identity: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PhoneText(if (ended) retainedIdentity.first else line, size = 14, secondary = true)
                        PhoneText((state.peer ?: state.terminal?.peer ?: retainedIdentity.second)?.text()?.let { displayCallNumber(privateUiPhoneDisplay(it)) } ?: "未知号码", size = 28)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CallStageText(if (ended && ready) "通话已结束" else callSessionStatusTitle(state), Modifier.alignByBaseline())
                            CallDurationText(state.clock, ticking, ready && inCall, Modifier.alignByBaseline())
                        }
                    listOfNotNull(state.operationMessage, (state.audio as? CallAudioState.Failed)?.message, audio.message)
                        .distinct().takeIf { it.isNotEmpty() }?.let { PhoneText(it.joinToString("\n"),
                            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, secondary = true) }
                    if (state.audio is CallAudioState.Failed) PhoneButton("恢复音频", onRestoreAudio,
                        wide = true, selected = true, enabled = ready && inCall, busy = preparing)
                    onPermissions?.let { PhoneNavigation("应用权限", it) }
                    }
            }
            val pane: @Composable (Modifier) -> Unit = { modifier ->
                    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
                        // Both trees remain measured. Only drawing changes during a transition.
                        Column(Modifier.matchParentSize().clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                            Box(Modifier.size(112.dp).background(palette.tonal, CircleShape), contentAlignment = Alignment.Center) {
                                CallStageGroup(state.phase) { phase ->
                                    Icon(if (phase == CallPhase.Idle || phase == CallPhase.Ending) CallPhoneIcons.End else CallPhoneIcons.Phone,
                                        null, Modifier.size(48.dp), tint = palette.accent)
                                }
                            }
                            PhoneText("通过 SoftVoWiFi", size = 14, secondary = true)
                        }
                        CompositionLocalProvider(LocalAppRouteActive provides sendEnabled) {
                            Column(Modifier.widthIn(max = 392.dp).fillMaxSize()
                                .graphicsLayer { translationY = size.height * (1f - progress.value) }
                                .background(palette.canvas)
                                .verticalScroll(keypadScroll, enabled = sendEnabled, overscrollEffect = null)
                                .then(if (!keypad || !atOpen) Modifier.clearAndSetSemantics {} else Modifier)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                BasicText(digits.ifEmpty { "通话按键" }, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    style = TextStyle(color = palette.text, fontSize = 24.sp, lineHeight = 32.sp,
                                        fontFamily = FontFamily.SansSerif, fontFeatureSettings = "tnum", textAlign = TextAlign.Center),
                                    maxLines = 1, softWrap = false)
                                // Input gating lives in LocalAppRouteActive, so a transient busy frame cannot flash every key gray.
                                CallKeypad(enabled = true, onDigit = {
                                    if (sendEnabledNow) { digits = (digits + it).takeLast(20); onDigit(it) }
                                })
                            }
                        }
                    }
            }
            val controls: @Composable (Modifier) -> Unit = { modifier ->
            CallStageGroup(state.phase, modifier.widthIn(max = 420.dp).fillMaxWidth()) { phase ->
            Column(Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Keep the original InCall row measured in every phase; only its drawing/input changes.
                CompositionLocalProvider(LocalAppRouteActive provides (LocalAppRouteActive.current && (phase == CallPhase.InCall || presented))) {
                Row(Modifier.fillMaxWidth().graphicsLayer { alpha = if (phase == CallPhase.InCall || presented) 1f else 0f }
                    .then(if (phase == CallPhase.InCall || presented) Modifier else Modifier.clearAndSetSemantics {}), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CompositionLocalProvider(LocalAppRouteActive provides (LocalAppRouteActive.current && ready && inCall)) {
                        PhoneButton("静音", onMute, Modifier.weight(1f), icon = if (audio.muted) CallPhoneIcons.MicrophoneOff else CallPhoneIcons.Microphone,
                            toggle = true, selected = audio.muted, enabled = state.audio == CallAudioState.Active, material = false,
                            colorSpec = tween(MotionTokens.DurFast, easing = MotionTokens.EaseStandard))
                        PhoneButton("免提", onSpeaker, Modifier.weight(1f), icon = CallPhoneIcons.Speaker,
                            toggle = true, selected = audio.speakerOn, enabled = state.audio == CallAudioState.Active, material = false,
                            colorSpec = tween(MotionTokens.DurFast, easing = MotionTokens.EaseStandard))
                    }
                    if (state.dtmfSupported || presented || !ready) Column(Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhoneButton(if (keypad) "关闭键盘" else "拨号键盘", { keypad = !keypad },
                            icon = if (keypad) CallPhoneIcons.Minimize else CallPhoneIcons.Keypad,
                            toggle = true, selected = keypad, enabled = keypad || (inCall && ready && state.dtmfSupported), material = false, showLabel = false)
                        PhoneText(if (keypad) "收起" else "键盘", Modifier.clearAndSetSemantics {}, size = 14)
                    }
                }
                }
                when (phase) {
                    CallPhase.InCall -> PhoneButton("挂断", { onAction(CallAction.Hangup) }, icon = CallPhoneIcons.End, destructive = true,
                        enabled = ready && inCall, busy = state.pendingAction == CallAction.Hangup)
                    CallPhase.Idle -> PhoneButton("返回通话记录", onReturn, Modifier.widthIn(min = 240.dp),
                        icon = CallPhoneIcons.End, selected = true, targetSize = 80.dp)
                    else -> PhoneSessionActions(state.copy(phase = phase), preparing, onAction)
                }
            }
            }
            }
            Column(Modifier.fillMaxSize()) {
                CommunicationNavigation("", leading = { PhoneNavigation("返回应用", if (ended) onReturn else onMinimize, CallPhoneIcons.Minimize) },
                    trailing = { if (canRefreshCallState(state)) CommunicationTextAction("重新同步", onRefresh) },
                    systemInset = false, background = false)
                if (landscape) Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                        identity(); controls(Modifier)
                    }
                    pane(Modifier.weight(1f).fillMaxHeight())
                } else {
                    identity()
                    pane(Modifier.weight(1f).fillMaxWidth())
                    controls(Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }
    }
}
