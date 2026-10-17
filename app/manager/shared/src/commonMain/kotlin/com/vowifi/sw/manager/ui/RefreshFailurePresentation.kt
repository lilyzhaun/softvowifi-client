package com.vowifi.sw.manager.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Only presentation exits; actual result, busy and retry acceptance never wait for motion. */
@Composable
internal fun RefreshFailurePresentation(
    spec: StatePanelSpec?,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    retryEnabled: Boolean = true,
) {
    val routeActive = LocalAppRouteActive.current
    val space = MotionTokens.SpringSpatialFast
    AnimatedContent(spec, contentAlignment = Alignment.TopStart, transitionSpec = {
        (fadeIn(tween(MotionTokens.DurFast, easing = MotionTokens.EaseStandard)) togetherWith
            fadeOut(tween(MotionTokens.DurFast, easing = MotionTokens.EaseStandard))) using
            SizeTransform(clip = false) { _, _ -> spring(dampingRatio = space.dampingRatio, stiffness = space.stiffness) }
    }, label = "refresh-failure") { shown ->
        val current = shown != null && shown == spec
        CompositionLocalProvider(LocalAppRouteActive provides (routeActive && current)) {
            Box(if (current) Modifier else Modifier.clearAndSetSemantics { }) {
                shown?.let { StatePanel(it, modifier, onRetry = onRetry, retryEnabled = retryEnabled && current) }
            }
        }
    }
}
