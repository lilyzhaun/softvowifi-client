// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Point motion/bounds adapted from compose-miuix-ui/miuix
// a1ce560e32fce2a5b4b66cc5fc20c087ac0e9f18 example/component/effect/BgEffectPainter.kt.
// Changes: one OS3 painter, reusable buffers, draw-owned uniforms and continuous color interpolation.
package com.vowifi.sw.manager.ui

import androidx.compose.ui.graphics.drawscope.DrawScope
import top.yukonga.miuix.kmp.blur.RuntimeShader
import top.yukonga.miuix.kmp.blur.asBrush
import kotlin.math.cos
import kotlin.math.sin

internal class AboutCloudPainter(private val palette: AboutCloudPalette) {
    private val shader = RuntimeShader(AboutCloudShader).apply {
        setFloatUniform("uPoints", AboutCloudPalette.Points)
        setFloatUniform("uSaturateOffset", palette.saturation)
        setFloatUniform("uLightOffset", palette.light)
    }
    private val points = FloatArray(8)
    private val colors = FloatArray(16)
    private val resolution = FloatArray(2)
    private val bound = FloatArray(4)

    fun draw(scope: DrawScope, time: Float, alpha: Float) = with(scope) {
        if (size.width <= 0f || size.height <= 0f || alpha <= 0f) return@with
        resolution[0] = size.width; resolution[1] = size.height
        bound[0] = 0f; bound[2] = 1f
        val region = if (size.width <= size.height) .5f else size.width / size.height
        bound[1] = .75f - region / 2f; bound[3] = region
        shader.setFloatUniform("uResolution", resolution)
        shader.setFloatUniform("uBound", bound)
        shader.setFloatUniform("uAnimTime", time)
        for (i in 0 until 4) {
            val x = AboutCloudPalette.Points[i * 3]
            val y = AboutCloudPalette.Points[i * 3 + 1]
            points[i * 2] = x + sin(time + y) * palette.pointOffset
            points[i * 2 + 1] = y + cos(time + points[i * 2]) * palette.pointOffset
        }
        val stage = time / palette.period
        val cycle = stage.toInt()
        val fraction = stage - cycle
        val smooth = fraction * fraction * (3f - 2f * fraction)
        fun frame(index: Int) = palette.colors[when (index.mod(4)) { 1 -> 0; 3 -> 2; else -> 1 }]
        val from = frame(cycle); val to = frame(cycle + 1)
        for (i in colors.indices) colors[i] = from[i] + (to[i] - from[i]) * smooth
        shader.setFloatUniform("uPointsAnim", points)
        shader.setFloatUniform("uColors", colors)
        // Skiko asBrush snapshots uniforms; Android returns its existing mutable brush.
        // Request it after the uniform updates so both targets draw this frame's cloud.
        drawRect(shader.asBrush(), alpha = alpha)
    }
}
