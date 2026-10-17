package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec

/** S1 §5.1 profiles; Round's successful release and cancellation keep their exact thresholds. */
internal data class ManagerPressMotionProfile(
    val pressedScale: Float,
    val pressedAlpha: Float,
    val pressedTint: Float,
    val pressScaleSpec: SpringSpec<Float>,
    val releaseScaleSpec: SpringSpec<Float>,
    val cancelScaleSpec: SpringSpec<Float>,
    val alphaSpec: TweenSpec<Float>,
    val tintInSpec: TweenSpec<Float>,
    val tintOutSpec: TweenSpec<Float>,
)

internal fun managerPressMotionProfile(style: ManagerPressStyle): ManagerPressMotionProfile {
    val round = style == ManagerPressStyle.Round
    return ManagerPressMotionProfile(
        pressedScale = when (style) {
            ManagerPressStyle.Icon -> MotionTokens.ScalePressSmall
            ManagerPressStyle.Card -> MotionTokens.ScalePressLarge
            ManagerPressStyle.Content -> 1f
            else -> MotionTokens.ScalePress
        },
        pressedAlpha = if (style == ManagerPressStyle.Content) MotionTokens.AlphaPressDim else 1f,
        pressedTint = when (style) {
            ManagerPressStyle.Icon, ManagerPressStyle.Solid, ManagerPressStyle.Round -> MotionTokens.TintPress
            else -> 0f
        },
        pressScaleSpec = if (round) MotionTokens.SpringPhonePressIn else MotionTokens.SpringPressScaleIn,
        releaseScaleSpec = if (round) MotionTokens.SpringPhonePressOutBouncy else MotionTokens.SpringPressScaleOut,
        cancelScaleSpec = if (round) MotionTokens.SpringPhonePressIn else MotionTokens.SpringPressScaleOut,
        alphaSpec = TweenSpec(durationMillis = MotionTokens.DurFast, easing = MotionTokens.EaseStandard),
        tintInSpec = TweenSpec(durationMillis = MotionTokens.DurMicro, easing = MotionTokens.EaseStandard),
        tintOutSpec = TweenSpec(durationMillis = MotionTokens.DurFast, easing = MotionTokens.EaseStandard),
    )
}
