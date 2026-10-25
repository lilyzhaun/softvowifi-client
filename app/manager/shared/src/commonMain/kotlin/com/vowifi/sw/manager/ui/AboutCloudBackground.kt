package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.isActive

/** The occasional About identity owns this loop. Frame time is read only by draw, not composition. */
@Composable
internal fun AboutCloudBackground(
    progress: () -> Float,
    shaderSupported: Boolean = remember { runtimeBlurSupported() },
) {
    val colors = LocalSoftVoWiFiColors.current
    val dark = colors.canvas.luminance() < .5f
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val visible = progress() < 1f
    val playing = shaderSupported && LocalAppRouteActive.current && lifecycle == Lifecycle.State.RESUMED && visible && scale > 0f
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, scale) {
        if (!playing) return@LaunchedEffect
        var previous = withFrameNanos { it }
        val framePeriod = 1_000_000_000L / 60L
        var nextDraw = previous + framePeriod
        while (isActive) {
            withFrameNanos { now ->
                if (now >= nextDraw) {
                    time.floatValue += (now - previous) / 1_000_000_000f / scale
                    previous = now
                    nextDraw += ((now - nextDraw) / framePeriod + 1L) * framePeriod
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val palette = remember(dark, maxWidth >= 600.dp) { AboutCloudPalette.forTheme(dark, maxWidth >= 600.dp) }
        val painter = remember(shaderSupported, palette) { if (shaderSupported) AboutCloudPainter(palette) else null }
        Spacer(Modifier.fillMaxSize().drawWithCache {
            // Unsupported renderers get a static, theme-matched cloud, not an invisible/blank hero.
            val fallback = if (painter == null) List(4) { i ->
                val rgba = palette.colors[1]
                val color = Color(rgba[i * 4], rgba[i * 4 + 1], rgba[i * 4 + 2], rgba[i * 4 + 3])
                Brush.radialGradient(listOf(color, Color.Transparent),
                    center = Offset(size.width * AboutCloudPalette.Points[i * 3], size.height * .5f * (1f - AboutCloudPalette.Points[i * 3 + 1])),
                    radius = size.width.coerceAtLeast(size.height * .5f))
            } else emptyList()
            onDrawBehind {
                drawRect(colors.canvas)
                val alpha = 1f - progress()
                if (painter != null) painter.draw(this, time.floatValue, alpha)
                else fallback.forEach { drawRect(it, alpha = alpha) }
            }
        })
    }
}
