package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/** S1 §4.4/4.6/5.1 exact component values, including the user's explicit Round exception. */
class ManagerPressMotionSpecTest {
    @Test fun styleTargetsAreTypedAndDoNotPaintOpenRowsOrCards() {
        val expected = mapOf(
            ManagerPressStyle.Icon to Triple(.88f, 1f, .08f),
            ManagerPressStyle.Solid to Triple(.96f, 1f, .08f),
            ManagerPressStyle.Round to Triple(.96f, 1f, .08f),
            ManagerPressStyle.Card to Triple(.98f, 1f, 0f),
            ManagerPressStyle.CardRow to Triple(.96f, 1f, 0f),
            ManagerPressStyle.Content to Triple(1f, .82f, 0f),
        )
        expected.forEach { (style, values) ->
            val profile = managerPressMotionProfile(style)
            assertEquals(values.first, profile.pressedScale, "$style scale")
            assertEquals(values.second, profile.pressedAlpha, "$style alpha")
            assertEquals(values.third, profile.pressedTint, "$style tint")
        }
    }

    @Test fun roundReleaseAndCancelPreserveExactSpecsWhileOtherScaleCategoriesUsePointNine() {
        ManagerPressStyle.entries.forEach { style ->
            val profile = managerPressMotionProfile(style)
            assertSpring(profile.pressScaleSpec, 1f, 700f, .0001f)
            if (style == ManagerPressStyle.Round) {
                assertSpring(profile.releaseScaleSpec, .62f, 700f, .0001f)
                assertSpring(profile.cancelScaleSpec, 1f, 700f, .0001f)
                assertSame(MotionTokens.SpringPhonePressIn, profile.cancelScaleSpec)
                assertSame(MotionTokens.SpringPhonePressOutBouncy, profile.releaseScaleSpec)
            } else {
                assertSpring(profile.releaseScaleSpec, .9f, 700f, .0001f)
                assertSpring(profile.cancelScaleSpec, .9f, 700f, .0001f)
            }
        }
    }

    @Test fun alphaAndTintSpecsAreConsumableWithIndependentInteriorSamplesAndExactEndpoints() {
        assertEquals(90, MotionTokens.PressMinimumVisibleMillis)
        assertEquals(.0001f, MotionTokens.PressScaleVisibilityThreshold)
        val profile = managerPressMotionProfile(ManagerPressStyle.Content)
        assertEquals(150, profile.alphaSpec.durationMillis)
        assertEquals(0, profile.alphaSpec.delay)
        assertSame(MotionTokens.EaseStandard, profile.alphaSpec.easing)
        val alpha = TargetBasedAnimation(profile.alphaSpec, Float.VectorConverter, 1f, .82f)
        // Independent cubic(.2,0,0,1) midpoint is .877833604810 (the S0 reference sample).
        assertEquals(1f, alpha.getValueFromNanos(0L))
        assertEquals(.84198995f, alpha.getValueFromNanos(75_000_000L), .0002f)
        assertEquals(.82f, alpha.getValueFromNanos(150_000_000L))
        assertEquals(100, profile.tintInSpec.durationMillis)
        assertEquals(150, profile.tintOutSpec.durationMillis)
        assertEquals(0, profile.tintInSpec.delay)
        assertEquals(0, profile.tintOutSpec.delay)
        assertSame(MotionTokens.EaseStandard, profile.tintInSpec.easing)
        assertSame(MotionTokens.EaseStandard, profile.tintOutSpec.easing)
        val tintIn = TargetBasedAnimation(profile.tintInSpec, Float.VectorConverter, 0f, .08f)
        val tintOut = TargetBasedAnimation(profile.tintOutSpec, Float.VectorConverter, .08f, 0f)
        assertEquals(.07022669f, tintIn.getValueFromNanos(50_000_000L), .00002f)
        assertEquals(.08f, tintIn.getValueFromNanos(100_000_000L))
        assertEquals(.00977331f, tintOut.getValueFromNanos(75_000_000L), .00002f)
        assertEquals(0f, tintOut.getValueFromNanos(150_000_000L))
    }

    @Test fun dialAndLongPressActionsHaveSeparateMappingsAndDisabledActionsRemainUnaccepted() {
        assertEquals(HapticFeedbackType.VirtualKey, managerActionFeedback(ManagerAction.DialKey).hapticType)
        assertEquals(HapticFeedbackType.LongPress, managerActionFeedback(ManagerAction.LongPress).hapticType)
        assertEquals(HapticFeedbackType.ContextClick, managerActionFeedback(ManagerAction.ContextAction).hapticType)
        assertEquals(HapticFeedbackType.ContextClick, managerActionFeedback(ManagerAction.Navigate).hapticType)
        listOf(ManagerAction.DialKey, ManagerAction.LongPress).forEach { action ->
            val disabled = managerActionFeedback(action, enabled = false)
            assertFalse(disabled.accepted)
            assertEquals(null, disabled.hapticType)
        }
    }

    private fun assertSpring(spec: SpringSpec<Float>, damping: Float, stiffness: Float, threshold: Float) {
        assertEquals(damping, spec.dampingRatio)
        assertEquals(stiffness, spec.stiffness)
        assertEquals(threshold, spec.visibilityThreshold)
    }
}
