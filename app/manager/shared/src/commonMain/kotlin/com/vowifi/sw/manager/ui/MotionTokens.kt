// Independent implementation of the damped step-response mechanism and values used by
// compose-miuix-ui/miuix 0.9.3 @ c36fab72391801d1e3ea5a00f966bf16bac28d4c.
// Reference copyright 2025, compose-miuix-ui contributors; reference license Apache-2.0.
// https://github.com/compose-miuix-ui/miuix/blob/c36fab72391801d1e3ea5a00f966bf16bac28d4c/miuix-navigation3-ui/src/commonMain/kotlin/androidx/navigation3/animation/NavTransitionEasing.kt
// https://github.com/compose-miuix-ui/miuix/blob/c36fab72391801d1e3ea5a00f966bf16bac28d4c/LICENSE
// Only the response/damping mechanism and values are referenced; no internal class is used.
package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** §3 values and the §4.4 press threshold; component exceptions keep their exact values. */
internal object MotionTokens {
    const val DurInstant = 0
    const val DurMicro = 100
    const val DurFast = 150
    const val DurBase = 200
    const val DurEmphasis = 300
    const val DurLarge = 400
    const val DurPage = 500

    // Equal-value component timings; not approximated by the general duration tiers.
    const val DurPhoneColor = 160
    const val DurInputOutline = 140

    // R5/C7/C15/L1/L5: presentation timing is distinct from the producer's real lifetime.
    const val InitialLoadDelayMillis = 150
    const val InitialLoadMinimumVisibleMillis = 300
    const val RefreshMinimumVisibleMillis = 400
    const val RefreshRotationMillis = 600
    const val DisclosureContentDelayMillis = 50
    const val ResultFeedbackTimeoutMillis = 2_000L

    val EaseStandard: Easing = CubicBezierEasing(.2f, 0f, 0f, 1f)
    val EaseEnter: Easing = CubicBezierEasing(.05f, .7f, .1f, 1f)
    val EaseExit: Easing = CubicBezierEasing(.3f, 0f, 1f, 1f)
    val EaseLinear: Easing = LinearEasing
    const val PageResponse = .8f
    const val PageDampingRatio = .95f
    val EasePage: Easing = PageSpringEasing(PageResponse, PageDampingRatio)

