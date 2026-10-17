package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * S0 contract: docs/设计-交互与动效规范.md §3, plus exact retained component values.
 * The initial missing-source assertion RED was observed before implementing the tokens.
 * This contract now consumes the production Kotlin API directly, without runtime reflection.
 * The missing-file/package/source/license checks remain alongside the numeric contracts.
 *
 * EasePage reference: compose-miuix-ui/miuix 0.9.3, Apache-2.0,
 * commit c36fab72391801d1e3ea5a00f966bf16bac28d4c:
 * https://github.com/compose-miuix-ui/miuix/blob/c36fab72391801d1e3ea5a00f966bf16bac28d4c/miuix-navigation3-ui/src/commonMain/kotlin/androidx/navigation3/animation/NavTransitionEasing.kt
 * https://github.com/compose-miuix-ui/miuix/blob/c36fab72391801d1e3ea5a00f966bf16bac28d4c/LICENSE
 */
class MotionTokensTest {
    @Test
    fun loadingRefreshDisclosureAndResultTimingsHaveExplicitTokens() {
        // S3 R5/C7/C15/L1/L5: declare the timing contract before wiring its consumers.
        val source = readRequired("MotionTokens.kt")
        val names = listOf(
            "InitialLoadDelayMillis", "InitialLoadMinimumVisibleMillis",
            "RefreshMinimumVisibleMillis", "RefreshRotationMillis",
            "DisclosureContentDelayMillis", "ResultFeedbackTimeoutMillis",
        )
        val missing = names.filterNot { name ->
            Regex("(?m)^\\s*const\\s+val\\s+${Regex.escape(name)}\\b").containsMatchIn(source)
        }
        assertTrue(missing.isEmpty(), "S3 timing declarations are missing: ${missing.joinToString()}")
        assertEquals(150, MotionTokens.InitialLoadDelayMillis)
        assertEquals(300, MotionTokens.InitialLoadMinimumVisibleMillis)
        assertEquals(400, MotionTokens.RefreshMinimumVisibleMillis)
        assertEquals(600, MotionTokens.RefreshRotationMillis)
        assertEquals(50, MotionTokens.DisclosureContentDelayMillis)
        assertEquals(2_000L, MotionTokens.ResultFeedbackTimeoutMillis)
    }

    @Test
    fun centralizedProductionTokensExistWithPinnedSourceAndLicense() {
        val source = readRequired("MotionTokens.kt")
        assertTrue(source.contains("package com.vowifi.sw.manager.ui"))
        assertTrue(Regex("\\bobject\\s+MotionTokens\\b").containsMatchIn(source))
        assertTrue(source.contains("c36fab72391801d1e3ea5a00f966bf16bac28d4c"))
        assertTrue(source.contains("Apache-2.0"))
        assertTrue(source.contains("https://github.com/compose-miuix-ui/miuix/"))
        assertTrue(source.contains("miuix-navigation3-ui/src/commonMain/kotlin/androidx/navigation3/animation/NavTransitionEasing.kt"))
        assertFalse(source.contains("import androidx.navigation3.animation.NavTransitionEasing"))
        assertFalse(source.contains("androidx.navigation3.animation.NavTransitionEasing("))
    }

