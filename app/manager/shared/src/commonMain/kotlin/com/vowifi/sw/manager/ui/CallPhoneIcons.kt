package com.vowifi.sw.manager.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.Back

internal object CallPhoneIcons {
    val Phone = MiuixIcons.Phone
    val Back = MiuixIcons.Back
    private fun glyph(name: String, draw: PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
    }.build()
    val Microphone = glyph("Microphone") {
        moveTo(9f, 6f); curveTo(9f, 2f, 15f, 2f, 15f, 6f); lineTo(15f, 11f)
        curveTo(15f, 15f, 9f, 15f, 9f, 11f); close()
        moveTo(6f, 10f); curveTo(6f, 20f, 18f, 20f, 18f, 10f)
        moveTo(12f, 18f); lineTo(12f, 21f); moveTo(9f, 21f); lineTo(15f, 21f)
    }
    val Minimize = glyph("Minimize call") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
    val Information = glyph("Call information") {
        moveTo(12f, 3f); curveTo(24f, 3f, 24f, 21f, 12f, 21f)
        curveTo(0f, 21f, 0f, 3f, 12f, 3f); close()
        moveTo(12f, 7f); lineTo(12f, 7.1f)
        moveTo(12f, 11f); lineTo(12f, 17f)
    }
    val Clock = glyph("Call duration") {
        moveTo(12f, 3f); curveTo(24f, 3f, 24f, 21f, 12f, 21f)
        curveTo(0f, 21f, 0f, 3f, 12f, 3f); close()
        moveTo(12f, 7f); lineTo(12f, 12f); lineTo(16f, 14f)
    }
    val MicrophoneOff = glyph("Microphone off") {
        moveTo(10f, 3f); curveTo(13f, 1f, 15f, 3f, 15f, 6f); lineTo(15f, 10f)
        moveTo(9f, 9f); lineTo(9f, 11f); curveTo(9f, 14f, 12f, 15f, 14f, 13f)
        moveTo(6f, 10f); curveTo(6f, 18f, 13f, 20f, 17f, 15f)
        moveTo(18f, 10f); lineTo(18f, 11f)
        moveTo(12f, 18f); lineTo(12f, 21f); moveTo(9f, 21f); lineTo(15f, 21f)
        moveTo(3f, 3f); lineTo(21f, 21f)
    }
    val Speaker = glyph("Speaker") {
        moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f)
        lineTo(7f, 15f); lineTo(3f, 15f); close()
        moveTo(16f, 8f); curveTo(19f, 10f, 19f, 14f, 16f, 16f)
        moveTo(19f, 5f); curveTo(24f, 9f, 24f, 15f, 19f, 19f)
    }
    val Keypad = ImageVector.Builder("Keypad", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2.8f, strokeLineCap = StrokeCap.Round) {
        for (row in 0..2) for (column in 0..2) {
            val horizontal = 5f + column * 7f
            val vertical = 5f + row * 7f
            moveTo(horizontal, vertical); lineTo(horizontal + .01f, vertical)
        }
        }
    }.build()
    val End = ImageVector.Builder("End call", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(2.4f, 10.3f); curveTo(7.5f, 5.5f, 16.5f, 5.5f, 21.6f, 10.3f)
            curveTo(22.6f, 11.2f, 22.4f, 12.7f, 21.6f, 13.6f); lineTo(19.8f, 15.1f)
            curveTo(19.2f, 15.7f, 18.2f, 15.5f, 17.8f, 14.9f); lineTo(16.5f, 12.4f)
            curveTo(16.2f, 11.9f, 16.1f, 11.4f, 16.1f, 10.9f)
            curveTo(13.4f, 10f, 10.6f, 10f, 7.9f, 10.9f)
            curveTo(7.9f, 11.4f, 7.8f, 11.9f, 7.5f, 12.4f); lineTo(6.2f, 14.9f)
            curveTo(5.8f, 15.5f, 4.8f, 15.7f, 4.2f, 15.1f); lineTo(2.4f, 13.6f)
            curveTo(1.6f, 12.7f, 1.4f, 11.2f, 2.4f, 10.3f); close()
        }
    }.build()
    val Delete = glyph("Delete number") {
        moveTo(9f, 5f); lineTo(22f, 5f); lineTo(22f, 19f); lineTo(9f, 19f); lineTo(2f, 12f); close()
        moveTo(12f, 9f); lineTo(18f, 15f); moveTo(18f, 9f); lineTo(12f, 15f)
    }
}