    val SpringPressIn: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 700f)
    val SpringPressOut: SpringSpec<Float> = spring(dampingRatio = .9f, stiffness = 700f)
    val SpringPressOutBouncy: SpringSpec<Float> = spring(dampingRatio = .62f, stiffness = 700f)
    val SpringSpatialFast: SpringSpec<Float> = spring(dampingRatio = .9f, stiffness = 700f)
    val SpringSpatial: SpringSpec<Float> = spring(dampingRatio = .9f, stiffness = 322f)
    val SpringSpatialLarge: SpringSpec<Float> = spring(dampingRatio = .95f, stiffness = 200f)
    // 2026-10-17 messages: SpringSpatial over a normalized 0..1 travel needs a sub-pixel rest threshold.
    const val FractionVisibilityThreshold = .001f
    val SpringSpatialFraction: SpringSpec<Float> = spring(.9f, 322f, FractionVisibilityThreshold)

    const val PressMinimumVisibleMillis = 90
    const val PressScaleVisibilityThreshold = .0001f
    val SpringPressScaleIn: SpringSpec<Float> = spring(1f, 700f, PressScaleVisibilityThreshold)
    val SpringPressScaleOut: SpringSpec<Float> = spring(.9f, 700f, PressScaleVisibilityThreshold)

    const val ScalePressSmall = .88f
    const val ScalePress = .96f
    const val ScalePressLarge = .98f
    const val ScaleEnterFrom = .96f
    const val AlphaPressDim = .82f
    const val AlphaDisabled = .55f
    const val AlphaPending = .5f
    const val TintPress = .08f
    const val ScalePopFrom = .6f
    val OffsetSmall: Dp = 8.dp

    // Retained component values are deliberately distinct from the general spring tiers.
    val SpringRootPager: SpringSpec<Float> = spring(.9f, 322.27f, .5f)
    val SpringPhonePressIn: SpringSpec<Float> = spring(1f, 700f, .0001f)
    val SpringPhonePressOutBouncy: SpringSpec<Float> = spring(.62f, 700f, .0001f)
    val SpringInCallKeypad: SpringSpec<Float> = spring(1f, 500f, .001f)

    const val BottomBarValueVisibilityThreshold = .001f
    const val BottomBarVelocityVisibilityMultiplier = 10f
    const val BottomBarVelocityRestThreshold = 5f
    const val BottomBarReleaseProximityFraction = .025f
    const val ScaleBottomBarInitial = 1f
    const val ScaleBottomBarPressed = 78f / 56f
    val BottomBarRubberBand: Dp = 4.dp
    val EaseBottomBarRubberBand: Easing = EaseOut
    val SpringBottomBarValue: SpringSpec<Float> = spring(1f, 1000f, BottomBarValueVisibilityThreshold)
    val SpringBottomBarVelocity: SpringSpec<Float> = spring(
        .5f, 300f, BottomBarValueVisibilityThreshold * BottomBarVelocityVisibilityMultiplier,
    )
    val SpringBottomBarPress: SpringSpec<Float> = spring(1f, 1000f, .001f)
    val SpringBottomBarScaleX: SpringSpec<Float> = spring(.6f, 250f, .001f)
    val SpringBottomBarScaleY: SpringSpec<Float> = spring(.7f, 250f, .001f)
    val SpringBottomBarRubberBand: SpringSpec<Float> = spring(1f, 300f, .5f)
    const val DurBottomBarMode = 220
    val EaseBottomBarMode: Easing = FastOutSlowInEasing
    const val ScaleBottomBarMode = ScaleEnterFrom
    const val DurBottomBarPress = 180
    const val DurBottomBarIndicator = 0 // Spring-owned, not a 180ms tween.

    const val DurContextEnter = 260
    const val DurContextExit = 170
    val EaseContextEnter: Easing = CubicBezierEasing(.2f, .8f, .2f, 1f)
    val EaseContextExit: Easing = EaseStandard
    const val ScaleContextMenuFrom = .94f
    const val ScaleContextMenuTravel = .06f // Preserve the original literal, not 1f - .94f.
    val OffsetContextMenu: Dp = 6.dp

    const val DurSendState = 120
    const val AlphaSendDisabled = .56f
    val EaseSendState: Easing = FastOutSlowInEasing

    // Per docs/设计-交互与动效规范.md §3.3's S0 source correction: metadata only.
    // SpringMath in miuix-ui-desktop:0.9.3 matches the pinned source parameters below.
    // miuix retains its own integration/settling process; no replacement SpringSpec exists.
    // https://github.com/compose-miuix-ui/miuix/blob/c36fab72391801d1e3ea5a00f966bf16bac28d4c/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/utils/SpringUtils.kt
    const val OverscrollLibraryOwned = true
    const val OverscrollDampingRatio = 1f
    const val OverscrollStandardPeriod = .4f
    const val OverscrollHighVelocityPeriod = .55f
    const val OverscrollHighVelocityThreshold = 5000f

    const val DurFadeThroughOut = 90
    const val DurFadeThroughIn = 210
    val Crossfade = CrossfadeMotionToken(
        durationMillis = DurBase,
        alphaSpec = TweenSpec(durationMillis = DurBase, easing = EaseStandard),
    )
    val FadeThrough = FadeThroughMotionToken(
        durationMillis = DurEmphasis,
        incomingScaleFrom = ScaleEnterFrom,
        outgoingAlphaSpec = TweenSpec(durationMillis = DurFadeThroughOut, easing = EaseLinear),
        incomingAlphaSpec = TweenSpec(
            durationMillis = DurFadeThroughIn, delay = DurFadeThroughOut, easing = EaseLinear,
        ),
        incomingScaleSpec = TweenSpec(
            durationMillis = DurFadeThroughIn, delay = DurFadeThroughOut, easing = EaseEnter,
        ),
    )
    val NumericRoll = NumericRollMotionToken(
        durationMillis = DurBase,
        offset = OffsetSmall,
        animationSpec = TweenSpec(durationMillis = DurBase, easing = EaseStandard),
    )
}

internal data class CrossfadeMotionToken(
    val durationMillis: Int,
    val alphaSpec: TweenSpec<Float>,
)

internal data class FadeThroughMotionToken(
    val durationMillis: Int,
    val incomingScaleFrom: Float,
    val outgoingAlphaSpec: TweenSpec<Float>,
    val incomingAlphaSpec: TweenSpec<Float>,
    val incomingScaleSpec: TweenSpec<Float>,
)

internal data class NumericRollMotionToken(
    val durationMillis: Int,
    val offset: Dp,
    val animationSpec: TweenSpec<Float>,
) {
    fun outgoingOffset(increasing: Boolean): Dp = if (increasing) -offset else offset
    fun incomingOffset(increasing: Boolean): Dp = if (increasing) offset else -offset
}

/** Same normalized spring step response; TweenSpec owns the 500ms timeline and final target. */
private class PageSpringEasing(response: Float, dampingRatio: Float) : Easing {
    private val decayRate: Float
    private val dampedFrequency: Float
    private val sineWeight: Float

    init {
        val naturalFrequency = 2.0 * PI / response
        val stiffnessOverMass = naturalFrequency * naturalFrequency
        val dampingOverMass = dampingRatio * 4.0 * PI / response
        decayRate = (-dampingOverMass / 2.0).toFloat()
        dampedFrequency = (sqrt(4.0 * stiffnessOverMass - dampingOverMass * dampingOverMass) / 2.0).toFloat()
        sineWeight = decayRate / dampedFrequency
    }

    override fun transform(fraction: Float): Float {
        val time = fraction.toDouble()
        val oscillation = cos(dampedFrequency * time) - sineWeight * sin(dampedFrequency * time)
        return (1.0 - exp(decayRate * time) * oscillation).toFloat()
    }
}
