package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Draw-only feedback: the caller owns the unscaled click target and gesture cancellation. */
@Composable
internal fun Modifier.phonePressMotion(enabled: Boolean, source: InteractionSource): Modifier {
    var pressed by remember { mutableStateOf(false) }
    var released by remember { mutableStateOf(false) }
    LaunchedEffect(source, enabled) {
        pressed = false
        released = false
        if (enabled) source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> { released = false; pressed = true }
                is PressInteraction.Release -> { released = true; pressed = false }
                is PressInteraction.Cancel -> { released = false; pressed = false }
            }
        }
    }
    val scale by animateFloatAsState(
        if (enabled && pressed) .96f else 1f,
        spring(dampingRatio = if (enabled && released) .62f else 1f, stiffness = 700f,
            visibilityThreshold = .0001f), label = "phone-press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** One effect per scroll owner; no second nested-scroll translation or platform stretch. */
@Composable
internal fun Modifier.phoneOverscroll(): Modifier {
    val motion = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val enabled = LocalAppRouteActive.current && motion > 0f
    return overScrollVertical(isEnabled = { enabled })
}
