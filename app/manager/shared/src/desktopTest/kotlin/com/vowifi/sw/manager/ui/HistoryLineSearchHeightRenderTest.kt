@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.data.CallHistoryOwnerChoice
import com.vowifi.sw.manager.data.CallLineState
import com.vowifi.sw.manager.data.MessageOwnerLabel
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.model.MessageHistoryChoices
import com.vowifi.sw.manager.model.MessageHistoryLine
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** 只用合成线路；测真实sheet内容Column/输入/正文viewport，不把整页或标题bounds冒sheet高度。 */
class HistoryLineSearchHeightRenderTest {
    @Test fun characterEditsKeepTheSameOpenSheetSearchAndBodyAtAFixedImeBudget() = onMotionTestThread { runTest {
        Picker(testScheduler).use { picker ->
            picker.openAndFocus()
            val initial = picker.geometry()
            assertEquals(2, initial.rows)
            assertTrue(initial.sheet.height < picker.availableHeight - 1f, "两条短线路应按自然高度打开，不能强撑全屏")
            assertTrue(initial.viewport.height < MessageLineLayout.viewport.value, "两条短线路不能预留长列表的全部360dp")
            val at = testScheduler.currentTime
            val observed = listOf("合" to 2, "合成" to 2, "合成甲" to 1, "合成甲X" to 0,
                "合成甲" to 1, "合成" to 2, "合" to 2, "" to 2, "合成乙" to 1, "" to 2).map { (query, rows) ->
                picker.setText(query)
                picker.geometry().also {
                    assertEquals(query, it.query, "必须经过真实CommunicationInput SetText")
                    assertEquals(rows, it.rows, "先证实真实筛选内容，不能只比较未变的空壳")
                    assertEquals(rows == 0, it.empty)
                }
            }.toMutableList()
            assertEquals(at, testScheduler.currentTime, "逐字/删除/替换须在同一虚拟时刻交付，不等settle掩盖跳变")
            assertTrue(picker.selections.isEmpty()); assertEquals(0, picker.edits); assertEquals(0, picker.dismisses)
            // 再检查每次变更的0/16/32/48ms在途布局，不允许animateContentSize把跳变变慢后冒几何稳定。
            for ((query, expectedRows) in listOf("合成甲" to 1, "合成甲X" to 0, "合成乙" to 1, "" to 2)) {
                picker.setText(query)
                repeat(4) { frame ->
                    if (frame > 0) picker.environment.advanceFrameBy(16)
                    observed += picker.geometry().also {
                        assertEquals(query, it.query); assertEquals(expectedRows, it.rows)
                        assertEquals(expectedRows == 0, it.empty)
                    }
                }
            }
            observed.forEach { assertStable(initial, it) }
        }
    } }

    @Test fun keyboardBudgetCanReflowButFilteringCannotKeepResizingItsNewViewport() = onMotionTestThread { runTest {
        Picker(testScheduler, height = 520, count = 12, initialIme = 0).use { picker ->
            picker.openAndFocus()
            val withoutKeyboard = picker.geometry()
            picker.setImeBudget(160)
            s4Frames(picker.environment, 80) // 只等外部IME预算变化，不在查询之间推进动画时钟。
            val withKeyboard = picker.geometry()
            assertEquals(360, picker.environment.scene.constraints.maxHeight)
            assertTrue(withKeyboard.sheet.bottom <= picker.availableHeight + .5f)
            assertTrue(withKeyboard.viewport.height < withoutKeyboard.viewport.height,
                "真实小窗口/IME必须能缩减viewport，不能把首次px永久锁死")
            assertTrue(withKeyboard.viewport.height > 0f)
            val at = testScheduler.currentTime
            val observed = listOf("合成甲", "合成甲X", "合成乙", "").map { query ->
                picker.setText(query)
                picker.geometry().also { assertEquals(query, it.query) }
            }
            assertEquals(at, testScheduler.currentTime)
            observed.forEach { assertStable(withKeyboard, it) }
        }
    } }

