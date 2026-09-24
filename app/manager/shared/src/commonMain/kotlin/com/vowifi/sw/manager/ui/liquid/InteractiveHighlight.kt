// SPDX-License-Identifier: GPL-3.0-only
// Closely adapted from KernelSU miuix/animation/InteractiveHighlight.kt @ befbcd388bb6c10befd3645311ad08e556d362ac
// and InstallerX-Revived animation/InteractiveHighlight.kt @ 11f15e4740c21bbed3e3156ceeab643683ed1fcb.
package com.vowifi.sw.manager.ui.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastCoerceIn
import com.vowifi.sw.manager.ui.inspectBarDragGestures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.RuntimeShader
import top.yukonga.miuix.kmp.blur.asBrush
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported

internal class InteractiveHighlight internal constructor(
    private val supported: Boolean,
    private val animationScope: CoroutineScope,
    private val position: (Size, Offset) -> Offset,
) {
    private val pressProgress = Animatable(0f, 0.001f)
    private val pressPosition = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var startPosition = Offset.Zero
    private val shader by lazy { RuntimeShader(HighlightShader) }

    val modifier: Modifier = if (!supported) Modifier else Modifier.drawWithContent {
        val progress = pressProgress.value
        if (progress > 0f) {
            drawRect(Color.White.copy(alpha = 0.06f * progress), blendMode = BlendMode.Plus)
            val center = position(size, pressPosition.value)
            shader.setFloatUniform("size", size.width, size.height)
            shader.setColorUniform("color", Color.White.copy(alpha = 0.12f * progress))
            shader.setFloatUniform(
                "position",
                center.x.fastCoerceIn(0f, size.width),
                center.y.fastCoerceIn(0f, size.height),
            )
            shader.setFloatUniform("radius", size.minDimension * 1.2f)
            drawRect(shader.asBrush(), blendMode = BlendMode.Plus)
        }
        drawContent()
    }

    val gestureModifier: Modifier = if (!supported) Modifier else Modifier.pointerInput(animationScope) {
        inspectBarDragGestures(
            onDragStart = { down ->
                startPosition = down.position
                animationScope.launch {
                    launch { pressProgress.animateTo(1f, PressSpec) }
                    launch { pressPosition.snapTo(startPosition) }
                }
            },
            onDragEnd = { release() },
            onDragCancel = { release() },
            onDrag = { change, _ -> animationScope.launch { pressPosition.snapTo(change.position) } },
        )
    }

    private fun release() {
        animationScope.launch {
            launch { pressProgress.animateTo(0f, PressSpec) }
            launch { pressPosition.animateTo(startPosition, PositionSpec) }
        }
    }

    companion object {
        val PressSpec = spring(0.5f, 300f, 0.001f)
        val PositionSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)
    }
}

@Composable
internal fun rememberInteractiveHighlight(
    animationScope: CoroutineScope,
    position: (Size, Offset) -> Offset,
): InteractiveHighlight = remember(animationScope, position, isRuntimeShaderSupported()) {
    InteractiveHighlight(isRuntimeShaderSupported(), animationScope, position)
}

private const val HighlightShader = """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;
half4 main(float2 coord) {
    return color * smoothstep(radius, radius * 0.5, distance(coord, position));
}
"""
