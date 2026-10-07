package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class DialerGeometryRenderTest {
    private var time = 0L

    @Test fun productionIdleFirstDigitKeepsGeometry392() = geometry(392, 1f, true)
    @Test fun productionIdleFirstDigitKeepsGeometry320() = geometry(320, 1f, true)
    @Test fun absentMessageAndValidationKeepGeometry() = geometry(392, 1f, false)
    @Test fun largeFontKeepsSingleLineAndActionsReachable() = geometry(320, 2f, true)

    private fun geometry(width: Int, font: Float, production: Boolean) {
        var number by mutableStateOf("")
        val scene = ImageComposeScene(width = width, height = 844) {
            CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalPhoneBack provides {}) {
                SoftVoWiFiTheme(false) {
                    if (production) CallsDialpadScreen(
                        CallUiState.Ready(CallPhase.Idle, CallAudioState.Idle, CallOrigin.ENGINE),
                        number, { number = it }, {}, lineLabel = "合成测试线路")
                    else PhoneDialerScreen(number, { number = it }, "合成测试线路", true, false, null, {}, {}, {})
                }
            }
        }
        try {
            frame(scene)
            val snapshots = mutableListOf<List<androidx.compose.ui.geometry.Rect>>()
            fun record(name: String) {
                snapshots += listOf(field(scene), button(scene, "1"), button(scene, "拨打")).map { node ->
                    androidx.compose.ui.geometry.Rect(node.positionInRoot,
                        androidx.compose.ui.geometry.Size(node.size.width.toFloat(), node.size.height.toFloat()))
                }
                println("geometry width=$width font=$font production=$production state=$name bounds=${snapshots.last()} scroll=${nodes(scene).single { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }.config[SemanticsProperties.VerticalScrollAxisRange].value()}")
                if (width == 392 && production && name in listOf("empty", "first")) screenshot(scene, name)
            }
            record("empty")
            click(scene, "1"); frame(scene); record("first")
            click(scene, "2"); frame(scene); record("more")
            click(scene, "删除号码"); frame(scene); record("delete")
            click(scene, "删除号码"); frame(scene); record("empty-again")
            click(scene, "*"); frame(scene); record("invalid")
            assertTrue(texts(scene).contains("请输入电话号码，暂不支持 USSD"))
            click(scene, "删除号码"); frame(scene); record("valid-again")
            val setText = requireNotNull(field(scene).config[SemanticsActions.SetText].action)
            setText(AnnotatedString("+12025550123000000000")); frame(scene); record("long")
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            requireNotNull(field(scene).config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts)
            val scroll = nodes(scene).single { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            requireNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, 4000f); frame(scene)
            val dial = button(scene, "拨打").boundsInRoot
            assertTrue(dial.top >= 0 && dial.bottom <= 844 && dial.height >= 64 && dial.width > 0, "dial action unreachable: $dial")
            println("long editor lines=${layouts.single().lineCount} width=$width font=$font scrolledDial=$dial")
            if (font == 2f) screenshot(scene, "large-bottom")
            snapshots.drop(1).forEachIndexed { index, bounds ->
                snapshots.first().zip(bounds).forEach { (before, after) ->
                    assertEquals(before.top, after.top, .5f, "state ${index + 1} moved: $before -> $after")
                    assertEquals(before.height, after.height, .5f, "state ${index + 1} resized")
                }
            }
            assertEquals(1, layouts.single().lineCount, "long number must scroll horizontally")
        } finally { scene.close() }
    }

    @Test fun numberIsCenteredIndependentlyOfDelete() {
        val scene = ImageComposeScene(width = 392, height = 844) {
            SoftVoWiFiTheme(false) { PhoneDialerScreen("1", {}, "合成测试线路", true, false, null, {}, {}, {}) }
        }
        try {
            frame(scene)
            val bounds = field(scene).boundsInRoot
            println("number center=${bounds.center.x} viewportCenter=196.0")
            assertEquals(196f, bounds.center.x, .5f)
            assertTrue(bounds.right <= button(scene, "删除号码").boundsInRoot.left)
        } finally { scene.close() }
    }

    @Test fun selectionPasteAndPersistentErrorsRemainUsable() {
        var number by mutableStateOf("12")
        val message = "线路暂不可用，请刷新后重试。"
        val scene = ImageComposeScene(width = 320, height = 844) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                SoftVoWiFiTheme(false) { PhoneDialerScreen(number, { number = it }, "合成测试线路", false, false, message, {}, {}, {}) }
            }
        }
        try {
            frame(scene)
            requireNotNull(field(scene).config[SemanticsActions.SetSelection].action).invoke(0, 1, false)
            frame(scene); click(scene, "5"); frame(scene); assertEquals("52", number)
            requireNotNull(field(scene).config[SemanticsActions.SetSelection].action).invoke(1, 1, false)
            frame(scene); click(scene, "3"); frame(scene); assertEquals("532", number)
            click(scene, "删除号码"); frame(scene); assertEquals("52", number)
            requireNotNull(field(scene).config[SemanticsActions.SetText].action).invoke(AnnotatedString("bad paste"))
            frame(scene); assertEquals("52", number)
            requireNotNull(field(scene).config[SemanticsActions.SetText].action).invoke(AnnotatedString("*"))
            frame(scene)
            assertTrue(texts(scene).contains(message))
            assertTrue(texts(scene).contains("请输入电话号码，暂不支持 USSD"))
            val scroll = nodes(scene).single { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            requireNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, 4000f); frame(scene)
            val errors = nodes(scene).filter { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any {
                it.text == message || it.text == "请输入电话号码，暂不支持 USSD"
            } }
            assertEquals(2, errors.size)
            errors.forEach { assertTrue(it.boundsInRoot.height > 0 && it.boundsInRoot.bottom <= 844) }
            assertTrue(button(scene, "拨打").config.contains(SemanticsProperties.Disabled))
        } finally { scene.close() }
    }

    @Test fun actualKeypadLongPressAndNavigationActExactlyOnce() = runBlocking {
        var number by mutableStateOf("")
        var edits = 0
        var navigation = 0
        val haptics = mutableListOf<HapticFeedbackType>()
        val recorder = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
        }
        val scene = ImageComposeScene(width = 392, height = 844) {
            CompositionLocalProvider(LocalHapticFeedback provides recorder) {
                SoftVoWiFiTheme(false) {
                    PhoneDialerScreen(number, { number = it; edits++ }, "合成测试线路", true, false, null,
                        {}, { navigation++ }, { navigation++ })
                }
            }
        }
        suspend fun press(label: String, duration: Long) {
            val center = button(scene, label).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, center); delay(duration); frame(scene)
            scene.sendPointerEvent(PointerEventType.Release, center); frame(scene)
        }
        try {
            frame(scene); press("1", 30); assertEquals("1", number)
            press("0", 700); assertEquals("+1", number); assertEquals(2, edits)
            press("删除号码", 700); assertEquals("", number); assertEquals(3, edits)
            press("删除号码", 30); assertEquals(3, edits)
            press("返回", 30)
            assertEquals(1, navigation); assertEquals(4, haptics.size)
            assertTrue(nodes(scene).none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("历史") == true })
        } finally { scene.close() }
    }

    private fun frame(scene: ImageComposeScene) { repeat(12) { time += 100_000_000L; scene.render(time).close() } }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        descend(owner.rootSemanticsNode)
    }
    private fun field(scene: ImageComposeScene) = nodes(scene).first { it.config.contains(SemanticsActions.SetText) }
    private fun texts(scene: ImageComposeScene) = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun button(scene: ImageComposeScene, label: String) = nodes(scene).first {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true && it.config.contains(SemanticsActions.OnClick)
    }
    private fun click(scene: ImageComposeScene, label: String) { requireNotNull(button(scene, label).config[SemanticsActions.OnClick].action).invoke() }
    private fun screenshot(scene: ImageComposeScene, name: String) {
        val directory = Files.createDirectories(Path.of("build/reports/dialer"))
        scene.render(time).use { image -> requireNotNull(image.encodeToData()).use { data ->
            Files.write(directory.resolve("$name.png"), data.bytes)
        } }
    }
}
