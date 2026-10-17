package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

internal enum class ManagerLoadingPhase { Hidden, Loading, Exiting, Content }

/** Low-frequency presentation phase; no backend state is rewritten to Loading. */
internal class ManagerLoadingState internal constructor(initial: ManagerLoadingPhase) {
    internal var phase by mutableStateOf(initial)
    val hidden: Boolean get() = phase == ManagerLoadingPhase.Hidden
    val showLoading: Boolean get() = phase == ManagerLoadingPhase.Loading || phase == ManagerLoadingPhase.Exiting
    lateinit var alpha: State<Float>
        internal set
}

@Composable
internal fun rememberManagerLoadingState(loading: Boolean, hasContent: Boolean = false): ManagerLoadingState {
    val needed = rememberUpdatedState(loading && !hasContent)
    val state = remember {
        ManagerLoadingState(if (needed.value) ManagerLoadingPhase.Hidden else ManagerLoadingPhase.Content)
    }
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    LaunchedEffect(state) {
        coroutineScope {
            val presentationScope = this
            var minimum: Deferred<Unit>? = null
            snapshotFlow { needed.value }.collectLatest { show ->
                if (show) {
                    if (!state.showLoading) {
                        state.phase = ManagerLoadingPhase.Hidden
                        delay(MotionTokens.InitialLoadDelayMillis.toLong())
                        state.phase = ManagerLoadingPhase.Loading
                        // A producer finishing cancels only the collection branch, not this timer.
                        minimum = presentationScope.async {
                            delay(MotionTokens.InitialLoadMinimumVisibleMillis.toLong())
                        }
                    } else state.phase = ManagerLoadingPhase.Loading
                } else {
                    if (state.showLoading) {
                        minimum?.await()
                        state.phase = ManagerLoadingPhase.Exiting
                        if ((motionScale?.scaleFactor ?: 1f) > 0f) delay(MotionTokens.DurMicro.toLong())
                    }
                    state.phase = ManagerLoadingPhase.Content
                }
            }
        }
    }
    state.alpha = animateFloatAsState(
        targetValue = if (state.phase == ManagerLoadingPhase.Loading || state.phase == ManagerLoadingPhase.Content) 1f else 0f,
        animationSpec = tween(if (state.phase == ManagerLoadingPhase.Exiting) MotionTokens.DurMicro else MotionTokens.DurBase,
            easing = if (state.phase == ManagerLoadingPhase.Exiting) MotionTokens.EaseExit else MotionTokens.EaseEnter),
        label = "管理页加载透明度",
    )
    return state
}

internal fun Modifier.managerLoadingAlpha(state: ManagerLoadingState): Modifier =
    graphicsLayer { alpha = state.alpha.value }

/** L6 is an alpha-only leaf transition, not a second list or a content crossfade. */
@Composable
internal fun rememberManagerEmptyFade(empty: Boolean): State<Float> {
    val alpha = remember { Animatable(if (empty) 0f else 1f) }
    LaunchedEffect(empty) {
        if (empty) {
            alpha.snapTo(0f)
            alpha.animateTo(1f, tween(MotionTokens.DurBase, easing = MotionTokens.EaseEnter))
        } else alpha.snapTo(1f)
    }
    return alpha.asState()
}
