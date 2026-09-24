package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiquidGlassContractTest {
    @Test
    fun effectiveBlurUsesTheCompleteLiquidGlassLayerStack() {
        val contract = liquidGlassContract(effectiveLiquidGlass = true)

        assertTrue(contract.usesVibrancy)
        assertTrue(contract.usesLens)
        assertTrue(contract.usesCombinedBackdrop)
        assertTrue(contract.usesInnerShadow)
        assertTrue(contract.usesInteractiveHighlight)
        assertTrue(contract.usesSpecularHighlight)
        assertFalse(contract.usesTextureBlur)
        assertTrue(contract.velocityDeformationInBackdropLayer)
        assertTrue(contract.horizontalOnlyMotion)
        assertEquals(0.10f, contract.restContrastAlpha)
        assertEquals(1, contract.pagerCaptureCount)
        assertEquals(1, contract.barLocalCaptureCount)
        assertEquals(3, contract.drawBackdropCallCount)
        assertEquals(4, contract.baseBlurRadiusDp)
        assertEquals(24, contract.baseLensHeightDp)
        assertEquals(24, contract.baseLensAmountDp)
        assertEquals(0.40f, contract.baseSurfaceAlpha)
        assertEquals(10, contract.indicatorLensHeightDp)
        assertEquals(14, contract.indicatorLensAmountDp)
        assertTrue(contract.indicatorDepthEffect)
        assertEquals(0.5f, contract.indicatorChromaticAberration)
    }

    @Test
    fun liquidIndicatorAtRestMaintainsThemeContrastAndZeroRefraction() {
        val rest = liquidIndicatorVisualState(pressProgress = 0f)

        assertEquals(0.10f, rest.restContrastAlpha)
        assertEquals(0f, rest.interactiveFillAlpha)
        assertEquals(0f, rest.lensHeightDp)
        assertEquals(0f, rest.lensAmountDp)
        assertEquals(0f, rest.specularAlpha)
        assertEquals(0f, rest.innerShadowAlpha)
        assertEquals(0f, rest.innerShadowRadiusDp)
        assertTrue(rest.velocityDeformationInBackdropLayer)
        assertTrue(rest.horizontalOnlyMotion)
    }

    @Test
    fun liquidIndicatorAtFullPressDeploysFullRefractionAndInteractiveState() {
        val pressed = liquidIndicatorVisualState(pressProgress = 1f)

        assertEquals(0f, pressed.restContrastAlpha)
        assertEquals(0.10f, pressed.interactiveFillAlpha)
        assertEquals(10f, pressed.lensHeightDp)
        assertEquals(14f, pressed.lensAmountDp)
        assertEquals(1f, pressed.specularAlpha)
        assertEquals(1f, pressed.innerShadowAlpha)
        assertEquals(8f, pressed.innerShadowRadiusDp)
        assertTrue(pressed.velocityDeformationInBackdropLayer)
        assertTrue(pressed.horizontalOnlyMotion)
    }

    @Test
    fun opaqueFallbackNeverUsesLiquidEffects() {
        val contract = liquidGlassContract(effectiveLiquidGlass = false)

        assertFalse(contract.usesVibrancy)
        assertFalse(contract.usesLens)
        assertFalse(contract.usesCombinedBackdrop)
        assertFalse(contract.usesInnerShadow)
        assertFalse(contract.usesInteractiveHighlight)
        assertFalse(contract.usesSpecularHighlight)
        assertFalse(contract.usesTextureBlur)
        assertEquals(0, contract.barLocalCaptureCount)
        assertEquals(0, contract.drawBackdropCallCount)
        assertEquals(0, contract.baseBlurRadiusDp)
        assertEquals(0, contract.indicatorLensHeightDp)
        assertFalse(contract.indicatorDepthEffect)
    }
}
