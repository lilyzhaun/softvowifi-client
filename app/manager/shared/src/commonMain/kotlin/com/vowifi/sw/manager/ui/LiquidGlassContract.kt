package com.vowifi.sw.manager.ui

internal data class LiquidGlassContract(
    val usesVibrancy: Boolean,
    val usesLens: Boolean,
    val usesCombinedBackdrop: Boolean,
    val usesInnerShadow: Boolean,
    val usesInteractiveHighlight: Boolean,
    val usesSpecularHighlight: Boolean,
    val usesTextureBlur: Boolean,
    val velocityDeformationInBackdropLayer: Boolean,
    val horizontalOnlyMotion: Boolean,
    val restContrastAlpha: Float,
    val pagerCaptureCount: Int,
    val barLocalCaptureCount: Int,
    val drawBackdropCallCount: Int,
    val baseBlurRadiusDp: Int,
    val baseLensHeightDp: Int,
    val baseLensAmountDp: Int,
    val baseSurfaceAlpha: Float,
    val indicatorLensHeightDp: Int,
    val indicatorLensAmountDp: Int,
    val indicatorDepthEffect: Boolean,
    val indicatorChromaticAberration: Float,
)

internal fun liquidGlassContract(effectiveLiquidGlass: Boolean): LiquidGlassContract = LiquidGlassContract(
    usesVibrancy = effectiveLiquidGlass,
    usesLens = effectiveLiquidGlass,
    usesCombinedBackdrop = effectiveLiquidGlass,
    usesInnerShadow = effectiveLiquidGlass,
    usesInteractiveHighlight = effectiveLiquidGlass,
    usesSpecularHighlight = effectiveLiquidGlass,
    usesTextureBlur = false,
    velocityDeformationInBackdropLayer = effectiveLiquidGlass,
    horizontalOnlyMotion = true,
    restContrastAlpha = if (effectiveLiquidGlass) 0.10f else 0f,
    pagerCaptureCount = if (effectiveLiquidGlass) 1 else 0,
    barLocalCaptureCount = if (effectiveLiquidGlass) 1 else 0,
    drawBackdropCallCount = if (effectiveLiquidGlass) 3 else 0,
    baseBlurRadiusDp = if (effectiveLiquidGlass) 4 else 0,
    baseLensHeightDp = if (effectiveLiquidGlass) 24 else 0,
    baseLensAmountDp = if (effectiveLiquidGlass) 24 else 0,
    baseSurfaceAlpha = if (effectiveLiquidGlass) 0.40f else 1f,
    indicatorLensHeightDp = if (effectiveLiquidGlass) 10 else 0,
    indicatorLensAmountDp = if (effectiveLiquidGlass) 14 else 0,
    indicatorDepthEffect = effectiveLiquidGlass,
    indicatorChromaticAberration = if (effectiveLiquidGlass) 0.5f else 0f,
)

internal data class LiquidIndicatorVisualState(
    val restContrastAlpha: Float,
    val interactiveFillAlpha: Float,
    val lensHeightDp: Float,
    val lensAmountDp: Float,
    val specularAlpha: Float,
    val innerShadowAlpha: Float,
    val innerShadowRadiusDp: Float,
    val velocityDeformationInBackdropLayer: Boolean,
    val horizontalOnlyMotion: Boolean,
)

internal fun liquidIndicatorVisualState(pressProgress: Float): LiquidIndicatorVisualState {
    val clamped = pressProgress.coerceIn(0f, 1f)
    return LiquidIndicatorVisualState(
        restContrastAlpha = 0.10f * (1f - clamped),
        interactiveFillAlpha = 0.10f * clamped,
        lensHeightDp = 10f * clamped,
        lensAmountDp = 14f * clamped,
        specularAlpha = clamped,
        innerShadowAlpha = clamped,
        innerShadowRadiusDp = 8f * clamped,
        velocityDeformationInBackdropLayer = true,
        horizontalOnlyMotion = true,
    )
}