    @Test fun messageViewportHasOneScrollOwnerAndRealConfirmationAndCancelStillWork() = onMotionTestThread { runTest {
        Picker(testScheduler, count = 12).use { picker ->
            picker.openAndFocus()
            val initial = picker.geometry()
            val scroll = picker.scrollOwner()
            assertTrue(scroll.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f)
            assertTrue(assertNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, 10_000f))
            s4Frames(picker.environment, 80)
            assertTrue(picker.scrollOwner().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
            assertStable(initial, picker.geometry(), "滚动只能移动结果内容")
            picker.setText("合成乙")
            val target = picker.rows().single()
            assertFalse(target.config[SemanticsProperties.Selected])
            s4Tap(picker.environment, target.boundsInRoot.center)
            picker.consumeNow()
            assertEquals(listOf(picker.lines[1].owner), picker.selections)
            assertEquals(picker.lines[1].owner, picker.selectedOwner)
            s4Frames(picker.environment, 80)
            assertFalse(s4HasText(picker.environment, "选择短信记录线路"))
            assertEquals(1, picker.dismisses); assertEquals(0, picker.edits)
        }
        Picker(testScheduler).use { picker ->
            picker.openAndFocus(); picker.cancel()
            assertEquals(1, picker.dismisses); assertTrue(picker.selections.isEmpty())
            assertFalse(s4HasText(picker.environment, "选择短信记录线路"))
        }
    } }

