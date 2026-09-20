package com.vowifi.sw.manager.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val CommunicationBackspace = ImageVector.Builder("Backspace", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
        moveTo(9f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(9f, 19f)
        lineTo(2f, 12f); close()
        moveTo(12f, 9f); lineTo(18f, 15f)
        moveTo(18f, 9f); lineTo(12f, 15f)
    }
}.build()
