package com.vowifi.sw.manager.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.LogExportCompletion
import com.vowifi.sw.manager.model.LogExportOutcome
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text

internal enum class ManagerResultLabel(val text: String) {
    MessageCopied(MESSAGE_COPIED_FEEDBACK), NumberCopied("已复制号码"), LogsWritten("脱敏日志已导出"),
}

/** Presentation only: one result and one active-page registration, no producer or queue. */
@Stable
internal class ManagerResultFeedback {
    var label by mutableStateOf(ManagerResultLabel.MessageCopied)
        private set
    var visible by mutableStateOf(false)
        private set
    var generation by mutableLongStateOf(0L)
        private set
    var pageInset by mutableStateOf<State<Dp>?>(null)
        private set
    var pageSurface by mutableStateOf<State<Color>?>(null)
        private set
    private var resultOwner: Any? = null
    private var pageOwner: Any? = null
    fun show(label: ManagerResultLabel, owner: Any? = null) {
        this.label = label; resultOwner = owner; generation++; visible = true
    }
    fun hide(owner: Any) { if (resultOwner === owner) visible = false }
    fun clearExport() { if (resultOwner == null) visible = false }
    fun expire(generation: Long) { if (this.generation == generation) visible = false }
    fun register(owner: Any, inset: State<Dp>, surface: State<Color>) {
        pageOwner = owner; pageInset = inset; pageSurface = surface
    }
    fun unregister(owner: Any) {
        if (pageOwner === owner) { pageOwner = null; pageInset = null; pageSurface = null }
    }
}

internal val LocalManagerResultFeedback = staticCompositionLocalOf<ManagerResultFeedback?> { null }
@Composable internal fun rememberManagerResultFeedback() = remember { ManagerResultFeedback() }

/** AppShell invokes this leaf inside ManagerHaptics, above the existing page content. */
@Composable
internal fun ManagerResultFeedbackLayer(controller: ManagerResultFeedback, bottomInset: Dp,
    exportCompletion: LogExportCompletion? = null, affordanceInset: State<Dp>? = null) {
    var seenSequence by remember(controller) { mutableStateOf(exportCompletion?.sequence) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(controller, exportCompletion?.sequence) {
        val event = exportCompletion ?: return@LaunchedEffect
        val previous = seenSequence
        if (previous == null || event.sequence > previous) {
            seenSequence = event.sequence
            when (event.outcome) {
                LogExportOutcome.Written -> controller.show(ManagerResultLabel.LogsWritten)
                LogExportOutcome.Failed -> { controller.clearExport(); haptics.performHapticFeedback(HapticFeedbackType.Reject) }
                LogExportOutcome.Cancelled -> controller.clearExport()
            }
        }
    }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(controller, controller.generation, controller.visible, accessibility) {
        if (controller.visible) {
            val generation = controller.generation
            val timeout = accessibility?.calculateRecommendedTimeoutMillis(
                MotionTokens.ResultFeedbackTimeoutMillis, containsText = true) ?: MotionTokens.ResultFeedbackTimeoutMillis
            delay(timeout)
            controller.expire(generation)
        }
    }
    val base = rememberUpdatedState(bottomInset)
    val density = LocalDensity.current
    val enter = tween<Float>(MotionTokens.DurBase, easing = MotionTokens.EaseStandard)
    val slide = tween<IntOffset>(MotionTokens.DurBase, easing = MotionTokens.EaseStandard)
    val surface = controller.pageSurface?.value ?: communicationColors().field
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(controller.visible,
            modifier = Modifier.align(Alignment.BottomCenter).offset {
                val bottom = maxOf(base.value, controller.pageInset?.value ?: 0.dp, affordanceInset?.value ?: 0.dp)
                IntOffset(0, -bottom.roundToPx())
            }.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            enter = fadeIn(enter) + slideInVertically(slide) { with(density) { MotionTokens.OffsetSmall.roundToPx() } },
            exit = fadeOut(tween(MotionTokens.DurFast, easing = MotionTokens.EaseStandard))) {
            Surface(shape = RoundedCornerShape(12.dp), color = surface) {
                key(controller.generation) {
                    Text(controller.label.text, Modifier.widthIn(max = 280.dp).semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        color = LocalSoftVoWiFiColors.current.textPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}
