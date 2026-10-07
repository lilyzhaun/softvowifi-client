package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import kotlinx.coroutines.test.*
import org.jetbrains.skia.Bitmap
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommunicationContextMotionTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))

    @Test fun callHoldNeverPaintsAColoredBlockBeforeLongPressRecognition() = runTest {
        val scheduler = testScheduler
        var time = 0L
        val group = callHistoryGroups(CallHistoryState.Ready(listOf(record()), owner)).single()
        val scene = ImageComposeScene(width = 390, height = 844,
            coroutineContext = UnconfinedTestDispatcher(scheduler)) {
            SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 16.dp, vertical = 160.dp)) {
                    CallHistoryGroupRow(group, {}, false, {}, {})
                }
            } }
        }
        fun frame(ms: Int) { scheduler.advanceTimeBy(ms.toLong()); scheduler.runCurrent(); time += ms * 1_000_000L; scene.render(time).close() }
        try {
            repeat(12) { frame(16) }
            val source = nodes(scene).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "通话记录操作" }
            val x = source.boundsInRoot.center.x.toInt()
            val y = source.boundsInRoot.top.toInt() + 4
            val original = pixel(scene, time, x, y)
            scene.sendPointerEvent(PointerEventType.Press, source.boundsInRoot.center)
            for (delta in listOf(32, 48, 80, 100, 80)) {
                frame(delta)
                assertEquals(original, pixel(scene, time, x, y), "长按成立前不得出现整行蓝色或灰色块")
            }
            scene.sendPointerEvent(PointerEventType.Release, source.boundsInRoot.center); frame(16)
        } finally { scene.close() }
    }

    @Test fun conversationWithoutLongPressActionDoesNotPaintAnythingOnHold() = runTest {
        val scheduler = testScheduler
        var time = 0L
        var opened = 0
        val scene = ImageComposeScene(width = 390, height = 240,
            coroutineContext = UnconfinedTestDispatcher(scheduler)) {
            SoftVoWiFiTheme(false) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    ConversationRow(ConversationRowPresentation("12025550123", "", "合成预览", "10:00", null), { opened++ })
                }
            }
        }
        fun frame(ms: Int) { scheduler.advanceTimeBy(ms.toLong()); scheduler.runCurrent(); time += ms * 1_000_000L; scene.render(time).close() }
        try {
            repeat(12) { frame(16) }
            val source = nodes(scene).single { it.config.contains(SemanticsActions.OnClick) }
            assertFalse(source.config.contains(SemanticsActions.OnLongClick))
            val original = pixel(scene, time, 200, 4)
            scene.sendPointerEvent(PointerEventType.Press, source.boundsInRoot.center)
            for (delta in listOf(32, 80, 100, 140, 200)) {
                frame(delta)
                assertEquals(original, pixel(scene, time, 200, 4), "无长按动作的会话不得制造选中块")
                assertEquals(0, opened)
            }
            scene.sendPointerEvent(PointerEventType.Release, source.boundsInRoot.center); frame(16)
            assertEquals(1, opened, "原点击行为不变")
        } finally { scene.close() }
    }

    @Test fun lineChoiceAndMenuPressUseNoAccentSurfaceFill() = runTest {
        val scheduler = testScheduler
        var time = 0L
        val scene = ImageComposeScene(width = 390, height = 240,
            coroutineContext = UnconfinedTestDispatcher(scheduler)) {
            SoftVoWiFiTheme(false) { Column(Modifier.fillMaxSize().background(Color.White)) {
                CommunicationLineRow("合成线路", "合成状态", true, {})
                CommunicationMenuRow("合成动作", CommunicationCopy, {})
            } }
        }
        fun frame(ms: Int) { scheduler.advanceTimeBy(ms.toLong()); scheduler.runCurrent(); time += ms * 1_000_000L; scene.render(time).close() }
        try {
            repeat(12) { frame(16) }
            val line = nodes(scene).single { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
            assertEquals(-1, pixel(scene, time, 260, line.boundsInRoot.top.toInt() + 4), "选线勾选不应铺满蓝色")
            val menu = nodes(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "合成动作" } == true && it.config.contains(SemanticsActions.OnClick) }
            val y = menu.boundsInRoot.top.toInt() + 4
            scene.sendPointerEvent(PointerEventType.Press, menu.boundsInRoot.center)
            repeat(10) { frame(16); assertEquals(-1, pixel(scene, time, 260, y), "菜单按压不应铺满蓝色") }
            scene.sendPointerEvent(PointerEventType.Release, menu.boundsInRoot.center); frame(16)
        } finally { scene.close() }
    }

    @Test fun menuExpandsFromItsAnchorAndExitCanBeInterruptedWithoutJumping() = runTest {
        val scheduler = testScheduler
        var time = 0L
        var open by mutableStateOf(false)
        val scene = ImageComposeScene(width = 390, height = 844,
            coroutineContext = UnconfinedTestDispatcher(scheduler)) {
            SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 16.dp, vertical = 160.dp)) {
                    CompositionLocalProvider(LocalSmsBubbleAction provides { open = true },
                        LocalSmsBubbleMenu provides SmsBubbleMenu(if (open) "source" else null, emptySet(), false, {}, {}, { open = false })) {
                        IncomingMessageBubble(MessageThreadItem.IncomingBubble("source", "合成正文", MessageTimestampSpec(1)))
                    }
                }
            } }
        }
        fun frame(ms: Int) { scheduler.advanceTimeBy(ms.toLong()); scheduler.runCurrent(); time += ms * 1_000_000L; scene.render(time).close() }
        fun menuWidth() = nodes(scene).firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "复制正文" } == true && it.config.contains(SemanticsActions.OnClick) }?.boundsInRoot?.width ?: 0f
        try {
            repeat(12) { frame(16) }
            val source = nodes(scene).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" }
            requireNotNull(source.config[SemanticsActions.OnLongClick].action).invoke()
            repeat(4) { frame(16) }
            val early = menuWidth()
            repeat(20) { frame(16) }
            val settled = menuWidth()
            assertTrue(early > 0 && early < settled - .5f, "菜单不能仅整层淡入，须从锚点连续展开")
            assertEquals(248f, settled, .5f)
            open = false
            val exitWidths = List(6) { frame(16); menuWidth() }
            val closing = exitWidths.last()
            assertTrue(exitWidths.any { it > 0 && it < settled - .1f } && closing > 0,
                "退出须存在回落中间帧、不得突切：$exitWidths，稳定宽度$settled")
            open = true; frame(16)
            val reopened = menuWidth()
            assertTrue(reopened > 0 && kotlin.math.abs(reopened - closing) < 8f, "反向动作从当前进度衔接")
            repeat(20) { frame(16) }
            assertEquals(settled, menuWidth(), .5f)
        } finally { scene.close() }
    }

    @Test fun detailHandoffWaitsForExitAndIsCancelledIfItsRouteBecomesInactive() = runTest {
        for (cancel in listOf(false, true)) {
            val scheduler = testScheduler
            var time = 0L
            var open by mutableStateOf(false)
            var active by mutableStateOf(true)
            var details = 0
            val scene = ImageComposeScene(width = 390, height = 844,
                coroutineContext = UnconfinedTestDispatcher(scheduler)) {
                SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalAppRouteActive provides active,
                        LocalSmsBubbleAction provides { open = true },
                        LocalSmsBubbleMenu provides SmsBubbleMenu(if (open) "source" else null, emptySet(), false,
                            { details++; open = false }, {}, { open = false })) {
                        Box(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 16.dp, vertical = 160.dp)) {
                            IncomingMessageBubble(MessageThreadItem.IncomingBubble("source", "合成正文", MessageTimestampSpec(1)))
                        }
                    }
                } }
            }
            fun frame(ms: Int) { scheduler.advanceTimeBy(ms.toLong()); scheduler.runCurrent(); time += ms * 1_000_000L; scene.render(time).close() }
            try {
                repeat(12) { frame(16) }
                requireNotNull(nodes(scene).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" }
                    .config[SemanticsActions.OnLongClick].action).invoke()
                repeat(24) { frame(16) }
                val detail = nodes(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "消息详情" } == true && it.config.contains(SemanticsActions.OnClick) }
                val click = requireNotNull(detail.config[SemanticsActions.OnClick].action)
                click.invoke(); click.invoke(); frame(16)
                assertEquals(0, details, "必须先退场，不能立即打开或重复交接")
                if (cancel) active = false
                repeat(24) { frame(16) }
                assertEquals(if (cancel) 0 else 1, details, "只执行一次，失活取消迟到交接")
                assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsProperties.TestTag) == "communication-context-preview" })
            } finally { scene.close() }
        }
    }

    private fun record() = requireNotNull(CallHistoryWrite.parse(CallHistoryDraft("synthetic", CallDirection.INCOMING,
        "+12025550123", CallTimesDraft(1000, 2000, 62000, 60), CallOutcome.COMPLETED))).toRecord(owner)
    private fun pixel(scene: ImageComposeScene, time: Long, x: Int, y: Int) = scene.render(time).use { image ->
        Bitmap.makeFromImage(image).use { it.getColor(x, y) }
    }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { owner ->
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        tree(owner.rootSemanticsNode)
    }
}
