package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import kotlinx.coroutines.test.*
import org.jetbrains.skia.Bitmap
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SmsContextExitTest {
    @Test fun incomingLastFrameMatchesSourceAndBackdrop() = runTest { checkExit(false, false, testScheduler) }
    @Test fun outgoingLastFrameMatchesSourceAndBackdrop() = runTest { checkExit(true, false, testScheduler) }
    @Test fun constrainedLongMessageReturnsWithoutClippedLastFrame() = runTest { checkExit(true, true, testScheduler) }

    private fun checkExit(outgoing: Boolean, constrained: Boolean, scheduler: TestCoroutineScheduler) {
        var selected by mutableStateOf<String?>(null)
        val width = if (constrained) 320 else 390
        val height = if (constrained) 520 else 844
        val body = if (constrained) (1..14).joinToString("\n") { "合成测试第${it}行" } else "合成消息，仅用于动画验证"
        val folder = Files.createDirectories(Path.of("build/reports/sms-context-exit/$outgoing-$constrained"))
        val scene = ImageComposeScene(width = width, height = height, coroutineContext = UnconfinedTestDispatcher(scheduler)) {
            SoftVoWiFiTheme(false) {
                Box(Modifier.fillMaxSize().background(communicationColors().timeline).padding(horizontal = 16.dp)) {
                    CompositionLocalProvider(LocalSmsBubbleAction provides { selected = it },
                        LocalSmsBubbleMenu provides SmsBubbleMenu(selected, emptySet(), false,
                            { error("外部点击不能打开详情") }, {}, { selected = null })) {
                        val modifier = Modifier.padding(top = if (constrained) 24.dp else 600.dp)
                        if (outgoing) OutgoingMessageBubble(MessageThreadItem.OutgoingBubble("synthetic", body,
                            MessageTimestampSpec(1), "已受理", null), modifier)
                        else IncomingMessageBubble(MessageThreadItem.IncomingBubble("synthetic", body, MessageTimestampSpec(1)), modifier)
                    }
                }
            }
        }
        var time = 0L
        fun frame() { scheduler.advanceTimeBy(16); scheduler.runCurrent(); time += 16_000_000L; scene.render(time).close() }
        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
            fun tree(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::tree)
            tree(owner.rootSemanticsNode)
        }
        fun shown() = nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "communication-context-preview" }
        fun pixels(rect: Rect) = scene.render(time).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
            buildList { for (y in rect.top.toInt().coerceAtLeast(0) until rect.bottom.toInt().coerceAtMost(height) step 2)
                for (x in rect.left.toInt().coerceAtLeast(0) until rect.right.toInt().coerceAtMost(width) step 2) add(bitmap.getColor(x,y)) }
        } }
        fun error(a: List<Int>, b: List<Int>) = a.indices.sumOf { i ->
            listOf(0,8,16).sumOf { s -> abs(((a[i] ushr s) and 255)-((b[i] ushr s) and 255)) }
        }.toDouble()/(a.size*3)
        try {
            repeat(30) { frame() }
            val source = nodes().single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" }
            val area = source.boundsInRoot.inflate(12f)
            val backdrop = Rect(0f, 0f, width.toFloat(), 12f)
            val original = pixels(area); val clear = pixels(backdrop)
            scene.sendPointerEvent(PointerEventType.Press, source.boundsInRoot.center)
            repeat(40) { frame() }
            scene.sendPointerEvent(PointerEventType.Release, source.boundsInRoot.center)
            repeat(24) { frame() }; assertTrue(shown())
            scene.sendPointerEvent(PointerEventType.Press, Offset(width-2f, 6f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(width-2f, 6f))
            var previous = pixels(area); var previousShade = pixels(backdrop); var wasShown = true
            var terminal: Pair<Double,Double>? = null
            val errors = mutableListOf<Pair<Double,Double>>()
            repeat(40) { index ->
                frame()
                val present = shown(); val current = pixels(area); val shade = pixels(backdrop)
                if (wasShown && !present) terminal = error(previous,original) to error(previousShade,clear)
                if (terminal != null) {
                    assertFalse(present,"关闭后不能重开")
                    assertTrue(error(current,original)<.1,"关闭后正文/阴影不得闪回")
                }
                errors += error(current,original) to error(shade,clear)
                if (index in 7..13) scene.render(time).use { image -> image.encodeToData()!!.use {
                    Files.write(folder.resolve("exit-$index.png"),it.bytes)
                } }
                previous=current; previousShade=shade; wasShown=present
            }
            Files.writeString(folder.resolve("errors.txt"),errors.joinToString("\n"))
            val last = assertNotNull(terminal,"窗口必须完成退场")
            assertTrue(last.first<1.0,"气泡/文字/外围末帧RGB差=${last.first}")
            assertTrue(last.second<1.0,"遮罩末帧RGB差=${last.second}")
            assertNull(selected)
        } finally { scene.close() }
    }
}
