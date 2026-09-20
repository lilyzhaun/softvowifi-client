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
    val Speaker = glyph("Speaker") {
        moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f)
        lineTo(7f, 15f); lineTo(3f, 15f); close()
        moveTo(16f, 8f); curveTo(19f, 10f, 19f, 14f, 16f, 16f)
        moveTo(19f, 5f); curveTo(24f, 9f, 24f, 15f, 19f, 19f)
    }
    val Keypad = glyph("Keypad") {
        for (row in 0..2) for (column in 0..2) {
            val horizontal = 5f + column * 7f
            val vertical = 5f + row * 7f
            moveTo(horizontal - .5f, vertical); lineTo(horizontal + .5f, vertical)
        }
    }
    val End = glyph("End call") {
        moveTo(3f, 12f); curveTo(7f, 6f, 17f, 6f, 21f, 12f)
        lineTo(21f, 16f); lineTo(16f, 16f); lineTo(16f, 12f)
        curveTo(13f, 11f, 11f, 11f, 8f, 12f); lineTo(8f, 16f); lineTo(3f, 16f); close()
    }
    val Delete = glyph("Delete number") {
        moveTo(9f, 5f); lineTo(22f, 5f); lineTo(22f, 19f); lineTo(9f, 19f); lineTo(2f, 12f); close()
        moveTo(12f, 9f); lineTo(18f, 15f); moveTo(18f, 9f); lineTo(12f, 15f)
    }
}
