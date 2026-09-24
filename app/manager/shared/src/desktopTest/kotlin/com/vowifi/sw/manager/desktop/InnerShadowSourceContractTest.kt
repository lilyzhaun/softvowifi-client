package com.vowifi.sw.manager.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InnerShadowSourceContractTest {
    @Test
    fun sourceResolvesInnerShadowPixelsBeforeRecordingGraphicsLayer() {
        val source = readRequired("shared/src/commonMain/kotlin/com/vowifi/sw/manager/ui/liquid/InnerShadow.kt")
        val recordStart = source.indexOf("layer.record {")
        val recordBlock = source.substring(recordStart, source.indexOf("\n        }", recordStart) + 10)
        val radius = source.indexOf("val radius = shadow.radius.toPx()")
        val offsetX = source.indexOf("val offsetX = shadow.offset.x.toPx()")
        val offsetY = source.indexOf("val offsetY = shadow.offset.y.toPx()")

        assertTrue(recordStart >= 0)
        assertTrue(radius in 0 until recordStart)
        assertTrue(offsetX in 0 until recordStart)
        assertTrue(offsetY in 0 until recordStart)
        assertTrue(recordBlock.contains("drawContext.canvas.translate(offsetX, offsetY)"))
        assertFalse(recordBlock.contains(".toPx()"))
    }

    private fun readRequired(relativeFromManager: String): String {
        val file = locate(relativeFromManager)
        assertTrue(file.isFile, "missing $relativeFromManager at ${file.absolutePath}")
        return file.readText()
    }

    private fun locate(relativeFromManager: String): File {
        val here = File("").absoluteFile
        val candidates = listOf(
            File(here, relativeFromManager),
            File(here, "../$relativeFromManager"),
            File(here, "../../$relativeFromManager"),
            File(here, "app/manager/$relativeFromManager"),
        )
        return candidates.firstOrNull(File::isFile) ?: candidates[1]
    }
}