    @Test
    fun retainedComponentMotionTokensAreRegisteredForIntegrationFindings() {
        // C-I01–03 / §3.3: supplemental source-only missing-token RED was observed first.
        // Retain the declaration contract alongside direct typed/spec consumption checks.
        val source = readRequired("MotionTokens.kt")
        val requiredNames = listOf(
            "EaseBottomBarMode", "ScaleBottomBarMode",
            "ScaleContextMenuFrom", "ScaleContextMenuTravel", "OffsetContextMenu",
            "DurSendState", "AlphaSendDisabled", "EaseSendState",
        )
        val missing = requiredNames.filterNot { name ->
            Regex("(?m)^\\s*(?:const\\s+)?val\\s+${Regex.escape(name)}\\b").containsMatchIn(source)
        }
        assertTrue(
            missing.isEmpty(),
            "S0 retained component motion token declarations are missing: ${missing.joinToString()}",
        )

        assertSame(FastOutSlowInEasing, MotionTokens.EaseBottomBarMode)
        assertEquals(.96f, MotionTokens.ScaleBottomBarMode)
        assertEquals(.94f, MotionTokens.ScaleContextMenuFrom)
        assertEquals(.06f, MotionTokens.ScaleContextMenuTravel)
        assertNotEquals(1f - MotionTokens.ScaleContextMenuFrom, MotionTokens.ScaleContextMenuTravel)
        assertEquals(6.dp, MotionTokens.OffsetContextMenu)
        assertEquals(120, MotionTokens.DurSendState)
        assertEquals(.56f, MotionTokens.AlphaSendDisabled)
        assertSame(FastOutSlowInEasing, MotionTokens.EaseSendState)

        // Independent parametric-bisection reference: cubic(.4,0,.2,1) at .5 = .775561311116.
        // Consume actual Compose specs; no old production consumer is rewired here.
        val modeSpec = TweenSpec<Float>(MotionTokens.DurBottomBarMode, easing = MotionTokens.EaseBottomBarMode)
        assertTween(modeSpec, 220, 0, FastOutSlowInEasing)
        assertEquals(0f, value(modeSpec, 0f, 1f, 0))
        assertEquals(.77556131f, value(modeSpec, 0f, 1f, 110), .0002f)
        assertEquals(1f, value(modeSpec, 0f, 1f, 220))
        assertEquals(.96f, value(modeSpec, MotionTokens.ScaleBottomBarMode, 1f, 0))
        assertEquals(.99102245f, value(modeSpec, MotionTokens.ScaleBottomBarMode, 1f, 110), .00002f)
        assertEquals(.96897755f, value(modeSpec, 1f, MotionTokens.ScaleBottomBarMode, 110), .00002f)
        assertEquals(1f, value(modeSpec, MotionTokens.ScaleBottomBarMode, 1f, 220))
        assertEquals(.96f, value(modeSpec, 1f, MotionTokens.ScaleBottomBarMode, 220))

        val menuEnter = TweenSpec<Float>(MotionTokens.DurContextEnter, easing = MotionTokens.EaseContextEnter)
        val menuExit = TweenSpec<Float>(MotionTokens.DurContextExit, easing = MotionTokens.EaseContextExit)
        assertTween(menuEnter, 260, 0, MotionTokens.EaseContextEnter)
        assertTween(menuExit, 170, 0, MotionTokens.EaseContextExit)
        val entering = value(menuEnter, 0f, 1f, 130)
        val exiting = value(menuExit, 1f, 0f, 85)
        // These fixed expected values use the independent context-curve interior samples.
        assertEquals(.99676477f, MotionTokens.ScaleContextMenuFrom + MotionTokens.ScaleContextMenuTravel * entering, .00002f)
        assertEquals(.94732998f, MotionTokens.ScaleContextMenuFrom + MotionTokens.ScaleContextMenuTravel * exiting, .00002f)
        assertEquals(.32352323f, (MotionTokens.OffsetContextMenu * (1f - entering)).value, .002f)
        assertEquals(-.32352323f, (-MotionTokens.OffsetContextMenu * (1f - entering)).value, .002f)
        assertEquals(5.26700163f, (MotionTokens.OffsetContextMenu * (1f - exiting)).value, .002f)
        val entered = value(menuEnter, 0f, 1f, 260)
        val exited = value(menuExit, 1f, 0f, 170)
        assertEquals(1f, MotionTokens.ScaleContextMenuFrom + MotionTokens.ScaleContextMenuTravel * entered)
        assertEquals(.94f, MotionTokens.ScaleContextMenuFrom + MotionTokens.ScaleContextMenuTravel * exited)
        assertEquals(0.dp, MotionTokens.OffsetContextMenu * (1f - entered))
        assertEquals(6.dp, MotionTokens.OffsetContextMenu * (1f - exited))

        val sendSpec = TweenSpec<Float>(MotionTokens.DurSendState, easing = MotionTokens.EaseSendState)
        assertTween(sendSpec, 120, 0, FastOutSlowInEasing)
        assertEquals(.56f, value(sendSpec, MotionTokens.AlphaSendDisabled, 1f, 0))
        assertEquals(.90124698f, value(sendSpec, MotionTokens.AlphaSendDisabled, 1f, 60), .0002f)
        assertEquals(1f, value(sendSpec, MotionTokens.AlphaSendDisabled, 1f, 120))
        assertEquals(1f, value(sendSpec, 1f, MotionTokens.AlphaSendDisabled, 0))
        assertEquals(.65875302f, value(sendSpec, 1f, MotionTokens.AlphaSendDisabled, 60), .0002f)
        assertEquals(.56f, value(sendSpec, 1f, MotionTokens.AlphaSendDisabled, 120))
        assertEquals(.77556131f, value(sendSpec, 0f, 1f, 60), .0002f)
        assertEquals(1f, value(sendSpec, 0f, 1f, 120))
    }

