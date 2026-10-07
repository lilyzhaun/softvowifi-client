package com.vowifi.sw.manager.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
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

private fun communicationGlyph(name: String, draw: PathBuilder.() -> Unit) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
    }.build()

internal val CommunicationChevronDown = communicationGlyph("Choose line") {
    moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
}
internal val CommunicationCompose = communicationGlyph("Compose SMS") {
    moveTo(15f, 4f); lineTo(20f, 9f); lineTo(9f, 20f); lineTo(4f, 21f); lineTo(5f, 16f); close()
    moveTo(13f, 6f); lineTo(18f, 11f)
}
internal val CommunicationSendUp = communicationGlyph("Send SMS") {
    moveTo(12f, 19f); lineTo(12f, 5f); moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f)
}
internal val CommunicationIncoming = communicationGlyph("Incoming call") {
    moveTo(18f, 6f); lineTo(6f, 18f); moveTo(6f, 9f); lineTo(6f, 18f); lineTo(15f, 18f)
}
internal val CommunicationOutgoing = communicationGlyph("Outgoing call") {
    moveTo(6f, 18f); lineTo(18f, 6f); moveTo(9f, 6f); lineTo(18f, 6f); lineTo(18f, 15f)
}
internal val CommunicationPerson = communicationGlyph("Unknown contact") {
    moveTo(12f, 3f); curveTo(18f, 3f, 18f, 12f, 12f, 12f); curveTo(6f, 12f, 6f, 3f, 12f, 3f); close()
    moveTo(4f, 21f); lineTo(4f, 18f); curveTo(4f, 13f, 20f, 13f, 20f, 18f); lineTo(20f, 21f); close()
}
internal val CommunicationSearch = communicationGlyph("Search") {
    moveTo(10f, 3f); curveTo(19f, 3f, 19f, 17f, 10f, 17f); curveTo(1f, 17f, 1f, 3f, 10f, 3f); close()
    moveTo(15f, 15f); lineTo(21f, 21f)
}
internal val CommunicationCheck = communicationGlyph("Selected") {
    moveTo(4f, 12f); lineTo(9f, 17f); lineTo(20f, 6f)
}
internal val CommunicationCopy = communicationGlyph("Copy") {
    moveTo(8f, 7f); lineTo(20f, 7f); lineTo(20f, 21f); lineTo(8f, 21f); close()
    moveTo(16f, 7f); lineTo(16f, 3f); lineTo(4f, 3f); lineTo(4f, 17f); lineTo(8f, 17f)
}
internal val CommunicationTrash = communicationGlyph("Clear") {
    moveTo(4f, 6f); lineTo(20f, 6f); moveTo(9f, 6f); lineTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 6f)
    moveTo(6f, 6f); lineTo(7f, 21f); lineTo(17f, 21f); lineTo(18f, 6f); moveTo(10f, 10f); lineTo(10f, 17f); moveTo(14f, 10f); lineTo(14f, 17f)
}
internal val CommunicationSim = communicationGlyph("SIM") {
    moveTo(5f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f); lineTo(5f, 21f); close()
    moveTo(8f, 12f); lineTo(16f, 12f); lineTo(16f, 18f); lineTo(8f, 18f); close()
    moveTo(12f, 12f); lineTo(12f, 18f); moveTo(8f, 15f); lineTo(16f, 15f)
}
