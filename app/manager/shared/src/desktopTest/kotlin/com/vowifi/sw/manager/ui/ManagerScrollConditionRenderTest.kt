package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Real screen conditions and list semantics; no test-only reset state machine. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ManagerScrollConditionRenderTest {
    @Test fun callsConfirmedOwnerChangeReturnsToTop() = ownerChange(Kind.CALLS)
    @Test fun messagesConfirmedOwnerChangeReturnsToTop() = ownerChange(Kind.MESSAGES)

    private fun ownerChange(kind: Kind) = onMotionTestThread { runTest {
        Driver(testScheduler, kind).use { d ->
            d.frames(8)
            val initialAxis = d.axis().value()
            val initialAnchors = d.visibleAnchorPositions()
            assertEquals(listOf("1550000000", "1550000001"), initialAnchors.map { it.first }, "初始顶位必须真实显示首两条记录，不能空集合自证")
            d.scrollDown()
            assertNotEquals(initialAnchors, d.visibleAnchorPositions(), "须先实际离开初始可见几何")
            println(d.diagnostics("before-confirm"))
            assertTrue(d.confirmOwner(ownerB), "此driver直接发布受控已确认Ready投影，不从Unit请求推导成功")
            assertEquals(ownerB, d.selectedOwner)
            assertEquals(1, d.selections, "确认owner只选择一次")
            d.frames(8)
            println(d.diagnostics("after-confirm-8-frames"))
            assertEquals(ownerB, d.composedInputOwner, "前提：生产Screen已消费本次已确认owner输入")
            println("SCROLL_TOP_BASELINE|kind=$kind|initialAxis=$initialAxis|afterAxis=${d.axis().value()}|initialAnchors=$initialAnchors|afterAnchors=${d.visibleAnchorPositions()}")
            assertEquals(initialAnchors, d.visibleAnchorPositions(), "切线后实际首批可见记录及其几何须恢复初始顶位")
            // Lazy's semantic axis includes zero-height notice slots; its top is not always numeric zero.
            assertEquals(initialAxis, d.axis().value(), "已确认的线路改变必须恢复实际初始顶位，不能把语义轴估值当像素偏移")
        }
    } }

    @Test fun callsUnknownOwnerDoesNotResetPosition() = unknownOwner(Kind.CALLS)
    @Test fun callsUnknownOwnerDoesNotClearSearch() = onMotionTestThread { runTest {
        Driver(testScheduler, Kind.CALLS).use { d ->
            d.frames(8)
            d.enterCallQuery("155")
            d.scrollDown()
            val before = d.axis().value()
            d.owner = null
            d.frames(8)
            assertEquals("155", d.queryText(), "暂缺 owner 不能清空用户查询并间接触发回顶")
            assertEquals(before, d.axis().value())
        }
    } }

    private fun unknownOwner(kind: Kind) = onMotionTestThread { runTest {
        Driver(testScheduler, kind).use { d ->
            d.scrollDown()
            val before = d.axis().value()
            d.owner = null
            d.frames(8)
            assertEquals(before, d.axis().value(), "暂缺 owner 不代表选线")
            d.owner = ownerA
            d.frames(8)
            assertEquals(before, d.axis().value(), "原 owner 恢复不能被当成选线")
        }
    } }

    @Test fun callsRefreshAndInactiveReturnKeepPosition() = refresh(Kind.CALLS)
    @Test fun messagesRefreshAndInactiveReturnKeepPosition() = refresh(Kind.MESSAGES)
    @Test fun logsRefreshAndInactiveReturnKeepPosition() = refresh(Kind.LOGS)

    private fun refresh(kind: Kind) = onMotionTestThread { runTest {
        Driver(testScheduler, kind).use { d ->
            d.scrollDown()
            val before = d.axis().value()
            d.active = false
            if (kind == Kind.LOGS) d.cached = true else d.count += 5
            d.frames(8)
            d.active = true
            d.frames(8)
            assertEquals(before, d.axis().value(), "后台更新、分页追加和返回不能重置滚动")
        }
    } }

    @Test fun logsKindChangeReturnsToTop() = logFilter(OperationalLogFilter(kind = OperationalLogKind.ENGINE))
    @Test fun logsLevelChangeReturnsToTop() = logFilter(OperationalLogFilter(level = OperationalLogLevel.INFO))
    @Test fun logsInstanceChangeReturnsToTop() = logFilter(OperationalLogFilter(displayLabel = "测试线路"))

    private fun logFilter(filter: OperationalLogFilter) = onMotionTestThread { runTest {
        Driver(testScheduler, Kind.LOGS).use { d ->
            d.scrollDown()
            d.filter = filter
            d.frames(8)
            assertEquals(0f, d.axis().value(), "日志筛选改变必须回顶，即便筛选后同一批条目仍匹配")
        }
    } }

    private enum class Kind { CALLS, MESSAGES, LOGS }

    private class Driver(scheduler: TestCoroutineScheduler, private val kind: Kind) : AutoCloseable {
        var owner by mutableStateOf<SimOwnerId?>(ownerA)
        // 此driver没有选线请求入口；owner就是受控的源已确认投影，不从Unit回调推定确认。
        val selectedOwner: SimOwnerId? get() = owner
        var selections = 0
            private set
        var composedInputOwner: SimOwnerId? = null
            private set
        var count by mutableStateOf(80)
        var active by mutableStateOf(true)
        var filter by mutableStateOf(OperationalLogFilter())
        var cached by mutableStateOf(false)
        private val back = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val window = object : WindowInfo {
            override val isWindowFocused = true
            override val containerSize = IntSize(390, 520)
            override val containerDpSize = DpSize(390.dp, 520.dp)
        }
        private val environment = MotionTestEnvironment(scheduler, width = 390, height = 520) {
            CompositionLocalProvider(LocalWindowInfo provides window, LocalLifecycleOwner provides lifecycle,
                LocalNavigationEventDispatcherOwner provides back, LocalAppRouteActive provides active) {
                SoftVoWiFiTheme(false) {
                    val inputOwner = selectedOwner
                    SideEffect { composedInputOwner = inputOwner }
                    when (kind) {
                        Kind.CALLS -> CallsScreen(
                            state = CallUiState.Loading, microphonePermissionGranted = true,
                            onRequestMicrophonePermission = {},
                            history = CallHistoryState.Ready(List(count) { index ->
                                CallRecord(CallDirection.INCOMING, CallPeerPhone.parse("155000${index.toString().padStart(4, '0')}"),
                                    1_700_000_000_000L - index * 1000L, null, null, null, CallOutcome.MISSED,
                                    CallHistoryRecordId("test-$index"))
                            }, inputOwner),
                            onOpenDialpad = {}, onClearHistory = {}, onAnswer = {}, onReject = {}, onHangup = {},
                        )
                        Kind.MESSAGES -> MessagesScreen(
                            state = MessageUiState.Ready(List(count) { index ->
                                MessageRecord.Incoming("test-$index", "155000${index.toString().padStart(4, '0')}",
                                    "测试条目", 1_700_000_000_000L - index * 1000L, isRead = true)
                            }, MessageOrigin.ENGINE, ownerId = inputOwner),
                            // 已确认的历史owner同时带来源已发布的线路；旧空默认会留下5个零高L7槽，
                            // 令初始ScrollAxisRange本就为2500，不能把它误判成回顶失败。
                            historyChoices = MessageHistoryChoices(
                                lines = listOf(MessageHistoryLine(ownerA, count, false), MessageHistoryLine(ownerB, count, false)),
                                selectedOwner = inputOwner, storageAvailable = true),
                            onOpenThread = {}, onNewMessage = {},
                        )
                        Kind.LOGS -> SettingsLogsScreen(
                            OperationalLogUiState.Ready(List(count) { index ->
                                OperationalLogEntry("2026-10-14 10:${(index / 60).toString().padStart(2, '0')}:${(index % 60).toString().padStart(2, '0')}",
                                    OperationalLogLevel.INFO, OperationalLogKind.ENGINE, "测试线路",
                                    OperationalLogMessage.EVENT, "测试条目 $index")
                            }, selectedFileId = "test-file", filter = filter, loadInfo = OperationalLogLoadInfo(cached = cached)),
                            SettingsLogsActions({}, {}, {}, {}, {}), exportBusy = false,
                        )
                    }
                }
            }
        }

        fun frames(count: Int) { repeat(count) { environment.advanceFrameBy() } }
        private fun nodes(): List<SemanticsNode> {
            fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
            return environment.scene.semanticsOwners.flatMap { visit(it.rootSemanticsNode) }
        }
        private fun list(): SemanticsNode = nodes().first {
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange) && it.config.contains(SemanticsActions.ScrollBy)
        }
        fun enterCallQuery(text: String) {
            val search = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("搜索通话记录") == true &&
                it.config.contains(SemanticsActions.OnClick) }
            assertTrue(search.config[SemanticsActions.OnClick].action!!.invoke())
            frames(20)
            assertTrue(nodes().first { it.config.contains(SemanticsActions.SetText) }
                .config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text)))
            frames(8)
            assertEquals(text, queryText())
        }
        fun queryText(): String? = nodes().firstOrNull { it.config.contains(SemanticsActions.SetText) }
            ?.config?.getOrNull(SemanticsProperties.EditableText)?.text
        fun axis(): ScrollAxisRange = list().config[SemanticsProperties.VerticalScrollAxisRange]
        fun visibleAnchorPositions(): List<Pair<String, Float>> {
            val viewport = list().boundsInRoot
            return nodes().filter { node -> node.boundsInRoot.top >= viewport.top && node.boundsInRoot.bottom <= viewport.bottom }
                .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().filter { it.text.startsWith("155000") }
                    .map { it.text to node.boundsInRoot.top } }.distinct().sortedBy { it.second }.take(2)
        }
        fun confirmOwner(target: SimOwnerId): Boolean {
            selections++
            owner = target
            return selectedOwner == target
        }
        fun diagnostics(phase: String): String {
            val viewport = list().boundsInRoot
            val anchors = nodes().filter { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("155000") } == true &&
                    node.boundsInRoot.top >= viewport.top && node.boundsInRoot.bottom <= viewport.bottom
            }.sortedBy { it.boundsInRoot.top }.take(2).map { "${it.id}@${it.boundsInRoot.top}" }
            fun ownerLabel(value: SimOwnerId?) = when (value) { ownerA -> "A"; ownerB -> "B"; else -> "unknown" }
            return "SCROLL_CONDITION|phase=$phase|kind=$kind|owner=${ownerLabel(selectedOwner)}|composedOwner=${ownerLabel(composedInputOwner)}|axis=${axis().value()}|max=${axis().maxValue()}|list=${list().id}@$viewport|anchors=$anchors|selections=$selections"
        }
        fun scrollDown() {
            frames(8)
            println(diagnostics("before-scroll"))
            assertTrue(list().config[SemanticsActions.ScrollBy].action!!.invoke(0f, 600f))
            frames(40)
            println(diagnostics("after-scroll-40-frames"))
            assertTrue(axis().value() > 0f, "须先真实滚动生产列表")
        }
        override fun close() { environment.close(); back.navigationEventDispatcher.dispose() }
    }

    private companion object {
        val ownerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val ownerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
    }
}
