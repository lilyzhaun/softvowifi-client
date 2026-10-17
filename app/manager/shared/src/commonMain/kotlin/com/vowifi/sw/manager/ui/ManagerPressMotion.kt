package com.vowifi.sw.manager.ui

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

internal enum class ManagerPressStyle { Icon, Solid, Round, Card, CardRow, Content }

/** One source-owned visual cycle. Read these states only in a drawing/layer phase. */
@Stable
internal class ManagerPressMotion(
    val scale: State<Float>,
    val contentAlpha: State<Float>,
    val tintAlpha: State<Float>,
)

/** Explicit-source adapter; private library sources use the indication-node adapter instead. */
@Composable
internal fun rememberManagerPressMotion(
    interactionSource: InteractionSource,
    enabled: Boolean,
    style: ManagerPressStyle,
): ManagerPressMotion {
    val gate = rememberUpdatedState(enabled && LocalAppRouteActive.current)
    val scope = rememberCoroutineScope()
    val core = remember(interactionSource, style) { ManagerPressMotionCore(style, gate) }
    DisposableEffect(core, interactionSource, scope) {
        core.connect(scope, interactionSource)
        onDispose { core.disconnect() }
    }
    return core.motion
}

/** Keep this inside the stable clickable/semantics host; tint is drawn by that control's shape. */
internal fun Modifier.managerPressDrawing(motion: ManagerPressMotion): Modifier = graphicsLayer {
    scaleX = motion.scale.value
    scaleY = motion.scale.value
    alpha = motion.contentAlpha.value
}
