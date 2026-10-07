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
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import kotlinx.coroutines.test.*
import org.jetbrains.skia.Bitmap
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PhoneContextExitTest {
    @Test fun historyLastPreviewFrameMatchesRestoredSource() = runTest { checkExit(false, testScheduler) }
    @Test fun detailLastPreviewFrameMatchesRestoredSource() = runTest { checkExit(true, testScheduler) }
    @Test fun historyPointerReleaseAndDismissDoNotReopenTheWindow() = runTest { checkExit(false, testScheduler, pointer = true) }
    @Test fun detailPointerReleaseAndDismissDoNotReopenTheWindow() = runTest { checkExit(true, testScheduler, pointer = true) }

    private fun checkExit(detail: Boolean, scheduler: TestCoroutineScheduler, pointer: Boolean = false) {
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val record = requireNotNull(CallHistoryWrite.parse(CallHistoryDraft("synthetic", CallDirection.OUTGOING,
            "+12025550123", CallTimesDraft(1790956800000L, 1790956801000L, 1790956861000L, 60), CallOutcome.COMPLETED))).toRecord(owner)
        val group = callHistoryGroups(CallHistoryState.Ready(listOf(record), owner)).single()
        val folder = Files.createDirectories(Path.of("build/reports/phone-context-exit/${if (detail) "detail" else "history"}${if (pointer) "-pointer" else ""}"))
        val scene = ImageComposeScene(width = 390, height = 844, coroutineContext = UnconfinedTestDispatcher(scheduler)) {
            SoftVoWiFiTheme(false) { PhoneStyle {
                Column(Modifier.fillMaxSize().background(phoneColors().canvas).padding(horizontal = 24.dp, vertical = 160.dp)) {
                    if (detail) CallHistoryOccurrence(record) { error("取消不能删除") }
                    else CallHistoryGroupRow(group, { error("取消不能打开详情") }, true, { error("取消不能拨号") }, { error("取消不能删除") })
                }
            } }
        }
        var time = 0L
        fun frame(name: String? = null) {
            scheduler.advanceTimeBy(16); scheduler.runCurrent(); time += 16_000_000L
            scene.render(time).use { image -> if (name != null) image.encodeToData()!!.use { Files.write(folder.resolve("$name.png"), it.bytes) } }
        }
        fun source() = nodes(scene).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == if (detail) "删除通话记录" else "通话记录操作" }
        fun pixels(rect: Rect): List<Int> = scene.render(time).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
            buildList { for (y in rect.top.toInt() + 8 until rect.bottom.toInt() - 8 step 2)
                for (x in rect.left.toInt() + 8 until rect.right.toInt() - 8 step 2) add(bitmap.getColor(x, y)) }
        } }
        fun pixelError(a: List<Int>, b: List<Int>) = a.indices.sumOf { i ->
            listOf(0, 8, 16).sumOf { shift -> abs(((a[i] ushr shift) and 255) - ((b[i] ushr shift) and 255)) }
        }.toDouble() / (a.size * 3)
        try {
            repeat(30) { frame() }
            val bounds = source().boundsInRoot
            val original = pixels(bounds)
            if (pointer) {
                scene.sendPointerEvent(PointerEventType.Press, bounds.center)
                repeat(40) { frame() }
                scene.sendPointerEvent(PointerEventType.Release, bounds.center)
            } else source().config[SemanticsActions.OnLongClick].action!!.invoke()
            repeat(30) { frame() }
            assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.TestTag) == "communication-context-preview" })
            scene.sendPointerEvent(PointerEventType.Press, Offset(350f, 700f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(350f, 700f))
            var previous = pixels(bounds)
            var previouslyShown = true
            var handoffError: Double? = null
            val errors = mutableListOf<Double>()
            repeat(if (pointer) 80 else 24) { index ->
                frame("exit-${index.toString().padStart(2, '0')}")
                val current = pixels(bounds)
                val shown = nodes(scene).any { it.config.getOrNull(SemanticsProperties.TestTag) == "communication-context-preview" }
                if (handoffError != null) assertFalse(shown, "已完成退场后不得重新出现窗口")
                if (previouslyShown && !shown) handoffError = pixelError(previous, original)
                if (!shown) errors += pixelError(current, original)
                previous = current; previouslyShown = shown
            }
            val errorAtHandoff = assertNotNull(handoffError, "弹层必须完全释放")
            assertTrue(errorAtHandoff < 1.0, "交接前一帧必须已经回到原内容，当前RGB平均差=$errorAtHandoff")
            assertTrue(errors.all { it < .1 }, "弹层释放后不得出现源空帧/闪回：$errors")
            assertEquals(bounds, source().boundsInRoot)
        } finally { scene.close() }
    }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { owner ->
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        tree(owner.rootSemanticsNode)
    }
}
