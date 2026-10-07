package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import org.jetbrains.skia.Bitmap
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class CommunicationContextSpacingTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private var time = 0L

    @Test fun focusedCallHasEqualClearInnerGuttersWithoutMovingItsSource() {
        val group = callHistoryGroups(CallHistoryState.Ready(listOf(record()), owner)).single()
        val scene = ImageComposeScene(width = 390, height = 844) {
            SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 16.dp, vertical = 160.dp)) {
                    CallHistoryGroupRow(group, {}, false, {}, {})
                }
            } }
        }
        try {
            frame(scene)
            val source = nodes(scene).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "通话记录操作" }
            val before = source.boundsInRoot
            requireNotNull(source.config[SemanticsActions.OnLongClick].action).invoke(); frame(scene)
            val preview = preview(scene)
            assertEquals(16f, preview.left, .5f)
            assertEquals(16f, 390 - preview.right, .5f)
            scene.render(time).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                for (inset in 2..9) {
                    for (offset in 22..45) {
                        assertEquals(-1, bitmap.getColor(preview.left.toInt() + inset, preview.top.toInt() + offset),
                            "左侧内容贴边：内距${inset}px处仍有头像/文字")
                        assertEquals(-1, bitmap.getColor(preview.right.toInt() - inset - 1, preview.top.toInt() + offset),
                            "右侧内容贴边：内距${inset}px处仍有时间/文字")
                    }
                }
            } }
            val menu = action(scene, "复制号码").boundsInRoot
            assertEquals(preview.right, menu.right, .5f, "右侧菜单与焦点卡片边缘对齐")
            assertEquals(before, nodes(scene).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "通话记录操作" }.boundsInRoot)
        } finally { scene.close() }
    }

    @Test fun leftAndRightEdgeMessagesKeepTheSameSixteenDpViewportGutter() {
        for (end in listOf(false, true)) {
            var open by mutableStateOf(false)
            val scene = ImageComposeScene(width = 320, height = 640) {
                SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 100.dp)) {
                        CompositionLocalProvider(LocalSmsBubbleAction provides { open = true },
                            LocalSmsBubbleMenu provides SmsBubbleMenu(if (open) "edge" else null, emptySet(), false, {}, {}, { open = false })) {
                            if (end) OutgoingMessageBubble(MessageThreadItem.OutgoingBubble("edge", "合成正文", MessageTimestampSpec(1), "已受理", null))
                            else IncomingMessageBubble(MessageThreadItem.IncomingBubble("edge", "合成正文", MessageTimestampSpec(1)))
                        }
                    }
                } }
            }
            try {
                frame(scene)
                val source = nodes(scene).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" }
                requireNotNull(source.config[SemanticsActions.OnLongClick].action).invoke(); frame(scene)
                val preview = preview(scene)
                val menu = action(scene, "复制正文").boundsInRoot
                assertEquals(16f, if (end) 320 - preview.right else preview.left, .5f, "左右两侧统一安全留白")
                assertEquals(if (end) preview.right else preview.left, if (end) menu.right else menu.left, .5f)
            } finally { scene.close() }
        }
    }

    @Test fun focusedOccurrenceKeepsBothInnerGuttersAndMenuWithinNarrowLargeTypeViewport() {
        val scene = ImageComposeScene(width = 320, height = 640, density = Density(1f, 2f)) {
            SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 16.dp, vertical = 60.dp)) {
                    CallHistoryOccurrence(record(), {})
                }
            } }
        }
        try {
            frame(scene)
            val source = nodes(scene).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "删除通话记录" }
            val before = source.boundsInRoot
            requireNotNull(source.config[SemanticsActions.OnLongClick].action).invoke(); frame(scene)
            val preview = preview(scene)
            assertEquals(16f, preview.left, .5f)
            assertEquals(16f, 320 - preview.right, .5f)
            scene.render(time).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                for (y in preview.top.toInt() + 18 until preview.bottom.toInt() - 18) {
                    assertEquals(-1, bitmap.getColor(preview.left.toInt() + 5, y), "详情文字侵入左内距")
                    assertEquals(-1, bitmap.getColor(preview.right.toInt() - 6, y), "详情文字侵入右内距")
                }
            } }
            val menu = action(scene, "删除").boundsInRoot
            assertEquals(preview.right, menu.right, .5f)
            assertTrue(menu.top >= 16 && menu.bottom <= 624)
            assertEquals(before, nodes(scene).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "删除通话记录" }.boundsInRoot)
        } finally { scene.close() }
    }

    private fun record() = requireNotNull(CallHistoryWrite.parse(CallHistoryDraft("synthetic", CallDirection.INCOMING,
        "+12025550123", CallTimesDraft(1000, 2000, 62000, 60), CallOutcome.COMPLETED))).toRecord(owner)
    private fun frame(scene: ImageComposeScene) { repeat(24) { time += 50_000_000L; scene.render(time).close() } }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { owner ->
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        tree(owner.rootSemanticsNode)
    }
    private fun preview(scene: ImageComposeScene): Rect = nodes(scene).single {
        it.config.getOrNull(SemanticsProperties.TestTag) == "communication-context-preview"
    }.boundsInRoot
    private fun action(scene: ImageComposeScene, label: String) = nodes(scene).single {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true && it.config.contains(SemanticsActions.OnClick)
    }
}
