package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class CommunicationCompleteSurfaceTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private var time = 0L

    @Test fun smsRootUsesPhoneHeadingAndCompactComposeAction() {
        var opened = 0
        val scene = ImageComposeScene(width = 390, height = 844) {
            SoftVoWiFiTheme(false) { MessagesScreen(MessageUiState.Unavailable("历史线路未连接"), {}, { opened++ }, hasRegisteredEngine = true) }
        }
        try {
            frame(scene)
            val title = leaves(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "短信" }
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            title.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertEquals(32f, layouts.single().layoutInput.style.fontSize.value)
            assertEquals(0f, layouts.single().getLineLeft(0), .1f)
            val compose = merged(scene).single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("新建短信")==true && it.config.contains(SemanticsActions.OnClick) }
            assertEquals(64f, compose.boundsInRoot.width, .1f)
            assertTrue(compose.boundsInRoot.center.x > 300 && compose.boundsInRoot.center.y > 700)
            compose.config[SemanticsActions.OnClick].action!!.invoke(); assertEquals(1, opened)
        } finally { scene.close() }
    }

    @Test fun twoLineSelectorIsBottomSheetAndSelectionIsIndependentOfRemark() {
        val second = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
        var selected: SimOwnerId? = null
        var edited: SimOwnerId? = null
        val lines = listOf(MessageHistoryLine(owner, 2, true, simLabel = "SIM 1 · Skinny"),
            MessageHistoryLine(second, 1, false, simLabel = "SIM 2 · Lebara"))
        val scene = ImageComposeScene(width = 390, height = 844) {
            SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) {
                MessageLinePicker(MessageHistoryChoices(lines, owner, true), {}, { selected = it }, { edited = it.owner }, null)
            } }
        }
        try {
            frame(scene)
            val heading = leaves(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "选择短信记录线路" }
            assertTrue(heading.boundsInRoot.top > 420f, "两线路面板应从底部出现，不是居中管理器dialog")
            val remarks = merged(scene).filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("备注") == true && it.config.contains(SemanticsActions.OnClick) }
            assertEquals(2, remarks.size)
            requireNotNull(remarks.last().config[SemanticsActions.OnClick].action).invoke()
            frame(scene)
            assertEquals(second, edited)
            assertNull(selected)
        } finally { scene.close() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun destructiveAlertIsCenteredAndCancellationNeverClearsHistory() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) {
            var cancelled = 0
            var cleared = 0
            var requested by mutableStateOf(true)
            val environment = MotionTestEnvironment(testScheduler, 390, 844, scale) {
                SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) {
                    if (requested) ClearCallHistoryConfirmation({ cancelled++; requested = false }, { cleared++ })
                } }
            }
            val scene = environment.scene
            fun consumeNow() { repeat(6) { environment.advanceFrameBy(0) } }
            fun hasDialog() = merged(scene).any { it.config.contains(SemanticsProperties.IsDialog) }
            try {
                repeat(16) { environment.advanceFrameBy() }
                val title = leaves(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "清除通话记录？" }
                assertTrue(title.boundsInRoot.top in 250f..460f, "危险确认不是底部管理器卡片")
                val cancel = merged(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "取消" && it.config.contains(SemanticsActions.OnClick) }
                assertTrue(cancel.boundsInRoot.height >= 48f)
                assertTrue(hasDialog(), "前提：原危险确认宿主已真实呈现")
                val exitAt = testScheduler.currentTime
                assertTrue(requireNotNull(cancel.config[SemanticsActions.OnClick].action).invoke())
                consumeNow()
                if (scale != 0f) {
                    assertEquals(0, cancelled, "§5.4 T7：取消先退出原宿主，不能点击即交接")
                    assertTrue(hasDialog()); assertEquals(0, cleared)
                    environment.advanceFrameBy(MotionTokens.DurFast.toLong() - 1); consumeNow()
                    assertEquals(0, cancelled); assertTrue(hasDialog()); assertEquals(0, cleared)
                    environment.advanceFrameBy(1); consumeNow()
                }
                assertEquals(exitAt + if (scale == 0f) 0L else MotionTokens.DurFast.toLong(), testScheduler.currentTime)
                assertFalse(hasDialog(), "原宿主必须真实退场完成，不以未触发取消冒充通过")
                assertFalse(requested, "父请求只由原onCancel完成回调移除，不重建另一scene")
                assertEquals(1, cancelled)
                assertEquals(0, cleared)
                consumeNow(); assertEquals(1, cancelled); assertEquals(0, cleared)
            } finally { environment.close() }
        }
    } }

    @Test fun bubbleContextMenuIsBoundedAndUsesTheBubbleAnchor() {
        val scene = ImageComposeScene(width = 390, height = 844) {
            SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 160.dp)) {
                    CompositionLocalProvider(LocalSmsBubbleMenu provides SmsBubbleMenu("bubble", emptySet(), false, {}, {}, {})) {
                        IncomingMessageBubble(MessageThreadItem.IncomingBubble("bubble", "合成正文", MessageTimestampSpec(1)))
                    }
                }
            } }
        }
        try {
            frame(scene)
            val copy = merged(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "复制正文" && it.config.contains(SemanticsActions.OnClick) }
            assertTrue(copy.boundsInRoot.width in 224f..288f, "就地菜单不能横跨手机")
            assertTrue(copy.boundsInRoot.top in 160f..310f, "菜单必须锚定正文，而非居中dialog")
            assertFalse(merged(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "重新编辑" } == true })
        } finally { scene.close() }
    }

    @Test fun realThemeAccentAndSelectedForegroundMaintainReadableContrast() {
        for (dark in listOf(false, true)) {
            lateinit var colors: CommunicationColors
            val scene = ImageComposeScene(width = 390, height = 100) {
                SoftVoWiFiTheme(dark) { colors = communicationColors() }
            }
            try {
                frame(scene)
                val a = colors.accent.luminance()
                val f = colors.onAccent.luminance()
                val contrast = (maxOf(a, f) + .05f) / (minOf(a, f) + .05f)
                assertTrue(contrast >= 4.5f, "深浅色发送/未读/选中前景对比度不足：$contrast")
            } finally { scene.close() }
        }
    }

    private fun frame(scene: ImageComposeScene) { repeat(12) { time += 100_000_000L; scene.render(time).close() } }
    private fun tree(root: SemanticsNode): List<SemanticsNode> = listOf(root) + root.children.flatMap(::tree)
    private fun leaves(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { tree(it.unmergedRootSemanticsNode) }
    private fun merged(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { tree(it.rootSemanticsNode) }
}
