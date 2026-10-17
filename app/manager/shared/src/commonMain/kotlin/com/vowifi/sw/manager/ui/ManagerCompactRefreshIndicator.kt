package com.vowifi.sw.manager.ui

// Public API reference: compose-miuix-ui/miuix 0.9.3 @ c36fab72391801d1e3ea5a00f966bf16bac28d4c.
// https://github.com/compose-miuix-ui/miuix/blob/c36fab72391801d1e3ea5a00f966bf16bac28d4c/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/basic/PullToRefresh.kt
// Reference license: Apache-2.0, https://github.com/compose-miuix-ui/miuix/blob/c36fab72391801d1e3ea5a00f966bf16bac28d4c/LICENSE
// Independent drawing/layout glue; no private FSM or draw function is copied.
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.PullToRefreshDefaults
import top.yukonga.miuix.kmp.basic.PullToRefreshState
import top.yukonga.miuix.kmp.basic.RefreshState
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun ManagerCompactRefreshIndicator(
    state: PullToRefreshState, controller: ManagerRefreshController, isRefreshing: Boolean,
    rotation: State<Float>, headerExtentPx: State<Float>, contentPadding: State<PaddingValues>,
) {
    val phase = state.refreshState
    val sequence = controller.sequence
    var sequenceFloor by remember(state, controller) { mutableLongStateOf(sequence) }
    // One receipt boundary only: an unaccepted new pull cannot replay the previous Completed.
    val floor = when {
        isRefreshing -> (sequence - 1L).coerceAtLeast(0L)
        phase == RefreshState.Idle || phase == RefreshState.Pulling || phase == RefreshState.ThresholdReached -> sequence
        else -> sequenceFloor
    }
    SideEffect { sequenceFloor = floor }
    val hasPullCaption by remember(state) { derivedStateOf { state.pullProgress > .5f } }
    val terminal = if (sequence > floor) controller.result else null
    val caption = when (phase) {
        RefreshState.Idle -> ""
        RefreshState.Pulling -> if (hasPullCaption) "下拉刷新" else ""
        RefreshState.ThresholdReached -> "松开刷新"
        RefreshState.Refreshing -> "正在刷新"
        RefreshState.RefreshComplete -> managerRefreshTexts(terminal).last()
    }
    if (!LocalAppRouteActive.current || phase == RefreshState.Idle) return
    val color = LocalSoftVoWiFiColors.current.interactive
    Box(Modifier.fillMaxSize().drawWithContent {
        val top = contentPadding.value.calculateTopPadding().toPx().coerceIn(0f, size.height)
        val bottom = (top + headerExtentPx.value.coerceAtLeast(0f)).coerceIn(top, size.height)
        if (bottom > top) clipRect(top = top, bottom = bottom) { this@drawWithContent.drawContent() }
    }) {
        Row(Modifier.align(Alignment.TopCenter).graphicsLayer {
            val height = headerExtentPx.value.coerceAtLeast(0f)
            translationY = contentPadding.value.calculateTopPadding().toPx() + (height - size.height) / 2f
            alpha = (height / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
        }, horizontalArrangement = Arrangement.spacedBy(MotionTokens.OffsetSmall), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.requiredSize(20.dp)) {
                val width = 2.dp.toPx()
                val stroke = Stroke(width = width, cap = StrokeCap.Round)
                val inset = Offset(width / 2f, width / 2f)
                val bounds = Size(size.width - width, size.height - width)
                val radius = (size.minDimension - width) / 2f
                when (phase) {
                    RefreshState.Pulling -> drawArc(color, -90f, 360f * state.pullProgress, false, inset, bounds, style = stroke)
                    RefreshState.ThresholdReached, RefreshState.RefreshComplete -> drawCircle(color, radius, style = stroke)
                    RefreshState.Refreshing -> {
                        drawCircle(color.copy(alpha = .35f), radius, style = stroke)
                        drawArc(color, rotation.value - 90f, 60f, false, inset, bounds, style = stroke)
                    }
                    RefreshState.Idle -> Unit
                }
            }
            Text(caption, color = color, style = PullToRefreshDefaults.refreshTextStyle)
        }
    }
}
