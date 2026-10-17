@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.data.CallSessionClock
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** 生产CallScreenContent的真实文字排版；仅合成UI，不证明通信/Android或用户手感。 */
class CallHeaderLayoutRenderTest {
    private val line = "SIM 1 · 合成线路"
    private val number = "+1 202 555 0123"
    private val binding = CallBinding("synthetic-header-owner", "synthetic-header-call", 1, "synthetic-epoch")
    private fun state(phase: CallPhase, clock: CallSessionClock? = null) = CallUiState.Ready(
        phase, if (phase == CallPhase.InCall) CallAudioState.Active else CallAudioState.Idle,
        CallOrigin.DESKTOP_FIXTURE, binding = binding, peer = CallPeer.parse("+12025550123"),
        direction = CallDirection.OUTGOING, dtmfSupported = phase == CallPhase.InCall, clock = clock)

    @Composable private fun content(state: CallUiState.Ready, ticking: Boolean = false) {
        CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) { s4SmsHost {
            CallScreenContent(state, line, CallAudioControls(), onAction = {}, onMute = {}, onSpeaker = {},
                onDigit = {}, onRestoreAudio = {}, onMinimize = {}, ticking = ticking)
        } }
    }
    private data class TextGeometry(val run: Rect, val glyph: Rect, val baseline: Float)
    private fun capture(e: MotionTestEnvironment, name: String, timeMillis: Long) {
        val label = System.getenv("SOFTVOWIFI_HEADER_CAPTURE") ?: return
        require(Regex("[a-z-]{1,24}").matches(label))
        e.scene.render(timeMillis * 1_000_000L).use { image ->
            requireNotNull(image.encodeToData()).use { data ->
                java.nio.file.Files.write(java.nio.file.Path.of("/tmp/opencode/call-header-$label-$name.png"),
                    data.bytes, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)
            }
        }
    }
    private fun text(e: MotionTestEnvironment, value: String): TextGeometry {
        val node = assertNotNull(s4Nodes(e, true).singleOrNull { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == value &&
                n.config.contains(SemanticsActions.GetTextLayoutResult)
        }, "测量前提：须唯一取得实际Text叶与公开GetTextLayoutResult：$value")
        val results = mutableListOf<TextLayoutResult>()
        assertTrue(assertNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(results))
        val semanticLayout = assertNotNull(results.singleOrNull(), "测量前提：实际文字布局结果必须唯一")
        val input = semanticLayout.layoutInput
        // Compose 1.11 SimpleText's semantics reflows with the original max constraint,
        // not its drawn Paragraph width. Re-measure with the actual Text leaf width.
        val layout = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0)
            .measure(input.text, input.style, input.overflow, input.softWrap, input.maxLines,
                input.placeholders, constraints = Constraints.fixedWidth(node.size.width), skipCache = true)
        assertEquals(node.size, layout.size, "测量前提：公开重测须与实际Text叶尺寸吻合")
        assertEquals(value, layout.layoutInput.text.text); assertEquals(1, layout.lineCount, "标准字号头部须单行")
        val origin = node.positionInRoot
        if (System.getenv("SOFTVOWIFI_HEADER_CAPTURE") != null) println(
            "CALL_HEADER_GEOMETRY|$value|origin=$origin|bounds=${node.boundsInRoot}|" +
                "layout=${layout.size}|paragraphWidth=${layout.multiParagraph.width}|" +
                "left=${layout.getLineLeft(0)}|right=${layout.getLineRight(0)}|baseline=${layout.firstBaseline}")
        val boxes = value.indices.filter { !value[it].isWhitespace() }.map { layout.getBoundingBox(it) }
        assertTrue(boxes.isNotEmpty() && boxes.all { it.width > 0f && it.height > 0f }, "测量前提：有实际字形advance，不采空容器")
        val glyph = Rect(origin.x + boxes.minOf { it.left }, origin.y + boxes.minOf { it.top },
            origin.x + boxes.maxOf { it.right }, origin.y + boxes.maxOf { it.bottom })
        val run = Rect(origin.x + layout.getLineLeft(0), origin.y + layout.getLineTop(0),
            origin.x + layout.getLineRight(0), origin.y + layout.getLineBottom(0))
        return TextGeometry(run, glyph, origin.y + layout.firstBaseline)
    }
    private fun durationLabel(e: MotionTestEnvironment): String {
        val labels = s4Nodes(e, true).filter { it.config.contains(SemanticsActions.GetTextLayoutResult) }
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text }
            .filter { Regex(" · \\d{2}:\\d{2}").matches(it) }.distinct()
        return assertNotNull(labels.singleOrNull(), "测量前提：独立计时Text叶须真实可见且唯一")
    }
    private fun compactInCall(e: MotionTestEnvironment) {
        val stage = text(e, "通话中"); val timer = text(e, durationLabel(e)); val peer = text(e, number)
        val faults = mutableListOf<String>()
        if (kotlin.math.abs(stage.baseline - timer.baseline) > 1f) faults += "状态与计时不在同一真实baseline"
        // timer run自身已包含原‘ · ’前缀，不能再额外留最长同步文案的空槽。
        val gap = timer.run.left - stage.run.right
        if (gap < -1f || gap > 1f) faults += "状态/计时Text run不紧邻：gap=$gap（‘ · ’已计入timer）"
        val center = (stage.run.left + timer.run.right) / 2f
        if (kotlin.math.abs(center - peer.glyph.center.x) > 1f || kotlin.math.abs(center - 195f) > 1f)
            faults += "状态＋计时整体未对齐号码/390视口中心：center=$center peer=${peer.glyph.center.x}"
        if (stage.glyph.right > timer.glyph.left + 1f) faults += "阶段/计时真实glyph布局重叠"
        assertTrue(faults.isEmpty(), faults.joinToString("\n"))
    }

    @Test fun dialingWithoutClockCentersTheActualStageGlyphRatherThanItsLongestTextContainer() = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher, timeout = 15.seconds) {
            val current = state(CallPhase.Dialing); assertNull(current.clock)
            MotionTestEnvironment(testScheduler, 390, 844, dispatcher = dispatcher) { content(current) }.use { e ->
                s4Frames(e, 30)
                capture(e, "dialing", testScheduler.currentTime)
                val stage = text(e, "正在呼叫"); val peer = text(e, number)
                assertEquals(195f, peer.glyph.center.x, 1f, "前提：原号码实际排版居中")
                assertEquals(peer.glyph.center.x, stage.glyph.center.x, 1f,
                    "无clock的状态字形须与号码/视口居中，不能用外层定宽Box中心掩盖隐藏计时占位")
            }
        }
    }

    @Test fun inCallStageAndIndependentDurationAreAdjacentOnOneBaselineAndTogetherCentered() = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher, timeout = 15.seconds) {
            val current = state(CallPhase.InCall, CallSessionClock(12_000) { 0L })
            MotionTestEnvironment(testScheduler, 390, 844, dispatcher = dispatcher) { content(current) }.use { e ->
                s4Frames(e, 30); capture(e, "incall", testScheduler.currentTime)
                assertEquals(" · 00:12", durationLabel(e)); compactInCall(e)
            }
        }
    }

    @Test fun phaseAndSecondChangesKeepIdentityStageBaselineAndOriginalKeypadFooterGeometryStable() = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher, timeout = 20.seconds) {
            var current by mutableStateOf(state(CallPhase.Dialing))
            MotionTestEnvironment(testScheduler, 390, 844, dispatcher = dispatcher) { content(current, ticking = true) }.use { e ->
                s4Frames(e, 30)
                fun identity() = listOf(text(e, line).run, text(e, number).run)
                val originalIdentity = identity(); val baseline = text(e, "正在呼叫").baseline
                val footer = s4Action(e, "取消").boundsInRoot.bottom
                current = state(CallPhase.Connecting); s4Frames(e, 30)
                assertEquals(originalIdentity, identity()); assertEquals(baseline, text(e, "正在接通").baseline, 1f)
                assertEquals(footer, s4Action(e, "挂断").boundsInRoot.bottom, 1f)
                current = state(CallPhase.InCall, CallSessionClock(12_000) { testScheduler.currentTime })
                s4Frames(e, 30)
                assertEquals(originalIdentity, identity()); assertEquals(baseline, text(e, "通话中").baseline, 1f)
                val actions = listOf("挂断", "静音", "免提").associateWith { s4Action(e, it).boundsInRoot }
                assertEquals(footer, actions.getValue("挂断").bottom, 1f)
                val oldDuration = durationLabel(e)
                e.advanceFrameBy(1_000); e.advanceFrameBy(0)
                assertNotEquals(oldDuration, durationLabel(e), "正向前提：真实CallSessionClock已逐秒更新")
                assertEquals(originalIdentity, identity()); assertEquals(baseline, text(e, "通话中").baseline, 1f)
                assertEquals(actions, actions.keys.associateWith { s4Action(e, it).boundsInRoot })
                s4Tap(e, s4Action(e, "拨号键盘").boundsInRoot.center); s4Frames(e, 50)
                assertNotNull(s4Action(e, "关闭键盘")); assertEquals(originalIdentity, identity())
                assertEquals(actions, actions.keys.associateWith { s4Action(e, it).boundsInRoot }, "原键盘开合不移动原底动作")
                s4Tap(e, s4Action(e, "关闭键盘").boundsInRoot.center); s4Frames(e, 50)
                assertNotNull(s4Action(e, "拨号键盘")); assertEquals(originalIdentity, identity())
                assertEquals(actions, actions.keys.associateWith { s4Action(e, it).boundsInRoot })
            }
        }
    }
}
