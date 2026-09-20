package com.vowifi.sw.manager.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InnerShadowSourceContractTest {
    @Test
    fun nativeReplacementDoesNotRetainRetiredShadowOrShaderSources() {
        val ui = locate("shared/src/commonMain/kotlin/com/vowifi/sw/manager/ui/AppBottomChrome.kt").parentFile
        listOf(
            "SlidingFloatingBottomBar.kt", "SlidingFloatingBottomBarLayers.kt", "SlidingFloatingBottomBarVisuals.kt",
            "DampedDragAnimation.kt", "InspectDragGestures.kt", "liquid/CombinedBackdrop.kt",
            "liquid/InnerShadow.kt", "liquid/Vibrancy.kt", "liquid/InteractiveHighlight.kt", "liquid/Lens.kt",
        ).forEach { assertFalse(File(ui, it).exists(), "Retired implementation remains: $it") }
        val source = readRequired("shared/src/commonMain/kotlin/com/vowifi/sw/manager/ui/NativeFloatingNavigation.kt")
        assertTrue(source.contains("import top.yukonga.miuix.kmp.basic.NavigationBar"))
        listOf("RuntimeShader", "drawBackdrop", "DampedDrag", "ui.liquid", "pointerInput").forEach {
            assertFalse(source.contains(it), "Native adapter must not implement liquid/drag effects: $it")
        }
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