    @Test
    fun durationTierValuesMatchSectionThreePointOne() {
        val expected = listOf(
            Triple("DurInstant", 0, MotionTokens.DurInstant),
            Triple("DurMicro", 100, MotionTokens.DurMicro),
            Triple("DurFast", 150, MotionTokens.DurFast),
            Triple("DurBase", 200, MotionTokens.DurBase),
            Triple("DurEmphasis", 300, MotionTokens.DurEmphasis),
            Triple("DurLarge", 400, MotionTokens.DurLarge),
            Triple("DurPage", 500, MotionTokens.DurPage),
        )
        expected.forEach { (name, millis, actual) -> assertEquals(millis, actual, name) }
    }

    @Test
    fun cubicAndLinearCurvesHaveIndependentInteriorSamplesAndExactEndpoints() {
        // Fixed samples from independent parametric bisection, not a copied Easing implementation.
        val expected = listOf(
            Triple("EaseStandard", MotionTokens.EaseStandard, listOf(.60722036f, .87783360f, .97548007f)),
            Triple("EaseEnter", MotionTokens.EaseEnter, listOf(.83152975f, .95024748f, .99051096f)),
            Triple("EaseExit", MotionTokens.EaseExit, listOf(.12850212f, .37202939f, .66756849f)),
            Triple("EaseLinear", MotionTokens.EaseLinear, listOf(.25f, .5f, .75f)),
        )
        expected.forEach { (name, easing, samples) ->
            assertEquals(0f, easing.transform(0f), name)
            assertEquals(1f, easing.transform(1f), name)
            listOf(.25f, .5f, .75f).zip(samples).forEach { (fraction, value) ->
                assertEquals(value, easing.transform(fraction), .0002f, "$name at $fraction")
            }
        }
    }

