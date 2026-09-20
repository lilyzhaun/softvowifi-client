package com.vowifi.sw.manager.desktop

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeNavigationSourceClosureTest {
    private val shared = listOf(File("."), File("shared"), File("app/manager/shared"))
        .single { File(it, "src/commonMain/kotlin/com/vowifi/sw/manager/ui/AppBottomChrome.kt").isFile }

    private val retiredNames = listOf(
        "SlidingBottomBarContract", "SlidingBottomBarModel", "FloatingBubbleBarContract",
        "clampBubblePosition", "dragBubblePosition", "settleBubbleIndex", "bubbleTranslationPx",
        "absolutePointerBubblePosition", "rubberBandPanelOffset", "FloatingDragGestureOwner",
        "FloatingRowLayoutContract", "LiquidGlassContract", "liquidIndicatorVisualState", "LiquidIndicatorVisualState",
        "TabClickIndication", "SafeBackdropContract",
        "DampedDragAnimation", "InspectDragGestures", "SlidingFloatingBottomBar",
        "CombinedBackdrop", "InnerShadow", "InteractiveHighlight", "LensShader", "LensKt", "Vibrancy",
        "effectiveLiquidGlass", "AppearanceLiquidGlassSummary", "appearanceLiquidGlassSummary",
    )

    @Test fun productionSourcesDoNotReferenceRetiredNavigationImplementation() {
        val roots = listOf("commonMain", "androidMain", "desktopMain")
        val files = roots.flatMap { File(shared, "src/$it/kotlin").walkTopDown().filter { it.extension == "kt" }.toList() }
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            val source = file.readText()
            retiredNames.forEach { name -> assertFalse(source.contains(name), "${file.name} retains $name") }
        }
    }

    @Test fun compiledProductionJarDoesNotPackageRetiredNavigationClasses() {
        val jars = File(shared, "build/libs").listFiles().orEmpty().filter { it.name.endsWith("-desktop.jar") }
        assertTrue(jars.isNotEmpty(), "desktopJar must be built before this regression test")
        jars.forEach { jar ->
            ZipFile(jar).use { archive ->
                val classes = archive.entries().asSequence().filter { it.name.endsWith(".class") }.map { it.name }.toList()
                assertTrue(classes.any { it.endsWith("/NativeFloatingNavigationKt.class") })
                classes.forEach { entry ->
                    retiredNames.forEach { name -> assertFalse(entry.contains(name), "${jar.name} retains $entry") }
                }
            }
        }
    }

    @Test fun focusedRenderEvidenceStaysInsideBuildReports() {
        val desktopTests = File(shared, "src/desktopTest/kotlin/com/vowifi/sw/manager/desktop")
        listOf("NativeNavigationRenderTest.kt", "ConfigurationPolicyRenderTest.kt", "ObserverSettingsRenderTest.kt").forEach { name ->
            val source = File(desktopTests, name).readText()
            assertTrue(source.contains("build/reports/"), "$name must use build reports")
            assertFalse(source.contains("/var/tmp"), "$name must not overwrite historical evidence")
            assertFalse(source.contains("/tmp/opencode"), "$name must not overwrite historical evidence")
        }
    }
}
