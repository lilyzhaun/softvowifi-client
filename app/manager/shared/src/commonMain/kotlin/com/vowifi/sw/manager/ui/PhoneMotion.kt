package com.vowifi.sw.manager.ui

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** Draw-only feedback: the caller owns the unscaled click target and gesture cancellation. */
@Composable
internal fun Modifier.phonePressMotion(enabled: Boolean, source: InteractionSource): Modifier {
    return managerPressDrawing(rememberManagerPressMotion(source, enabled, ManagerPressStyle.Round))
}

/** One effect per scroll owner; no second nested-scroll translation or platform stretch. */
@Composable
internal fun Modifier.managerVerticalScrollFeedback(): Modifier {
    val motion = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val active = LocalAppRouteActive.current
    // The haptic node must be outside the effect: it sees the original boundary velocity,
    // before the overscroll spring consumes it. ManagerHaptics retains app/system gating.
    return managerScrollEndFeedback().overScrollVertical(isEnabled = { active && motion > 0f })
}

/** Also used around Foundation's native effect, without adding a second viewport clip. */
@Composable
internal fun Modifier.managerScrollEndFeedback(): Modifier =
    if (LocalAppRouteActive.current) scrollEndHaptic() else this

/** Compatibility wrapper; callers still disable Foundation's separate overscroll effect. */
@Composable
internal fun Modifier.phoneOverscroll(): Modifier = managerVerticalScrollFeedback()
