package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface

@Composable internal fun PhoneEmblem(icon: ImageVector) {
    Surface(shape = CircleShape, color = phoneColors().surface) {
        Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = phoneColors().accent, modifier = Modifier.size(36.dp))
        }
    }
}

/** Phase leaves only: no size transform, and a departing action cannot retain input ownership. */
@Composable internal fun <T> CallStageGroup(target: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val current by rememberUpdatedState(target)
    val active = LocalAppRouteActive.current
    AnimatedContent(target, modifier, contentAlignment = Alignment.BottomCenter, transitionSpec = {
        (fadeIn(MotionTokens.FadeThrough.incomingAlphaSpec) togetherWith
            fadeOut(MotionTokens.FadeThrough.outgoingAlphaSpec)) using null
    }, label = "call-stage-group") { shown ->
        val incomingScale = transition.animateFloat(
            transitionSpec = { MotionTokens.FadeThrough.incomingScaleSpec }, label = "call-stage-draw-scale",
        ) { if (it == EnterExitState.PreEnter) MotionTokens.FadeThrough.incomingScaleFrom else 1f }
        val interactive = active && shown == current
        CompositionLocalProvider(LocalAppRouteActive provides interactive) {
            Box(Modifier.drawWithContent {
                scale(incomingScale.value) { this@drawWithContent.drawContent() }
            }.managerPressInputGate(interactive).then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})) { content(shown) }
        }
    }
}

@Composable internal fun CallStageText(text: String, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(text)
    Box(modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center) {
        AnimatedContent(text, contentAlignment = Alignment.Center, transitionSpec = {
            (fadeIn(MotionTokens.Crossfade.alphaSpec) togetherWith
                fadeOut(MotionTokens.Crossfade.alphaSpec)) using null
        }, label = "call-stage-text") { shown ->
            PhoneText(shown, if (shown == current) Modifier else Modifier.clearAndSetSemantics {}, size = 14, secondary = true)
        }
    }
}

@Composable internal fun CallDurationText(clock: com.vowifi.sw.manager.data.CallSessionClock?, ticking: Boolean, visible: Boolean,
    modifier: Modifier = Modifier) {
    val duration by produceState(clock?.formatted().orEmpty(), clock, ticking, visible) {
        while (clock != null && ticking && visible) { value = clock.formatted(); kotlinx.coroutines.delay(1000) }
    }
    var lastDuration by remember { mutableStateOf("00:00") }
    SideEffect { if (visible && duration.isNotEmpty()) lastDuration = duration }
    val present = visible && duration.isNotEmpty()
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = present
    // Preserve the clock fade, but do not reserve a hidden " · 00:00" before connection.
    AnimatedVisibility(visibility, modifier = modifier,
        enter = fadeIn(tween(MotionTokens.DurBase, easing = MotionTokens.EaseStandard)),
        exit = fadeOut(tween(MotionTokens.DurBase, easing = MotionTokens.EaseStandard))) {
        PhoneText(" · ${if (present) duration else lastDuration}",
            if (visible) Modifier else Modifier.clearAndSetSemantics {}, size = 14, secondary = true)
    }
}

@Composable internal fun PhoneStatusRow(label: String, icon: ImageVector) {
    Surface(shape = RoundedCornerShape(24.dp), color = phoneColors().surface) {
        Row(Modifier.semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = phoneColors().accent, modifier = Modifier.size(20.dp))
            PhoneText(label, Modifier.weight(1f, fill = false))
        }
    }
}

@Composable internal fun PhoneResultContent(
    title: String, message: String?, icon: ImageVector, actions: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(phoneColors().canvas).safeDrawingPadding()) {
        val viewportHeight = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewportHeight)
            .padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween) {
            PhoneText("电话", secondary = true, size = 14)
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                PhoneEmblem(icon)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PhoneText(title, size = 24)
                    message?.let { PhoneText(it, secondary = true) }
                }
            }
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp), content = actions)
        }
    }
}
