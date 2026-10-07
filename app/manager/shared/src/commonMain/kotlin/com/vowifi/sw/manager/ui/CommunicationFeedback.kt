package com.vowifi.sw.manager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun Modifier.communicationContentPress(feedback: ManagerActionFeedback,
    interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val opacity by animateFloatAsState(
        if (feedback.accepted && pressed) .82f else 1f,
        tween(smsInnerMotionMillis()), label = "communication-content-press",
    )
    // Only the transparent content responds. No painted state layer or geometry changes.
    return graphicsLayer { alpha = if (feedback.accepted) opacity else 1f }
}

internal enum class CommunicationCopyFeedback(val label: String) {
    MESSAGE(MESSAGE_COPIED_FEEDBACK), NUMBER("已复制号码"),
}

internal val LocalCommunicationCopyFeedback = staticCompositionLocalOf<(CommunicationCopyFeedback) -> Unit> { {} }

/** A single transient result layer, outside list rows and composer layout. */
@Composable
internal fun CommunicationFeedbackHost(bottomInset: Dp = LocalRootBottomContentInset.current.value,
    content: @Composable () -> Unit) {
    var result by remember { mutableStateOf(CommunicationCopyFeedback.MESSAGE) }
    var generation by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    val active by rememberUpdatedState(LocalAppRouteActive.current)
    val accessibility = LocalAccessibilityManager.current
    val timeout = accessibility?.calculateRecommendedTimeoutMillis(2000L, containsText = true) ?: 2000L
    val show = remember {
        { value: CommunicationCopyFeedback ->
            if (active) { result = value; generation++; visible = true }
        }
    }
    LaunchedEffect(generation, active) {
        if (!active) visible = false
        else if (visible) { delay(timeout); visible = false }
    }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalCommunicationCopyFeedback provides show, content = content)
        AnimatedVisibility(visible = visible && active,
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = bottomInset + 12.dp),
            enter = fadeIn(tween(smsInnerMotionMillis())), exit = fadeOut(tween(smsInnerMotionMillis()))) {
            Surface(shape = RoundedCornerShape(12.dp), color = communicationColors().field) {
                key(generation) {
                    Text(result.label, Modifier.widthIn(max = 280.dp).semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        color = LocalSoftVoWiFiColors.current.textPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}