    @Test fun callsUseTheirRealBoundedHistorySheetWithoutInventingASearchField() = onMotionTestThread { runTest {
        Picker(testScheduler, height = 520, count = 12, initialIme = 0, calls = true).use { picker ->
            picker.openAndFocus()
            val before = picker.geometry()
            assertNull(before.search, "当前CallLineEntry没有query输入，不伪造Calls筛选覆盖")
            assertTrue(before.viewport.height in 1f..360f)
            val scroll = picker.scrollOwner()
            assertTrue(scroll.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f)
            assertTrue(assertNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, 10_000f))
            s4Frames(picker.environment, 80)
            assertStable(before, picker.geometry())
            picker.cancel(); assertTrue(picker.selections.isEmpty())
            assertFalse(s4HasText(picker.environment, "选择通话记录线路"))
            picker.openAndFocus()
            val other = picker.rows().single { s4Matches(it, picker.lines[1].lineTitle()) }
            s4Tap(picker.environment, other.boundsInRoot.center); picker.consumeNow()
            assertEquals(listOf(picker.lines[1].owner), picker.selections)
            s4Frames(picker.environment, 80)
            assertFalse(s4HasText(picker.environment, "选择通话记录线路"))
        }
    } }

    private data class Geometry(val sheet: Rect, val search: Rect?, val viewport: Rect,
        val rows: Int, val empty: Boolean, val query: String)

    private fun assertStable(expected: Geometry, actual: Geometry, reason: String = "固定IME下筛选不得移动sheet/搜索框或改变正文viewport") {
        for ((name, pair) in listOf("sheet" to (expected.sheet to actual.sheet), "viewport" to (expected.viewport to actual.viewport))) {
            assertEquals(pair.first.top, pair.second.top, .5f, "$reason：$name top；实际query=${actual.query} geometry=$actual")
            assertEquals(pair.first.height, pair.second.height, .5f, "$reason：$name height；实际query=${actual.query} geometry=$actual")
            assertEquals(pair.first.left, pair.second.left, .5f, "$reason：$name left")
            assertEquals(pair.first.width, pair.second.width, .5f, "$reason：$name width")
        }
        assertEquals(expected.search, actual.search, "$reason：真实搜索输入边界")
    }

    private class Picker(
        scheduler: TestCoroutineScheduler,
        private val height: Int = 844,
        count: Int = 2,
        initialIme: Int = 280,
        private val calls: Boolean = false,
    ) : AutoCloseable {
        val lines = (0 until count).map { index ->
            val owner = assertNotNull(SimOwnerId.parse("sim-owner-v1:" + ('a'.code + index).toChar().toString().repeat(43)))
            MessageHistoryLine(owner, index, false, MessageOwnerLabel(index + 1L,
                when (index) { 0 -> "合成甲"; 1 -> "合成乙"; else -> "合成线路${index + 1}" }))
        }
        var selectedOwner by mutableStateOf(lines.first().owner)
        var imeBottom by mutableIntStateOf(initialIme)
        val availableHeight get() = (height - imeBottom).toFloat()
        val selections = mutableListOf<SimOwnerId>()
        var edits = 0
        var dismisses = 0
        private var shown by mutableStateOf(true)
        private val window = object : WindowInfo {
            override val isWindowFocused = true
            override val containerSize get() = IntSize(390, height - imeBottom)
            override val containerDpSize get() = DpSize(390.dp, (height - imeBottom).dp)
        }
        // Model the host's available content size, including its popup root; a local fake
        // WindowInsets.ime does not resize the library's separate desktop popup scene.
        val environment = MotionTestEnvironment(scheduler, 390, height - initialIme) {
            CompositionLocalProvider(LocalWindowInfo provides window) {
                s4SmsHost {
                    if (calls) CallLineEntry(CallLineState(), "", { select(it) },
                        historyOwners = lines.map { CallHistoryOwnerChoice(it.owner, it.lineTitle(), it.owner == selectedOwner) },
                        onSelectHistory = { select(it.owner) }, onConfirmHistory = { select(it.owner) })
                    else if (shown) MessageLinePicker(MessageHistoryChoices(lines, selectedOwner, true),
                        { dismisses++; shown = false }, { select(it) }, { edits++ }, null,
                        onConfirm = { select(it) })
                }
            }
        }
        private fun select(owner: SimOwnerId): Boolean { selections += owner; selectedOwner = owner; return true }
        private val title get() = if (calls) "选择通话记录线路" else "选择短信记录线路"
        fun setImeBudget(bottom: Int) {
            imeBottom = bottom
            environment.scene.constraints = Constraints.fixed(390, height - bottom)
        }
        fun consumeNow() { repeat(6) { environment.advanceFrameBy(0) } }
        fun openAndFocus() {
            s4Frames(environment, 80)
            if (calls) s4Tap(environment, s4Action(environment, "通话记录线路").boundsInRoot.center)
            s4Frames(environment, 80)
            if (!calls) s4Tap(environment, editor().boundsInRoot.center)
            s4Frames(environment, 80)
            assertEquals(height - imeBottom, environment.scene.constraints.maxHeight,
                "前提：真实场景及popup必须受当前可用窗口预算约束")
            if (!calls) assertEquals(true, editor().config.getOrNull(SemanticsProperties.Focused))
        }
        private fun editor() = s4Nodes(environment, false).single { it.config.contains(SemanticsActions.SetText) }
        fun setText(query: String) {
            assertTrue(assertNotNull(editor().config[SemanticsActions.SetText].action).invoke(AnnotatedString(query)))
            consumeNow()
            if (query.isNotEmpty()) assertFalse(s4HasText(environment, "搜索SIM、备注或历史编号"),
                "保留自然占位高度不能暴露不可见的占位文字")
        }
        fun rows() = s4Nodes(environment, false).filter {
            it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton && it.config.contains(SemanticsActions.OnClick) &&
                it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f
        }
        fun scrollOwner() = s4Nodes(environment, false).single {
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange) && it.config.contains(SemanticsActions.ScrollBy)
        }
        private fun cancelButton() = s4Actions(environment, "取消").single {
            it.config.getOrNull(SemanticsProperties.Role) == Role.Button && !it.config.contains(SemanticsProperties.Disabled)
        }
        fun cancel() { s4Tap(environment, cancelButton().boundsInRoot.center); s4Frames(environment, 80) }
        fun geometry(): Geometry {
            val nodes = s4Nodes(environment, false)
            val heading = nodes.single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == title } == true }
            val cancel = cancelButton()
            val input = if (calls) null else editor()
            val empty = nodes.singleOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "没有匹配的线路" } == true }
            val scrolls = nodes.filter { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) && it.config.contains(SemanticsActions.ScrollBy) }
            assertTrue(scrolls.size <= 1, "一个sheet只能有一个列表scroll owner")
            val body = if (empty != null) empty else scrolls.single()
            val anchors = listOf(heading, cancel, body) + listOfNotNull(input)
            val paths = anchors.map { node -> generateSequence(node.layoutInfo) { it.parentInfo }.toList() }
            // 此共同祖先是CommunicationSheet的真实内容Column；不是Scene root、标题Text或额外假面板。
            val sheet: LayoutInfo = paths.first().first { candidate -> paths.drop(1).all { branch -> branch.any { it === candidate } } }
            assertNotNull(sheet.parentInfo)
            val sheetBounds = sheet.coordinates.boundsInRoot()
            assertTrue(sheetBounds.height in 1f..640.5f && sheetBounds.width <= 390.5f)
            assertTrue(sheetBounds.top >= 0f && sheetBounds.bottom <= availableHeight + .5f,
                "前提：实际sheet内容须位于当前窗口/IME上方，而非整页bounds：$sheetBounds available=$availableHeight")
            var viewport = body.layoutInfo
            while (viewport.parentInfo !== sheet) viewport = assertNotNull(viewport.parentInfo)
            val viewportBounds = viewport.coordinates.boundsInRoot()
            assertTrue(viewportBounds.height in 1f..360.5f)
            val query = input?.config?.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
            return Geometry(sheetBounds, input?.boundsInRoot, viewportBounds, rows().size, empty != null, query).also {
                println("HISTORY_LINE_GEOMETRY|calls=$calls|ime=$imeBottom|$it")
            }
        }
        override fun close() { environment.close() }
    }
}
