package com.vowifi.sw.manager.ui

enum class BottomChromeMode {
    FLOATING,
    NORMAL,
}

internal data class AppChromeSpec(
    val bottomMode: BottomChromeMode,
    val effectiveBlur: Boolean,
    val effectiveLiquidGlass: Boolean,
    val predictiveBackEnabled: Boolean,
    val hapticsEnabled: Boolean,
    val opaqueFallback: Boolean,
    val blurRadius: Float,
    val motionDurationMillis: Int,
    val pressedScale: Float,
    val communicationLiquidGlass: Boolean = false,
)

internal fun appChromeSpec(
    settings: AppearanceSettings,
    blurSupported: Boolean,
): AppChromeSpec {
    val blur = effectiveBlur(settings, blurSupported)
    val liquid = effectiveLiquidGlass(settings, blurSupported)
    return AppChromeSpec(
        bottomMode = if (settings.floatingNavigation) BottomChromeMode.FLOATING else BottomChromeMode.NORMAL,
        effectiveBlur = blur,
        effectiveLiquidGlass = liquid,
        predictiveBackEnabled = settings.predictiveBackRequested,
        hapticsEnabled = settings.hapticsEnabled,
        opaqueFallback = !blur,
        blurRadius = 25f,
        motionDurationMillis = 180,
        pressedScale = 0.96f,
        communicationLiquidGlass = settings.liquidGlassRequested && blur,
    )
}

internal data class BottomChromeTransitionContract(
    val modeDurationMillis: Int,
    val selectedIndicatorDurationMillis: Int,
    val selectedPressDurationMillis: Int,
    val stableReservedInset: Boolean,
)

internal fun bottomChromeTransitionContract(): BottomChromeTransitionContract =
    BottomChromeTransitionContract(
        modeDurationMillis = 220,
        selectedIndicatorDurationMillis = 0,
        selectedPressDurationMillis = 180,
        stableReservedInset = true,
    )
