@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class AboutSectionsRenderTest {
    @Test fun aboutUsesSeparateVersionAndDetailsBlocksInsteadOfInlineEngineDiagnostics() = onMotionTestThread { runTest {
        MotionTestEnvironment(testScheduler, 420, 1200) { s4SmsHost {
            SettingsAboutScreen("1.2.3", appVersionCode = 12)
        } }.use { e ->
            repeat(4) { e.advanceFrameBy() }
            val texts = texts(e)
            assertFalse("检查引擎" in texts || "引擎标识" in texts || "运行实例" in texts,
                "关于首页不再嵌入检查按钮和诊断占位，真实引擎信息应在独立子页")
            assertTrue(texts.containsAll(listOf("版本信息", "更多信息", "Root 后端版本", "引擎信息")))
        }
    } }

    @Test fun appVersionAndBuildHaveOneIdentityPresentationWithoutDuplicatePreferenceRows() = onMotionTestThread { runTest {
        MotionTestEnvironment(testScheduler, 420, 1200) { s4SmsHost {
            SettingsAboutScreen("1.2.3", appVersionCode = 12)
        } }.use { e ->
            repeat(4) { e.advanceFrameBy() }
            val texts = texts(e)
            assertFalse("应用版本" in texts || "应用信息" in texts, "已在品牌区表达的信息不再重复做设置行")
            assertEquals(1, texts.count { it.contains("1.2.3") }, "应用版本只出现一次，并保留真实code")
            assertTrue("版本 1.2.3 · code 12" in texts)
        }
    } }

    @Test fun groupedAboutAndEngineSubpageRenderTheirRealControlsAtPhoneSize() = onMotionTestThread { runTest {
        val chrome = appChromeSpec(AppearanceSettings.Default.copy(blurRequested = false), false)
        fun capture(e: MotionTestEnvironment, name: String) {
            val directory = Path.of("/tmp/opencode/about-sections-host-20261026")
            Files.createDirectories(directory)
            e.scene.render(testScheduler.currentTime * 1_000_000L).use { image ->
                requireNotNull(image.encodeToData()).use { Files.write(directory.resolve("$name.png"), it.bytes) }
            }
        }
        var opens = 0
        val diagnostics = EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.WARN, emptyList(),
            ModuleDiagnostics(true, "vowifi_sw", "0.3.48", 51, ModuleMarkers(false, false, false)))
        MotionTestEnvironment(testScheduler, 390, 844) { s4SmsHost {
            AppSubpageScene(AppRoute.SettingsAbout, chrome, onBack = {}) {
                SettingsAboutScreen("0.3.49", 52, diagnostics, onOpenEngineInformation = { opens++ })
            }
        } }.use { e ->
            repeat(6) { e.advanceFrameBy() }
            assertTrue("App 与 Root 后端版本不一致" in texts(e))
            capture(e, "about")
            val entry = nodes(e).single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "引擎信息" } == true }
            assertTrue(entry.boundsInRoot.height >= 48f && entry.boundsInRoot.bottom <= 844f)
            e.press(entry.boundsInRoot.center); e.release(entry.boundsInRoot.center)
            assertEquals(1, opens)
        }
        var reads = 0
        MotionTestEnvironment(testScheduler, 390, 844) { s4SmsHost {
            AppSubpageScene(AppRoute.SettingsEngineInformation, chrome, onBack = {}) {
                SettingsEngineInformationScreen {
                    reads++
                    EngineIntegritySnapshot("a".repeat(12), listOf(RunningEngineIntegrity(101, EngineExecutableMatch.SAME),
                        RunningEngineIntegrity(202, EngineExecutableMatch.DIFFERENT)))
                }
            }
        } }.use { e ->
            repeat(4) { e.advanceFrameBy() }
            assertEquals(0, reads)
            capture(e, "engine-unchecked")
            val check = nodes(e).single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "检查引擎" } == true }
            e.press(check.boundsInRoot.center); e.release(check.boundsInRoot.center)
            repeat(20) { e.advanceFrameBy() }
            assertEquals(1, reads)
            assertTrue("a".repeat(12) in texts(e))
            capture(e, "engine-results")
        }
    } }

    private fun nodes(e: MotionTestEnvironment): List<SemanticsNode> = e.scene.semanticsOwners.flatMap { owner ->
        fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
        visit(owner.rootSemanticsNode)
    }
    private fun texts(e: MotionTestEnvironment): List<String> = nodes(e)
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
}