    @Test
    fun pageCurveMatchesIndependentDampedOscillatorSamplesIncludingItsRawEndpoint() {
        assertEquals(.8f, MotionTokens.PageResponse)
        assertEquals(.95f, MotionTokens.PageDampingRatio)
        val easing = MotionTokens.EasePage
        // Independent RK4 integration of a unit-mass step response, x(0)=v(0)=0,
        // response=.8, damping=.95, step=.00001. Time is the easing fraction, NOT .5s.
        // Upstream stores coefficients as Float; 2e-6 admits that rounding, not a new curve.
        val samples = listOf(
            0f to 0f, .05f to .0602928795f, .1f to .1897121965f,
            .2f to .4793829506f, .25f to .6022759023f, .35f to .7829403521f,
            .5f to .9232387461f, .7f to .9845541995f, .9f to .9977538753f,
            .95f to .9987316470f, 1f to .9993313968f,
        )
        samples.forEach { (fraction, value) ->
            assertEquals(value, easing.transform(fraction), .000002f, "EasePage at $fraction")
        }
        assertEquals(0f, easing.transform(0f))
        assertTrue(easing.transform(1f) < 1f, "Do not silently clamp the upstream curve endpoint")
        val values = (0..100).map { easing.transform(it / 100f) }
        assertTrue(values.all { it.isFinite() && it in 0f..1f })
        assertTrue(values.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun pageTweenConsumesTheCurveOverFiveHundredMillisAndFinishesAtExactlyTheTarget() {
        val animation = animation(TweenSpec(MotionTokens.DurPage, easing = MotionTokens.EasePage), 0f, 1f)
        assertEquals(.6022759023f, animation.getValueFromNanos(125_000_000L), .000002f)
        assertEquals(.9232387461f, animation.getValueFromNanos(250_000_000L), .000002f)
        assertEquals(.9845541995f, animation.getValueFromNanos(350_000_000L), .000002f)
        assertFalse(animation.isFinishedFromNanos(499_000_000L))
        assertTrue(animation.isFinishedFromNanos(500_000_000L))
        assertEquals(1f, animation.getValueFromNanos(500_000_000L))
        assertEquals(1f, animation.getValueFromNanos(600_000_000L))
    }

    @Test
    fun generalSpringTokensMatchSectionThreePointThree() {
        assertSpring("SpringPressIn", MotionTokens.SpringPressIn, 1f, 700f)
        assertSpring("SpringPressOut", MotionTokens.SpringPressOut, .9f, 700f)
        assertSpring("SpringPressOutBouncy", MotionTokens.SpringPressOutBouncy, .62f, 700f)
        assertSpring("SpringSpatialFast", MotionTokens.SpringSpatialFast, .9f, 700f)
        assertSpring("SpringSpatial", MotionTokens.SpringSpatial, .9f, 322f)
        assertSpring("SpringSpatialLarge", MotionTokens.SpringSpatialLarge, .95f, 200f)
    }

    @Test
    fun amplitudeTokensMatchSectionThreePointFour() {
        val expected = listOf(
            Triple("ScalePressSmall", .88f, MotionTokens.ScalePressSmall),
            Triple("ScalePress", .96f, MotionTokens.ScalePress),
            Triple("ScalePressLarge", .98f, MotionTokens.ScalePressLarge),
            Triple("ScaleEnterFrom", .96f, MotionTokens.ScaleEnterFrom),
            Triple("AlphaPressDim", .82f, MotionTokens.AlphaPressDim),
            Triple("AlphaDisabled", .55f, MotionTokens.AlphaDisabled),
            Triple("AlphaPending", .5f, MotionTokens.AlphaPending),
            Triple("TintPress", .08f, MotionTokens.TintPress),
            Triple("ScalePopFrom", .6f, MotionTokens.ScalePopFrom),
        )
        expected.forEach { (name, value, actual) -> assertEquals(value, actual, name) }
        assertEquals(8.dp, MotionTokens.OffsetSmall)
    }

    @Test
    fun retainedRootPagerIsNotRoundedToTheGeneralSpatialSpring() {
        val retained = MotionTokens.SpringRootPager
        assertSpring("SpringRootPager", retained, .9f, 322.27f, .5f)
        val current = appRootPagerContract()
        assertEquals(current.springStiffness, retained.stiffness)
        assertEquals(current.springDampingRatio, retained.dampingRatio)
        assertEquals(current.springVisibilityThreshold, retained.visibilityThreshold)
        assertNotEquals(MotionTokens.SpringSpatial.stiffness, retained.stiffness)
    }

    @Test
    fun retainedPhoneAndKeypadSpringsIncludeTheirExactVisibilityThresholds() {
        assertSpring("SpringPhonePressIn", MotionTokens.SpringPhonePressIn, 1f, 700f, .0001f)
        assertSpring("SpringPhonePressOutBouncy", MotionTokens.SpringPhonePressOutBouncy, .62f, 700f, .0001f)
        assertSpring("SpringInCallKeypad", MotionTokens.SpringInCallKeypad, 1f, 500f, .001f)
    }

    @Test
    fun retainedLiquidDragParametersAreRegisteredWithoutSubstitution() {
        assertSpring("SpringBottomBarValue", MotionTokens.SpringBottomBarValue, 1f, 1000f, .001f)
        // Exact Float result of the current component's .001f * 10f, not rounded .01f.
        assertSpring("SpringBottomBarVelocity", MotionTokens.SpringBottomBarVelocity, .5f, 300f, .010000001f)
        assertSpring("SpringBottomBarPress", MotionTokens.SpringBottomBarPress, 1f, 1000f, .001f)
        assertSpring("SpringBottomBarScaleX", MotionTokens.SpringBottomBarScaleX, .6f, 250f, .001f)
        assertSpring("SpringBottomBarScaleY", MotionTokens.SpringBottomBarScaleY, .7f, 250f, .001f)
        assertSpring("SpringBottomBarRubberBand", MotionTokens.SpringBottomBarRubberBand, 1f, 300f, .5f)
        assertEquals(.001f, MotionTokens.BottomBarValueVisibilityThreshold)
        assertEquals(10f, MotionTokens.BottomBarVelocityVisibilityMultiplier)
        assertEquals(5f, MotionTokens.BottomBarVelocityRestThreshold)
        assertEquals(.025f, MotionTokens.BottomBarReleaseProximityFraction)
        assertEquals(1f, MotionTokens.ScaleBottomBarInitial)
        assertEquals(1.3928572f, MotionTokens.ScaleBottomBarPressed)
        assertEquals(4.dp, MotionTokens.BottomBarRubberBand)
        assertEquals(floatingBubbleBarContract().pressedScale, MotionTokens.ScaleBottomBarPressed)
        assertEquals(floatingBubbleBarContract().rubberBandDp.dp, MotionTokens.BottomBarRubberBand)
        assertSame(EaseOut, MotionTokens.EaseBottomBarRubberBand)
    }

    @Test
    fun retainedContextMenuTimingAndCurvesAreNotReplacedByGenericTiers() {
        assertEquals(260, MotionTokens.DurContextEnter)
        assertEquals(170, MotionTokens.DurContextExit)
        val enter = MotionTokens.EaseContextEnter
        val exit = MotionTokens.EaseContextExit
        listOf(.25f, .5f, .75f).zip(listOf(.76728430f, .94607946f, .99110819f)).forEach { (fraction, value) ->
            assertEquals(value, enter.transform(fraction), .0002f)
        }
        listOf(.25f, .5f, .75f).zip(listOf(.60722036f, .87783360f, .97548007f)).forEach { (fraction, value) ->
            assertEquals(value, exit.transform(fraction), .0002f)
        }
        assertEquals(0f, enter.transform(0f))
        assertEquals(1f, enter.transform(1f))
        assertEquals(0f, exit.transform(0f))
        assertEquals(1f, exit.transform(1f))
    }

    @Test
    fun retainedBottomModeAndPressTimingMatchCurrentComponentContracts() {
        val current = bottomChromeTransitionContract()
        assertEquals(220, MotionTokens.DurBottomBarMode)
        assertEquals(180, MotionTokens.DurBottomBarPress)
        assertEquals(0, MotionTokens.DurBottomBarIndicator, "The indicator is spring-owned, not a 180ms tween")
        assertEquals(current.modeDurationMillis, MotionTokens.DurBottomBarMode)
        assertEquals(current.selectedPressDurationMillis, MotionTokens.DurBottomBarPress)
        assertEquals(current.selectedIndicatorDurationMillis, MotionTokens.DurBottomBarIndicator)
    }

    @Test
    fun overscrollMetadataKeepsPinnedLibraryOwnershipAndActualParameters() {
        // §3.3's S0 source correction replaces the incorrect 280 reference with metadata.
        // Fixed SpringUtils.kt and the actual 0.9.3 SpringMath agree; the library owns physics.
        assertTrue(MotionTokens.OverscrollLibraryOwned)
        assertEquals(1f, MotionTokens.OverscrollDampingRatio)
        assertEquals(.4f, MotionTokens.OverscrollStandardPeriod)
        assertEquals(.55f, MotionTokens.OverscrollHighVelocityPeriod)
        assertEquals(5000f, MotionTokens.OverscrollHighVelocityThreshold)
        val source = readRequired("MotionTokens.kt")
        assertTrue(source.contains("miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/utils/SpringUtils.kt"))
        assertFalse(source.contains("SpringOverscrollReference"))
    }

    @Test
    fun crossfadeIsAConsumableSimultaneousAlphaSpecWithoutGeometryMotion() {
        val mode = MotionTokens.Crossfade
        assertEquals(200, mode.durationMillis)
        val spec = mode.alphaSpec
        assertTween(spec, 200, 0, MotionTokens.EaseStandard)
        assertEquals(1f, value(spec, 1f, 0f, 0))
        assertEquals(0f, value(spec, 0f, 1f, 0))
        assertEquals(.12216640f, value(spec, 1f, 0f, 100), .0002f)
        assertEquals(.87783360f, value(spec, 0f, 1f, 100), .0002f)
        assertEquals(0f, value(spec, 1f, 0f, 200))
        assertEquals(1f, value(spec, 0f, 1f, 200))
        val source = readRequired("MotionTokens.kt")
        val start = source.indexOf("internal data class CrossfadeMotionToken(")
        assertTrue(start >= 0, "Missing CrossfadeMotionToken declaration")
        val end = source.indexOf("internal data class FadeThroughMotionToken(", start)
        assertTrue(end > start, "Missing crossfade declaration boundary")
        val declaration = source.substring(start, end)
        // C-I04: both immutable and mutable geometry fields must remain forbidden.
        val geometryField = Regex("(?i)\\b(?:val|var)\\s+\\w*(?:scale|offset)\\w*\\b")
        assertTrue(geometryField.containsMatchIn("val scale: Float"))
        assertTrue(geometryField.containsMatchIn("var scale: Float"))
        assertTrue(geometryField.containsMatchIn("val offset: Float"))
        assertTrue(geometryField.containsMatchIn("var offset: Float"))
        assertFalse(geometryField.containsMatchIn(declaration), "Crossfade must not expose geometry fields")
    }

    @Test
    fun fadeThroughExposesNonOverlappingLinearAlphaSegmentsAndDelayedScaleSpec() {
        val mode = MotionTokens.FadeThrough
        assertEquals(300, mode.durationMillis)
        assertEquals(.96f, mode.incomingScaleFrom)
        val outgoing = mode.outgoingAlphaSpec
        val incoming = mode.incomingAlphaSpec
        val scale = mode.incomingScaleSpec
        assertTween(outgoing, 90, 0, MotionTokens.EaseLinear)
        assertTween(incoming, 210, 90, MotionTokens.EaseLinear)
        assertTween(scale, 210, 90, MotionTokens.EaseEnter)
        assertEquals(.5f, value(outgoing, 1f, 0f, 45), .000001f)
        assertEquals(0f, value(incoming, 0f, 1f, 45))
        assertEquals(.96f, value(scale, .96f, 1f, 45))
        assertEquals(0f, value(outgoing, 1f, 0f, 90))
        assertEquals(0f, value(incoming, 0f, 1f, 90))
        assertEquals(.96f, value(scale, .96f, 1f, 90))
        assertEquals(.5f, value(incoming, 0f, 1f, 195), .000001f)
        assertEquals(.99800990f, value(scale, .96f, 1f, 195), .00002f)
        assertEquals(0f, value(outgoing, 1f, 0f, 300))
        assertEquals(1f, value(incoming, 0f, 1f, 300))
        assertEquals(1f, value(scale, .96f, 1f, 300))
    }

    @Test
    fun numericRollExposesBothDirectionsAndAConsumableSharedAlphaAndOffsetSpec() {
        val mode = MotionTokens.NumericRoll
        assertEquals(200, mode.durationMillis)
        assertEquals(8.dp, mode.offset)
        val spec = mode.animationSpec
        assertTween(spec, 200, 0, MotionTokens.EaseStandard)
        assertEquals((-8).dp, mode.outgoingOffset(increasing = true))
        assertEquals(8.dp, mode.incomingOffset(increasing = true))
        assertEquals(8.dp, mode.outgoingOffset(increasing = false))
        assertEquals((-8).dp, mode.incomingOffset(increasing = false))
        // Positive y is down: larger numbers move up; smaller numbers move down.
        assertEquals(-7.0226688f, value(spec, 0f, -8f, 100), .002f)
        assertEquals(.9773312f, value(spec, 8f, 0f, 100), .002f)
        assertEquals(7.0226688f, value(spec, 0f, 8f, 100), .002f)
        assertEquals(-.9773312f, value(spec, -8f, 0f, 100), .002f)
        assertEquals(0f, value(spec, 1f, 0f, 200))
        assertEquals(1f, value(spec, 0f, 1f, 200))
        assertEquals(-8f, value(spec, 0f, -8f, 200))
        assertEquals(0f, value(spec, 8f, 0f, 200))
    }

    private fun assertSpring(
        name: String, spec: SpringSpec<Float>, damping: Float, stiffness: Float, threshold: Float? = null,
    ) {
        assertEquals(damping, spec.dampingRatio, name)
        assertEquals(stiffness, spec.stiffness, name)
        assertEquals(threshold, spec.visibilityThreshold, name)
    }

    private fun assertTween(spec: TweenSpec<Float>, duration: Int, delay: Int, easing: Easing) {
        assertEquals(duration, spec.durationMillis)
        assertEquals(delay, spec.delay)
        assertSame(easing, spec.easing)
    }

    private fun animation(spec: TweenSpec<Float>, from: Float, to: Float) =
        TargetBasedAnimation(spec, Float.VectorConverter, from, to)

    private fun value(spec: TweenSpec<Float>, from: Float, to: Float, millis: Int): Float =
        animation(spec, from, to).getValueFromNanos(millis * 1_000_000L)

    private fun readRequired(filename: String): String {
        val relative = "shared/src/commonMain/kotlin/com/vowifi/sw/manager/ui/$filename"
        val here = File("").absoluteFile
        val candidates = listOf(
            File(here, relative), File(here, "../$relative"), File(here, "../../$relative"),
            File(here, "app/manager/$relative"),
        )
        // Locate the existing UI directory first, so missing MotionTokens.kt is the real RED.
        val file = candidates.firstOrNull { File(it.parentFile, "AppRootPager.kt").isFile } ?: candidates[1]
        assertTrue(file.isFile, "S0 centralized production MotionTokens.kt is missing at ${file.absolutePath}")
        return file.readText()
    }
}
