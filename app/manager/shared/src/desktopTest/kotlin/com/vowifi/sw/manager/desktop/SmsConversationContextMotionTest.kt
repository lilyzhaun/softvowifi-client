package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.ui.*
import kotlinx.coroutines.test.*
import org.jetbrains.skia.Bitmap
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SmsConversationContextMotionTest {
    @Test fun previewInsetsTravelLikePhoneAndMenuAlignsToRightEdge() = smsOnEdt {
        val scheduler = TestCoroutineScheduler()
        val scene = ImageComposeScene(width = 390, height = 844, coroutineContext = StandardTestDispatcher(scheduler)) {
            SoftVoWiFiTheme(false) { PhoneStyle {
                Box(Modifier.fillMaxSize().background(phoneColors().canvas).padding(horizontal = 24.dp, vertical = 180.dp)) {
                    MessageConversationMenu(ConversationRowPresentation("155501", "1", "合成消息", "10:00", null), 0,
                        { error("长按不能进入会话") }, { error("背景关闭不能执行业务") }, true)
                }
            } }
        }
        var time = 0L
        fun frame() { scheduler.advanceTimeBy(16); scheduler.runCurrent(); time += 16_000_000L; scene.render(time).close(); scheduler.runCurrent() }
        fun nodes() = scene.semanticsOwners.flatMap { owner ->
            fun tree(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::tree)
            tree(owner.rootSemanticsNode)
        }
        fun preview() = nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "communication-context-preview" }
        try {
            repeat(40) { frame() }
            val source = nodes().single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "会话操作" }.boundsInRoot
            val y = (source.top + 34).toInt()
            val fill = scene.render(time).use { image -> Bitmap.makeFromImage(image).use { it.getColor(source.left.toInt() + 8, y) } }
            fun avatarLeft() = scene.render(time).use { image -> Bitmap.makeFromImage(image).use { pixels ->
                (0 until 390).firstOrNull { pixels.getColor(it, y) == fill }
            } }
            val start = assertNotNull(avatarLeft())
            val travel = mutableListOf<Int>()
            val heights = mutableListOf<Float>()
            scene.sendPointerEvent(PointerEventType.Press, source.center)
            repeat(60) { frame(); if (preview()) {
                avatarLeft()?.let(travel::add)
                heights += nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "communication-context-preview" }.boundsInRoot.height
            } }
            scene.sendPointerEvent(PointerEventType.Release, source.center)
            repeat(25) { frame() }
            assertTrue(preview())
            val end = assertNotNull(avatarLeft())
            assertEquals(12f, (end - start).toFloat(), 1f, "电话同款预览左右12dp内边距")
            assertTrue(travel.any { it > start + 1 && it < end - 1 }, "内边距必须经过中间帧：$travel")
            assertTrue(heights.all { kotlin.math.abs(it - source.height) < 1.1f }, "动画不能因内边距跨过320dp断点而重排时间行：$heights")
            val menu = nodes().last { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "标为未读" } == true }.boundsInRoot
            assertEquals(source.right, menu.right, 1f, "菜单像电话一样靠预览右侧对齐")
            val folder = java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/reports/sms-conversation-context"))
            scene.render(time).use { image -> image.encodeToData()!!.use { java.nio.file.Files.write(folder.resolve("open.png"), it.bytes) } }
            scene.sendPointerEvent(PointerEventType.Press, Offset(2f, 8f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(2f, 8f))
            val returning = List(40) { frame(); avatarLeft() }
            assertTrue(returning.filterNotNull().any { it > start + 1 && it < end - 1 }, "退出也应连续回到源行")
            assertFalse(preview()); assertEquals(start, avatarLeft())
        } finally { scene.close(); scheduler.runCurrent() }
    }
}
