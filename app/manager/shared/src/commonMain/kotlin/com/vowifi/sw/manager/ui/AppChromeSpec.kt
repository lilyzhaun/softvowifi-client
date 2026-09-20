package com.vowifi.sw.manager.ui

enum class BottomChromeMode {
    FLOATING,
    NORMAL,
}

internal data class AppChromeSpec(
    val bottomMode: BottomChromeMode,
    val effectiveBlur: Boolean,
    val predictiveBackEnabled: Boolean,
    val hapticsEnabled: Boolean,
    val opaqueFallback: Boolean,
    val blurRadius: Float,
    val motionDurationMillis: Int,
    val pressedScale: Float,
)

internal fun appChromeSpec(
    settings: AppearanceSettings,
    blurSupported: Boolean,
): AppChromeSpec {
    val blur = effectiveBlur(settings, blurSupported)
    return AppChromeSpec(
        bottomMode = if (settings.floatingNavigation) BottomChromeMode.FLOATING else BottomChromeMode.NORMAL,
        effectiveBlur = blur,
        predictiveBackEnabled = settings.predictiveBackRequested,
        hapticsEnabled = settings.hapticsEnabled,
        opaqueFallback = !blur,
        blurRadius = 25f,
        motionDurationMillis = 180,
        pressedScale = 0.96f,
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
