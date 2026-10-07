package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.ui.*
import org.jetbrains.skia.Bitmap
import kotlinx.coroutines.Dispatchers
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class CommunicationContextFocusRenderTest {
    private var time = 0L

    @Test fun selectedMessageRetainsItsSurfaceWhileOnlyTheBackgroundIsDimmed() {
        var selected by mutableStateOf(false)
        val scene = ImageComposeScene(width = 390, height = 844) {
            SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 16.dp, vertical = 160.dp)) {
                    CompositionLocalProvider(
                        LocalSmsBubbleAction provides { selected = true },
                        LocalSmsBubbleMenu provides SmsBubbleMenu(if (selected) "message" else null, emptySet(), false, {}, {}, { selected = false }),
                    ) {
                        IncomingMessageBubble(MessageThreadItem.IncomingBubble("message", "合成测试消息", MessageTimestampSpec(1)))
                    }
                }
            } }
        }
        try {
            settle(scene)
            val bubble = nodes(scene).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" }
            val bounds = bubble.boundsInRoot
            val x = bounds.left.toInt() + 4
            val y = bounds.center.y.toInt()
            val original = pixel(scene, x, y)
            val background = pixel(scene, 370, 80)
            requireNotNull(bubble.config[SemanticsActions.OnLongClick].action).invoke()
            settle(scene)
            assertEquals(original, pixel(scene, x, y), "选中气泡必须保持原色，不能被整页遮罩染灰或再叠强调色")
            assertNotEquals(background, pixel(scene, 370, 80), "背景应退后，让对象与菜单成为焦点")
            val copy = nodes(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.any { v -> v.text == "复制正文" } == true && it.config.contains(SemanticsActions.OnClick) }
            assertTrue(copy.boundsInRoot.top >= bounds.bottom, "菜单在空间足够时应贴近所选对象下方")
            assertTrue(copy.boundsInRoot.top - bounds.bottom <= 28f, "菜单不应与对象脱节")
            selected = false; settle(scene)
            assertEquals(original, pixel(scene, x, y))
            assertEquals(background, pixel(scene, 370, 80))
        } finally { scene.close() }
    }

    @Test fun largeTypeAndTallSourceKeepAllActionsInsideTheSmallViewport() {
        var selected by mutableStateOf(false)
        val scene = ImageComposeScene(width = 320, height = 480, density = Density(1f, 2f)) {
            SoftVoWiFiTheme(true) { Scaffold(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 80.dp)) {
                    CompositionLocalProvider(
                        LocalSmsBubbleAction provides { selected = true },
                        LocalSmsBubbleMenu provides SmsBubbleMenu(if (selected) "tall" else null, setOf("tall"), true, {}, {}, { selected = false }),
                    ) {
                        IncomingMessageBubble(MessageThreadItem.IncomingBubble("tall", "用于大字号窗口检查的合成正文。".repeat(6), MessageTimestampSpec(1)))
                    }
                }
            } }
        }
        try {
            settle(scene)
            val source = nodes(scene).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" }
            val sourceBounds = source.boundsInRoot
            requireNotNull(source.config[SemanticsActions.OnLongClick].action).invoke(); settle(scene)
            val preview = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == "communication-context-preview" }
            assertTrue(preview.boundsInRoot.height < sourceBounds.height, "高消息仅裁剪焦点预览，不能缩小正文")
            for (label in listOf("复制正文", "消息详情", "重新编辑")) {
                val action = nodes(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.any { v -> v.text == label } == true && it.config.contains(SemanticsActions.OnClick) }
                assertTrue(action.boundsInRoot.height >= 48f, "$label 点击目标过小")
                assertTrue(action.boundsInRoot.top >= 0 && action.boundsInRoot.bottom <= 480, "$label 超出窗口")
            }
            assertEquals(sourceBounds, nodes(scene).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" }.boundsInRoot)
        } finally { scene.close() }
    }

    @Test fun pointerDismissAndReopenWorkWithSystemAnimationsDisabled() {
        var selected by mutableStateOf(false)
        var details = 0
        val motion = object : MotionDurationScale { override val scaleFactor = 0f }
        val scene = ImageComposeScene(width = 390, height = 844, coroutineContext = Dispatchers.Unconfined + motion) {
            SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 160.dp)) {
                    CompositionLocalProvider(
                        LocalSmsBubbleAction provides { selected = true },
                        LocalSmsBubbleMenu provides SmsBubbleMenu(if (selected) "message" else null, emptySet(), false,
                            { details++; selected = false }, {}, { selected = false }),
                    ) {
                        IncomingMessageBubble(MessageThreadItem.IncomingBubble("message", "合成测试消息", MessageTimestampSpec(1)))
                    }
                }
            } }
        }
        fun open() {
            val source = nodes(scene).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" }
            requireNotNull(source.config[SemanticsActions.OnLongClick].action).invoke(); settle(scene)
        }
        fun tap(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, point)
            scene.sendPointerEvent(PointerEventType.Release, point)
            settle(scene)
        }
        try {
            settle(scene); open()
            tap(Offset(300f, 700f))
            assertFalse(selected)
            assertEquals(0, details, "背景取消不能误执行菜单动作")
            open()
            val action = nodes(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.any { v -> v.text == "消息详情" } == true && it.config.contains(SemanticsActions.OnClick) }
            tap(action.boundsInRoot.center)
            assertEquals(1, details)
            assertFalse(selected)
        } finally { scene.close() }
    }

    private fun settle(scene: ImageComposeScene) { repeat(16) { time += 50_000_000L; scene.render(time).close() } }
    private fun pixel(scene: ImageComposeScene, x: Int, y: Int) = scene.render(time).use { image ->
        Bitmap.makeFromImage(image).use { it.getColor(x, y) }
    }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { owner ->
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        tree(owner.rootSemanticsNode)
    }
}
