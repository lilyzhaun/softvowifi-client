package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

internal data class SoftVoWiFiChromeEnvironment(
    val spec: AppChromeSpec,
    val backdrop: LayerBackdrop?,
)

internal val LocalSoftVoWiFiChromeEnvironment = staticCompositionLocalOf {
    SoftVoWiFiChromeEnvironment(
        spec = appChromeSpec(AppearanceSettings.Default, blurSupported = false),
        backdrop = null,
    )
}

@Composable
internal fun rememberSoftVoWiFiBackdrop(spec: AppChromeSpec): LayerBackdrop? {
    if (!spec.effectiveBlur && !spec.effectiveLiquidGlass) return null
    val surface = LocalSoftVoWiFiColors.current.canvas.copy(alpha = 1f)
    return rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
}
