package com.vowifi.sw.manager.ui

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.ConfigurationProvisionState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.PullToRefreshState
import top.yukonga.miuix.kmp.basic.RefreshState
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 真实 HomeScreen/AppPageScaffold 的指针/接线门禁；原达阈值 RED 由统筹留证。
 * held 测试源独立拥有 producer，不以 delay 冒充完成；新接线尚未运行。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class S3HomeRefreshEntryDraftTest {
    @Test
    fun homePullBelowPinnedThresholdDoesNotRequestRefresh() = onMotionTestThread { runTest {
        HomeEntryDriver(testScheduler).use { driver ->
            driver.frames(30)
            driver.tapRefresh()
            driver.frames(30)
            assertEquals(1, driver.refreshCalls, "对照：同一真实首页刷新按钮必须命中现有回调")
            driver.untilResting()
            driver.assertAtTop()
            val before = driver.refreshCalls

            driver.beginDownwardDrag(36f)
            driver.assertRealDownwardInput()
            assertTrue(driver.pullProgress() > 0f && driver.pullProgress() < 1f,
                "持指必须确实未到真实库阈值：${driver.refreshTrace()}")
            assertEquals(before, driver.refreshCalls, "未抬起不得刷新")
            driver.finishDrag()
            driver.frames(120)

            assertEquals(before, driver.refreshCalls, "R5：未达固定库阈值的真实下拉不请求；${driver.refreshTrace()}")
            assertEquals(0, driver.otherActions)
        }
    } }

    @Test
    fun homePullPastPinnedThresholdRequestsExactlyOnceAfterRelease() = onMotionTestThread { runTest {
        HomeEntryDriver(testScheduler).use { driver ->
            driver.frames(30)
            driver.tapRefresh()
            driver.frames(30)
            assertEquals(1, driver.refreshCalls, "按钮阳性对照，不能把回调未接当下拉缺陷")
            driver.untilResting()
            driver.assertAtTop()
            val before = driver.refreshCalls

            driver.beginDownwardDrag(180f)
            driver.assertRealDownwardInput()
            assertEquals(1f, driver.pullProgress(), "达阈值前提：${driver.refreshTrace()}")
            assertEquals(before, driver.refreshCalls, "达阈值但仍持指不得提交")
            driver.finishDrag()
            driver.frames(120) // 仅给库释放/回弹呈现推进帧，不代替请求或完成事件。

            assertEquals(before + 1, driver.refreshCalls,
                "R5 旧入口候选：Home 顶端达阈值下拉须调用现有 onRefresh 恰好一次")
            assertEquals(0, driver.otherActions)
        }
    } }

    @Test
    fun heldHomeReadDisablesTheButtonAndThePullGestureCannotStartASecondRequest() = onMotionTestThread { runTest {
        HomeEntryDriver(testScheduler).use { driver ->
            driver.frames(30)
            driver.holdReads = true
            val restingOccupation = driver.pullOccupation()
            driver.beginDownwardDrag(180f)
            driver.assertRealDownwardInput()
            driver.finishDrag()
            driver.until("真实达阈值松手必须启动held producer") { driver.refreshCalls == 1 }
            assertTrue(driver.producer?.isActive == true)
            assertTrue(driver.refreshButton().config.contains(SemanticsProperties.Disabled))
            val contentOwner = driver.listIdentity()
            driver.untilPullOccupationStable()
            val indicatorOwner = driver.pullIndicator().id
            val refreshingOccupation = driver.pullOccupation()
            assertTrue(driver.producer?.isActive == true, driver.refreshTrace())
            assertEquals(contentOwner, driver.listIdentity(), "库到位过程中保持同一列表拥有者")
            assertEquals(1f, driver.pullProgress(), "到位后仍是实际库进度：${driver.refreshTrace()}")
            assertTrue(refreshingOccupation > restingOccupation + 8f,
                "库指示须实际占据内容上方空间，而非仅给按钮busy：${driver.refreshTrace()}")
            assertEquals("正在刷新", driver.pullIndicator().config.getOrNull(SemanticsProperties.StateDescription))
            driver.provisioning.value = ConfigurationProvisionState(pending = setOf(SimHandle("test-sim")))
            driver.untilPullOccupationStable()
            assertTrue(driver.producer?.isActive == true, driver.refreshTrace())
            assertEquals(contentOwner, driver.listIdentity(), "创建进度不替换movable内容拥有者")
            assertEquals(indicatorOwner, driver.pullIndicator().id, "创建进度不卸载/重建库指示节点")
            assertEquals(refreshingOccupation, driver.pullOccupation(), 1f,
                "创建进度不能卸载库指示的真实空间：${driver.refreshTrace()}")
            assertEquals(1f, driver.pullProgress(), "指示仍在库真实阈值：${driver.refreshTrace()}")
            assertEquals("正在刷新", driver.pullIndicator().config.getOrNull(SemanticsProperties.StateDescription),
                "创建进度不能卸载本次用户指示的可访问语义")
            driver.tapRefresh()
            driver.frames(8)
            driver.beginDownwardDrag(180f)
            driver.finishDrag()
            driver.frames(12)
            assertEquals(1, driver.refreshCalls, "按钮/下拉共享一轮，不重复真实读取")
            driver.provisioning.value = ConfigurationProvisionState()
            driver.releaseRead(RefreshResult.Completed)
            driver.frames(100)
            assertFalse(driver.refreshButton().config.contains(SemanticsProperties.Disabled))
            assertEquals(1, driver.refreshCalls)
            assertEquals(0, driver.otherActions)
        }
    } }

    @Test
    fun leavingRealHomeCancelsItsWaiterButTheHeldProducerCanStillFinish() = onMotionTestThread { runTest {
        HomeEntryDriver(testScheduler).use { driver ->
            driver.frames(30)
            driver.holdReads = true
            driver.tapRefresh()
            driver.frames(8)
            assertEquals(1, driver.refreshCalls)
            assertTrue(driver.producer?.isActive == true)
            driver.showHome = false
            driver.frames(2)
            assertTrue(driver.producer?.isActive == true)
            driver.releaseRead(RefreshResult.Completed)
            testScheduler.runCurrent()
            assertTrue(driver.producer?.isCompleted == true)
            assertFalse(driver.producer?.isCancelled == true)
            assertEquals(1, driver.refreshCalls)
        }
    } }

    @Test
    fun failedHomeRefreshPersistsInlineAndItsRealRetryRunsTheSameEntry() = onMotionTestThread { runTest {
        HomeEntryDriver(testScheduler).use { driver ->
            driver.frames(30)
            driver.nextResult = RefreshResult.Failed("测试读取失败")
            driver.tapRefresh()
            driver.frames(160)
            assertEquals(1, driver.refreshCalls)
            assertTrue(driver.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "刷新未完成" } == true })
            val retry = driver.nodes().single {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "重试刷新" } == true
            }
            driver.nextResult = RefreshResult.Completed
            val point = retry.boundsInRoot.center
            driver.environment.press(point); driver.frames(2); driver.environment.release(point)
            driver.frames(100)
            assertEquals(2, driver.refreshCalls)
            assertFalse(driver.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "刷新未完成" } == true })
            assertEquals(0, driver.otherActions)
        }
    } }

    @Test
    fun automaticStatePublicationDoesNotRotateOrInvokeHomeRefreshAction() = onMotionTestThread { runTest {
        HomeEntryDriver(testScheduler).use { driver ->
            driver.frames(30)
            val bounds = driver.refreshButton().boundsInRoot
            val original = driver.refreshPixels()
            assertTrue(original.distinct().size > 2, "像素前提：实际刷新按钮不能是空白区域")

            // 使用既有输入状态模拟自动发布，不调用用户刷新，不虚构 busy/新控制器参数。
            driver.registration = RegistrationUiState.Loading
            listOf(5, 7, 9).forEach { frameCount ->
                driver.frames(frameCount)
                assertEquals(bounds, driver.refreshButton().boundsInRoot)
                assertContentEquals(original, driver.refreshPixels(), "C15：背景发布不得旋转用户刷新图标")
            }
            driver.registration = RegistrationUiState.Empty
            driver.frames(20)
            assertContentEquals(original, driver.refreshPixels())
            assertEquals(0, driver.refreshCalls)
            assertEquals(0, driver.otherActions)
        }
    } }

    @Test
    fun shortManagementPageKeepsOneExplicitOverscrollAndNoThemeEffect() = onMotionTestThread { runTest {
        HomeEntryDriver(testScheduler, shortPage = true).use { driver ->
            driver.frames(20)
            assertEquals(0f, driver.axis().maxValue(), .1f, "前提：真实短页没有可滚动范围")
            assertEquals(1, driver.overscrollOwners(), "S2 守护：主列表路径只能有一个 miuix 回弹节点")
            assertEquals(0, driver.themeEffectCreations)
            val originalTop = driver.shortItemTop()

            driver.beginDownwardDrag(96f)
            driver.assertRealDownwardInput()
            driver.frames(2)
            assertTrue(driver.shortItemTop() > originalTop + 8f, "短页不能因 range=0 丢失实际回弹位移")
            assertEquals(1, driver.overscrollOwners())
            assertEquals(0, driver.themeEffectCreations, "不得再挂 Foundation/主题第二层回弹")
            driver.finishDrag()
            driver.frames(120)
            assertEquals(originalTop, driver.shortItemTop(), 1f)
        }
    } }

    internal class HomeEntryDriver(
        private val scheduler: TestCoroutineScheduler,
        shortPage: Boolean = false,
        handoffProbeOnly: Boolean = false,
    ) : AutoCloseable {
        var registration by mutableStateOf<RegistrationUiState>(RegistrationUiState.Empty)
        var refreshCalls = 0
        var otherActions = 0
        var themeEffectCreations = 0
        var showHome by mutableStateOf(true)
        val provisioning = MutableStateFlow(ConfigurationProvisionState())
        var holdReads = false
        var nextResult: RefreshResult = RefreshResult.Completed
        var producer: Deferred<RefreshResult>? = null
            private set
        var requestStartedAtMillis: Long? = null
            private set
        var readCompletedAtMillis: Long? = null
            private set
        var completedResult: RefreshResult? = null
            private set
        private var probeState: PullToRefreshState? = null
        private var probeController: ManagerRefreshController? = null
        private var probeIndicator: State<Boolean>? = null
        private val producerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
        private val readGate = CompletableDeferred<RefreshResult>()
        private var restingContentTop: Float? = null
        private suspend fun refreshHome(): RefreshResult {
            producer?.takeIf { it.isActive }?.let { return it.await() }
            val read = producerScope.async {
                refreshCalls++
                requestStartedAtMillis = scheduler.currentTime
                val result = if (holdReads) readGate.await() else nextResult
                completedResult = result
                readCompletedAtMillis = scheduler.currentTime
                result
            }
            producer = read
            return read.await()
        }
        fun releaseRead(result: RefreshResult) { readGate.complete(result) }
        private var pointerDowns = 0
        private var pressedMoves = 0
        private var positiveUserScrolls = 0
        private var lastPoint = Offset.Zero
        private var lastDragStart = Offset.Zero
        private var lastDragDistance = 0f
        private var lastMoveMillis = 0L
        private var releaseMillis = 0L
        private var releaseVelocity: Velocity? = null
        private val back = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
                currentState = Lifecycle.State.RESUMED
            }
        }
        private val window = object : WindowInfo {
            override val isWindowFocused = true
            override val containerSize = IntSize(390, 800)
            override val containerDpSize = DpSize(390.dp, 800.dp)
        }
        private val trace = object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) positiveUserScrolls++
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                releaseVelocity = available
                return Velocity.Zero
            }
        }
        private val themeFactory = object : OverscrollFactory {
            override fun createOverscrollEffect(): OverscrollEffect {
                themeEffectCreations++
                return object : OverscrollEffect {
                    override val isInProgress = false
                    override val node = object : Modifier.Node() {}
                    override fun applyToScroll(delta: Offset, source: NestedScrollSource,
                        performScroll: (Offset) -> Offset): Offset = performScroll(delta)
                    override suspend fun applyToFling(velocity: Velocity,
                        performFling: suspend (Velocity) -> Velocity) { performFling(velocity) }
                }
            }
            override fun equals(other: Any?) = other === this
            override fun hashCode() = 571
        }
        val environment = MotionTestEnvironment(scheduler, width = 390, height = 800) {
            CompositionLocalProvider(
                LocalWindowInfo provides window,
                LocalLifecycleOwner provides lifecycle,
                LocalNavigationEventDispatcherOwner provides back,
            ) {
                SoftVoWiFiTheme(false) {
                    // 放在主题内部，避免库主题覆盖哨兵 factory 而得到虚假的创建0次。
                    CompositionLocalProvider(LocalOverscrollFactory provides themeFactory) {
                        Box(Modifier.fillMaxSize().nestedScroll(trace).pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type == PointerEventType.Press) pointerDowns++
                                    if (event.type == PointerEventType.Move && event.changes.any { it.pressed }) pressedMoves++
                                }
                            }
                        }) {
                            if (handoffProbeOnly) {
                                // Diagnostic counterpart of the production bridge: actual public
                                // library + actual presentation/controller helpers, no copied state machine.
                                // This exposes interface/desktop rendering signals, not phone imagery.
                                val controller = rememberManagerRefreshController(::refreshHome)
                                val shown = rememberManagerRefreshIndicator(controller)
                                val state = rememberPullToRefreshState()
                                SideEffect { probeState = state; probeController = controller; probeIndicator = shown }
                                PullToRefresh(
                                    isRefreshing = shown.value,
                                    onRefresh = { controller.request() },
                                    pullToRefreshState = state,
                                    modifier = Modifier.fillMaxSize().semantics {
                                        contentDescription = "下拉刷新进度"
                                        progressBarRangeInfo = ProgressBarRangeInfo(state.pullProgress, 0f..1f)
                                    },
                                    refreshTexts = managerRefreshTexts(controller.result),
                                ) {
                                    LazyColumn(Modifier.fillMaxSize().managerVerticalScrollFeedback(), overscrollEffect = null) {
                                        items(4) { Text("交接探针条目 $it", Modifier.fillMaxWidth().height(64.dp)) }
                                    }
                                }
                            } else if (shortPage) {
                                AppPageScaffold("S3 短页守护", showTopBar = false) {
                                    item { Text("S3 短页首项", Modifier.fillMaxWidth().height(64.dp)) }
                                }
                            } else if (showHome) {
                                HomeScreen(
                                    state = registration,
                                    engineState = SimControlUiState.Ready(emptyList()),
                                    diagnosticsState = EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.PASS, emptyList()),
                                    onRefresh = ::refreshHome,
                                    onStartEngine = { otherActions++ },
                                    onStopEngine = { otherActions++ },
                                    onProvision = { otherActions++ },
                                    onOpenNotice = { otherActions++ },
                                    provisioning = provisioning,
                                )
                            } else Text("已离开首页")
                        }
                    }
                }
            }
        }
        fun frames(count: Int) { repeat(count) { environment.advanceFrameBy() } }
        fun handoffFrame(milliseconds: Long = 0): HandoffFrame {
            environment.advanceFrameBy(milliseconds)
            fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
            val unmerged = environment.scene.semanticsOwners.flatMap { tree(it.unmergedRootSemanticsNode) }
            val labels = unmerged.mapNotNull { node ->
                val bounds = node.boundsInRoot
                if (bounds.width <= 0f || bounds.height <= 0f || bounds.bottom <= 0f || bounds.top >= 800f) null
                else node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }
                    ?.filter { it in HANDOFF_LABELS }?.takeIf { it.isNotEmpty() }?.let { it to bounds.toString() }
            }
            return HandoffFrame(scheduler.currentTime, refreshCalls, producer?.isActive == true,
                readCompletedAtMillis, completedResult, pullOccupation(), listIdentity(),
                labels.flatMap { it.first }.toSet(), labels.joinToString(),
                probeState?.refreshState, probeController?.running, probeIndicator?.value)
        }
        fun nodes(): List<SemanticsNode> {
            fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
            return environment.scene.semanticsOwners.flatMap { tree(it.rootSemanticsNode) }
        }
        private fun list() = nodes().single { it.config.contains(SemanticsActions.ScrollToIndex) }
        fun listTop() = list().boundsInRoot.top
        fun listIdentity() = list().id
        // The library header separates its wrapper from this one real list. Do not
        // compare absolute viewport tops while the TopBar/root may also be moving.
        fun pullOccupation() = listTop() - pullIndicator().boundsInRoot.top
        fun untilPullOccupationStable() {
            var previous: Float? = null
            var stableFrames = 0
            until("真实库相对占位须连续3帧delta<0.1px，不用夹紧progress推断spring结束") {
                val occupation = pullOccupation()
                val last = previous
                stableFrames = if (last != null && kotlin.math.abs(occupation - last) < .1f) stableFrames + 1 else 0
                previous = occupation
                stableFrames >= 3
            }
        }
        fun pullIndicator() = nodes().single {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("下拉刷新进度") == true
        }
        fun pullProgress() = pullIndicator().config[SemanticsProperties.ProgressBarRangeInfo].current
        fun refreshTrace() = "t=${scheduler.currentTime}ms progress=${pullProgress()} viewport=${list().boundsInRoot} " +
            "pullBounds=${pullIndicator().boundsInRoot} occupation=${pullOccupation()} " +
            "contentTop=${firstContentTop()} contentInViewport=${firstContentTop() - listTop()} " +
            "buttonState=${nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("刷新首页状态") == true }?.config?.getOrNull(SemanticsProperties.StateDescription)} " +
            "producerActive=${producer?.isActive} start=$lastDragStart distance=$lastDragDistance end=$lastPoint " +
            "lastMove=$lastMoveMillis release=$releaseMillis velocity=$releaseVelocity"
        fun axis() = list().config[SemanticsProperties.VerticalScrollAxisRange]
        fun assertAtTop() { assertEquals(0f, axis().value(), .1f, "前提：首页真实 LazyColumn 位于顶端") }
        fun refreshButton() = nodes().single {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("刷新首页状态") == true &&
                it.config.contains(SemanticsActions.OnClick)
        }
        fun tapRefresh() {
            if (restingContentTop == null) restingContentTop = firstContentTop()
            val point = refreshButton().boundsInRoot.center
            environment.press(point); frames(2); environment.release(point)
        }
        private fun firstContentTop(): Float = list().children.first().boundsInRoot.top
        fun until(message: String, predicate: () -> Boolean) {
            repeat(180) { if (predicate()) return; frames(1) }
            assertTrue(predicate(), "$message；${refreshTrace()}")
        }
        fun untilResting() = until("按钮呈现收尾后恢复真实内容位置再执行下一手势") {
            kotlin.math.abs(firstContentTop() - requireNotNull(restingContentTop)) < 1f &&
                !refreshButton().config.contains(SemanticsProperties.Disabled) && pullProgress() == 0f
        }
        fun beginDownwardDrag(distance: Float) {
            assertAtTop()
            pointerDowns = 0; pressedMoves = 0; positiveUserScrolls = 0
            val bounds = list().boundsInRoot
            // AppPageScaffold 的大标题覆盖列表原点，不能用 top+48 命中顶栏代替列表。
            val start = Offset(bounds.center.x, bounds.top + 220f)
            val titleBottom = nodes().filter {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "SoftVoWiFi" } == true
            }.maxOfOrNull { it.boundsInRoot.bottom } ?: 0f
            assertTrue(start.y > titleBottom + 8f, "手势起点须在真实TopBar文字下方：start=$start titleBottom=$titleBottom bounds=$bounds")
            assertTrue(start.y + distance < bounds.bottom - 8f, "真实拖动轨迹必须位于列表内")
            lastDragStart = start
            lastDragDistance = distance
            releaseVelocity = null
            environment.press(start); frames(1)
            repeat(4) { index ->
                lastPoint = start + Offset(0f, distance * (index + 1) / 4f)
                lastMoveMillis = scheduler.currentTime
                environment.move(lastPoint); frames(1)
            }
        }
        fun assertRealDownwardInput() {
            assertEquals(1, pointerDowns, "前提：生产布局指针链确实收到 DOWN")
            assertTrue(pressedMoves >= 4, "前提：持指 MOVE 确实进入真实布局")
            assertTrue(positiveUserScrolls > 0, "前提：Foundation 过 slop 后确实分发向下 UserInput")
        }
        fun finishDrag() {
            frames(12)
            releaseMillis = scheduler.currentTime
            environment.release(lastPoint)
        }
        fun shortItemTop() = nodes().single {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "S3 短页首项" } == true
        }.boundsInRoot.top
        fun overscrollOwners(): Int = generateSequence(list().layoutInfo) { it.parentInfo }
            .flatMap { it.getModifierInfo().asSequence() }
            .count { it.modifier.javaClass.name == "top.yukonga.miuix.kmp.utils.OverscrollElement" }
        fun refreshPixels(): IntArray {
            val bounds = refreshButton().boundsInRoot
            val left = floor(bounds.left).toInt().coerceAtLeast(0)
            val top = floor(bounds.top).toInt().coerceAtLeast(0)
            val right = ceil(bounds.right).toInt().coerceAtMost(390)
            val bottom = ceil(bounds.bottom).toInt().coerceAtMost(800)
            assertTrue(right > left && bottom > top)
            return environment.scene.render(scheduler.currentTime * 1_000_000L).use { image ->
                Bitmap.makeFromImage(image).use { bitmap ->
                    IntArray((right - left) * (bottom - top)) { index ->
                        bitmap.getColor(left + index % (right - left), top + index / (right - left))
                    }
                }
            }
        }
        override fun close() { environment.close(); producerScope.cancel(); back.navigationEventDispatcher.dispose() }
        internal data class HandoffFrame(
            val timeMillis: Long,
            val requests: Int,
            val producerActive: Boolean,
            val completedAtMillis: Long?,
            val completedResult: RefreshResult?,
            val occupation: Float,
            val listOwner: Int,
            val texts: Set<String>,
            val textBounds: String,
            val libraryState: RefreshState?,
            val controllerRunning: Boolean?,
            val indicatorLevel: Boolean?,
        )
        private companion object {
            val HANDOFF_LABELS = setOf("下拉刷新", "松开刷新", "正在刷新", "刷新完成", "刷新未完成")
        }
    }
}
