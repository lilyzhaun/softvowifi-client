package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop

@Composable
internal fun chromeSurfaceModifier(
    modifier: Modifier,
    spec: AppChromeSpec,
    backdrop: LayerBackdrop?,
    shape: Shape,
    blendAlpha: Float? = null,
): Modifier {
    val renderer = chromeSurfaceFallbackRenderer(spec.effectiveBlur, backdrop != null)
    if (renderer == ChromeBlurRenderer.TRANSPARENT || backdrop == null) {
        return modifier
    }
    val colors = LocalSoftVoWiFiColors.current
    return modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = { blur(spec.blurRadius) },
        onDrawSurface = { drawRect(colors.canvas.copy(alpha = blendAlpha ?: 0.72f)) },
    )
}
