package com.vowifi.sw.manager.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallSyncState
import com.vowifi.sw.manager.model.CallUiState
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon

internal data class OngoingCallPresentation(val state: CallUiState.Ready, val open: () -> Unit)
internal val LocalOngoingCall = compositionLocalOf<OngoingCallPresentation?> { null }

/** Clock state is read by the text leaf and semantics, never by the bar's composition/layout shell. */
@Composable internal fun rememberOngoingCallDuration(state: CallUiState.Ready, ticking: Boolean): State<String> {
    val clock = state.clock.takeIf { state.phase == CallPhase.InCall && state.sync == CallSyncState.Ready }
    if (clock == null) return remember { mutableStateOf("") }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    return produceState(clock.formatted(), clock, lifecycle, ticking) {
        value = clock.formatted()
        if (ticking && lifecycle == Lifecycle.State.RESUMED) {
            while (true) { value = clock.formatted(); delay(1000) }
        }
    }
}

@Composable internal fun OngoingCallAffordance(state: CallUiState.Ready, hitEnabled: Boolean = true,
    compact: Boolean = false, onClick: () -> Unit) {
    val active = hitEnabled && LocalAppRouteActive.current
    val activeNow by rememberUpdatedState(active)
    val interaction = remember { MutableInteractionSource() }
    val press = rememberManagerPressMotion(interaction, active, ManagerPressStyle.Solid)
    val click = rememberManagerActionHandler(managerActionFeedback(ManagerAction.Navigate, active), onClick)
    val palette = phoneColors()
    val shape = RoundedCornerShape(40.dp)
    val stage = callSessionStatusTitle(state)
    val duration = rememberOngoingCallDuration(state, active)
    fun label(): String = stage + duration.value.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty() + " · 返回通话"
    val target = Modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 64.dp)
    // C14 exits retain drawing only, just like PhoneButton: a closed input gate still consumes DOWN.
    val input = if (hitEnabled) target.managerPressInputGate(active)
        .clickable(enabled = active, role = Role.Button, interactionSource = interaction, indication = null,
            onClick = { if (activeNow) click() }) else target
    Box(Modifier.widthIn(max = 320.dp).then(if (compact) Modifier else Modifier.padding(8.dp))) {
        Box(input.managerKeyboardFocusRing(interaction, active, shape, palette.accent)
            .then(if (hitEnabled) Modifier.semantics(mergeDescendants = true) {
                contentDescription = label(); testTag = "ongoing-call-action"
            }
                else Modifier.clearAndSetSemantics {}), contentAlignment = Alignment.Center) {
            Row(target.managerPressDrawing(press).clip(shape).background(palette.tonal)
                .drawBehind { drawRect(palette.accent.copy(alpha = press.tintAlpha.value)) }
                .semantics { testTag = "ongoing-call-face" }
                .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(CallPhoneIcons.Phone, null, Modifier.size(if (compact) 20.dp else 24.dp), tint = palette.accent)
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.Center,
                    verticalArrangement = Arrangement.Center) {
                    OngoingCallText(stage, compact)
                    OngoingCallDurationText(duration, compact)
                    OngoingCallText(" · 返回通话", compact)
                }
            }
        }
    }
}

@Composable private fun OngoingCallDurationText(duration: State<String>, compact: Boolean) {
    duration.value.takeIf { it.isNotEmpty() }?.let { OngoingCallText(" $it", compact) }
}

@Composable private fun OngoingCallText(text: String, compact: Boolean) {
    // Retain the existing selected PhoneButton's accent, typography and 24dp root glyph.
    BasicText(text, style = TextStyle(color = phoneColors().accent, fontSize = (if (compact) 14 else 18).sp,
        lineHeight = (if (compact) 20 else 26).sp, fontFamily = FontFamily.SansSerif,
        fontWeight = if (compact) FontWeight.Normal else FontWeight.Medium,
        fontFeatureSettings = "tnum", textAlign = TextAlign.Center))
}

/** Each subpage owns its bar; expanding height is included in that scene's single top inset. */
@Composable internal fun SubpageOngoingCallBar(active: Boolean = LocalAppRouteActive.current,
    canvas: Color = LocalSoftVoWiFiColors.current.canvas) {
    val current = LocalOngoingCall.current
    val latest by rememberUpdatedState(current)
    var retained by remember { mutableStateOf(current) }
    SideEffect { if (current != null) retained = current }
    val spatial = MotionTokens.SpringSpatial
    val heightSpec = spring<IntSize>(spatial.dampingRatio, spatial.stiffness)
    AnimatedVisibility(current != null, modifier = Modifier.background(canvas),
        enter = expandVertically(heightSpec, expandFrom = Alignment.Top) +
            fadeIn(MotionTokens.SpringSpatial) + scaleIn(MotionTokens.SpringSpatial, initialScale = MotionTokens.ScaleEnterFrom),
        exit = shrinkVertically(heightSpec, shrinkTowards = Alignment.Top) +
            fadeOut(MotionTokens.SpringSpatial) + scaleOut(MotionTokens.SpringSpatial, targetScale = MotionTokens.ScaleEnterFrom)) {
        val shown = current ?: retained
        if (shown != null) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            OngoingCallAffordance(shown.state, hitEnabled = active && current != null, compact = true) {
                if (active) latest?.open?.invoke()
            }
        }
    }
}
