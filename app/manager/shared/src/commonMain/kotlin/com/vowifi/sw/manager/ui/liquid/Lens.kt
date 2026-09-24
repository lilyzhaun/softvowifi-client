// SPDX-License-Identifier: GPL-3.0-only
// Closely adapted from KernelSU liquid/Lens.kt @ befbcd388bb6c10befd3645311ad08e556d362ac
// and InstallerX-Revived liquid/Lens.kt @ 11f15e4740c21bbed3e3156ceeab643683ed1fcb.
package com.vowifi.sw.manager.ui.liquid

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceAtMost
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect

internal fun BackdropEffectScope.lens(
    refractionHeight: Float,
    refractionAmount: Float,
    depthEffect: Boolean = false,
    chromaticAberration: Float = 0f,
) {
    if (!isRuntimeShaderSupported() || refractionHeight <= 0f || refractionAmount <= 0f) return
    if (padding < refractionAmount) padding = refractionAmount
    val radii = roundedRectCornerRadii() ?: return
    val scale = downscaleFactor.coerceAtLeast(1).toFloat()
    val dispersed = chromaticAberration > 0f
    runtimeShaderEffect(
        key = if (dispersed) "SoftVoWiFiLensDispersion" else "SoftVoWiFiLens",
        shaderString = if (dispersed) LensWithDispersionShader else LensShader,
        uniformShaderName = "content",
    ) {
        setFloatUniform("size", size.width / scale, size.height / scale)
        setFloatUniform("offset", -padding / scale, -padding / scale)
        setFloatUniform("cornerRadii", FloatArray(radii.size) { radii[it] / scale })
        setFloatUniform("refractionHeight", refractionHeight / scale)
        setFloatUniform("refractionAmount", -refractionAmount / scale)
        setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
        if (dispersed) setFloatUniform("chromaticAberration", chromaticAberration)
    }
}

private fun BackdropEffectScope.roundedRectCornerRadii(): FloatArray? {
    val rounded = shape as? CornerBasedShape ?: return null
    val maxRadius = size.minDimension / 2f
    val ltr = layoutDirection == LayoutDirection.Ltr
    val topLeft = if (ltr) rounded.topStart else rounded.topEnd
    val topRight = if (ltr) rounded.topEnd else rounded.topStart
    val bottomRight = if (ltr) rounded.bottomEnd else rounded.bottomStart
    val bottomLeft = if (ltr) rounded.bottomStart else rounded.bottomEnd
    return floatArrayOf(
        topLeft.toPx(size, this).fastCoerceAtMost(maxRadius),
        topRight.toPx(size, this).fastCoerceAtMost(maxRadius),
        bottomRight.toPx(size, this).fastCoerceAtMost(maxRadius),
        bottomLeft.toPx(size, this).fastCoerceAtMost(maxRadius),
    )
}

private const val RoundedRectSdf = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) return coord.y <= 0.0 ? radii.y : radii.z;
    return coord.y <= 0.0 ? radii.x : radii.w;
}
float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    return length(max(cornerCoord, 0.0)) - radius + min(max(cornerCoord.x, cornerCoord.y), 0.0);
}
float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) return sign(coord) * normalize(max(cornerCoord, 0.0));
    float gradX = step(cornerCoord.y, cornerCoord.x);
    return sign(coord) * float2(gradX, 1.0 - gradX);
}
float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }
"""

private const val LensShader = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
$RoundedRectSdf
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centered = (coord + offset) - halfSize;
    float sd = sdRoundedRect(centered, halfSize, radiusAt(coord, cornerRadii));
    if (-sd >= refractionHeight) return content.eval(coord);
    sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float radius = min(radiusAt(coord, cornerRadii) * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centered, halfSize, radius) + depthEffect * normalize(centered));
    return content.eval(coord + d * grad);
}
"""

private const val LensWithDispersionShader = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;
$RoundedRectSdf
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centered = (coord + offset) - halfSize;
    float sd = sdRoundedRect(centered, halfSize, radiusAt(coord, cornerRadii));
    if (-sd >= refractionHeight) return content.eval(coord);
    sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float radius = min(radiusAt(coord, cornerRadii) * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centered, halfSize, radius) + depthEffect * normalize(centered));
    float2 refracted = coord + d * grad;
    float2 dispersed = d * grad * chromaticAberration * ((centered.x * centered.y) / (halfSize.x * halfSize.y));
    half4 red = content.eval(refracted + dispersed);
    half4 green = content.eval(refracted);
    half4 blue = content.eval(refracted - dispersed);
    return half4(red.r, green.g, blue.b, (red.a + green.a + blue.a) / 3.0);
}
"""
