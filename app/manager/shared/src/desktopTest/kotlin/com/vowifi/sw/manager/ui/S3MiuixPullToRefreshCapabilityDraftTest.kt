package com.vowifi.sw.manager.ui

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.PullToRefreshState
import top.yukonga.miuix.kmp.basic.RefreshState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 固定 miuix 0.9.3 公开能力门禁，非 Android 后端完成证明。
 * 仅操纵公开 level 与真实指针；不访问库 internal，不复制刷新状态机。
 * 原枚举复位前提已改为公开偏移/内容位置与再次手势；修后由统筹统一验证。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class S3MiuixPullToRefreshCapabilityDraftTest {
    @Test
    fun belowThresholdReleaseDoesNotInvokeRefresh() = onMotionTestThread { runTest {
        PullCapabilityDriver(testScheduler).use { driver ->
            driver.frames(12)
            driver.beginDrag(36f)
            val state = driver.state()
            assertEquals(RefreshState.Pulling, state.refreshState, "真实手势已进入库，不是未命中")
            assertTrue(state.pullProgress > 0f && state.pullProgress < 1f)
            assertEquals(0, driver.releaseCallbacks)
            driver.finishDrag()
            // 固定库的 fling/release 可留下 Pulling enum；可见复位用公开偏移/布局，
            // 并以第二次真实手势证明它没有卡住。不能调用 internal resetToIdle。
            driver.until("未达阈值应收起真实指示并恢复内容位置") {
                abs(state.dragOffset) < .5f && state.pullProgress == 0f &&
                    abs(driver.firstItemTop() - driver.restingItemTop()) < 1f
            }
            assertEquals(0, driver.releaseCallbacks)
            assertEquals(0, driver.acceptedRequests)
            assertTrue(driver.haptics.isEmpty(), "未达阈值不能发阈值触感")
            driver.beginDrag(180f)
            assertEquals(RefreshState.ThresholdReached, state.refreshState)
            driver.finishDrag()
            driver.until("复位后再次达阈值必须仍能刷新") { driver.acceptedRequests == 1 }
            assertEquals(1, driver.releaseCallbacks)
            assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), driver.haptics)
        }
    } }

    @Test
    fun thresholdReleaseFiresOnceAndRefreshingLevelClosesTheLibraryGate() = onMotionTestThread { runTest {
        PullCapabilityDriver(testScheduler).use { driver ->
            driver.frames(12)
            driver.beginDrag(180f)
            assertEquals(RefreshState.ThresholdReached, driver.state().refreshState)
            assertEquals(1f, driver.state().pullProgress)
            assertEquals(0, driver.releaseCallbacks, "持指达阈值尚未请求")
            assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), driver.haptics)
            driver.finishDrag()
            driver.until("真实释放必须调用库回调") { driver.acceptedRequests == 1 }
            driver.until("外部 level 必须驱动 Refreshing") { driver.state().refreshState == RefreshState.Refreshing }
            assertTrue(driver.refreshing)
            val listPosition = driver.listPosition()

            driver.beginDrag(-96f)
            driver.finishDrag()
            driver.frames(20)
            driver.beginDrag(180f)
            driver.finishDrag()
            driver.frames(20)
            assertEquals(listPosition, driver.listPosition(), "固定库 Refreshing 阶段消费滚动，不重复派发")
            assertEquals(1, driver.releaseCallbacks)
            assertEquals(1, driver.acceptedRequests)
            assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), driver.haptics)

            // 外部明确结束 level；没有按时间假定请求已完成，也不返回虚构 Completed。
            driver.refreshing = false
            driver.until("level 落下后必须由库完成退出") {
                driver.state().refreshState == RefreshState.Idle && abs(driver.state().dragOffset) < .5f
            }
            assertEquals(1, driver.releaseCallbacks)
        }
    } }

    @Test
    fun refusingOnlyOnRefreshDoesNotDisableIndicatorOrThresholdHaptic() = onMotionTestThread { runTest {
        PullCapabilityDriver(testScheduler).use { driver ->
            driver.admitRequest = false // 容器仍挂着，故意验证“只挡回调”并非完整禁用。
            driver.frames(12)
            driver.beginDrag(180f)
            assertEquals(RefreshState.ThresholdReached, driver.state().refreshState)
            assertEquals(1f, driver.state().pullProgress)
            assertTrue(driver.firstItemTop() > driver.restingItemTop() + 8f, "库指示已真实推开内容")
            assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), driver.haptics)
            driver.finishDrag()
            driver.until("拒绝业务准入也不阻止库发出释放回调") { driver.releaseCallbacks == 1 }
            assertEquals(0, driver.acceptedRequests)
            assertFalse(driver.refreshing)
        }
    } }

    @Test
    fun removingPullContainerClosesGestureGateWithoutReplacingTheRealList() = onMotionTestThread { runTest {
        PullCapabilityDriver(testScheduler).use { driver ->
            driver.frames(12)
            val body = requireNotNull(driver.bodyIdentity)
            val oldPullState = driver.state()
            driver.beginDrag(180f)
            assertEquals(RefreshState.ThresholdReached, oldPullState.refreshState)
            assertEquals(0, driver.acceptedRequests)

            driver.pullAllowed = false // 模拟保存/route 准入关闭；不设置假的 isRefreshing=true。
            driver.frames(2)
            driver.finishDrag()
            driver.frames(120)
            assertNull(driver.observedState, "关门分支不组合 PullToRefresh/其指示和指针节点")
            assertEquals(0, driver.acceptedRequests, "关门发生在阈值后、释放前也不能提交")
            assertSame(body, driver.bodyIdentity, "movableContentOf 保留同一个列表内容拥有者")
            assertEquals(1, driver.bodyMounts)
            assertEquals(0, driver.bodyDisposals)

            val hapticsBefore = driver.haptics.size
            driver.beginDrag(-180f)
            driver.finishDrag()
            driver.frames(40)
            assertTrue(driver.contentState.firstVisibleItemIndex > 0 || driver.contentState.firstVisibleItemScrollOffset > 0,
                "关闭刷新不得关闭正常列表滚动")
            assertEquals(0, driver.acceptedRequests)
            val position = driver.listPosition()
            driver.pullAllowed = true
            driver.frames(12)
            assertNotSame(oldPullState, driver.state(), "重开使用新库视觉状态，不能复活旧阈值手势")
            assertEquals(RefreshState.Idle, driver.state().refreshState)
            assertEquals(0f, driver.state().dragOffset)
            assertEquals(position, driver.listPosition(), "开关包装不得重置滚动位置")
            assertSame(body, driver.bodyIdentity)
            assertEquals(1, driver.bodyMounts)
            assertEquals(0, driver.bodyDisposals)
            assertEquals(hapticsBefore, driver.haptics.size)
        }
    } }

    @Test
    fun shortPageKeepsOneOverscrollWhilePullRefreshOwnsOnlyTheTopPull() = onMotionTestThread { runTest {
        PullCapabilityDriver(testScheduler, shortPage = true).use { driver ->
            driver.frames(12)
            assertFalse(driver.contentState.canScrollBackward)
            assertFalse(driver.contentState.canScrollForward)
            assertEquals(1, driver.overscrollOwners())
            val top = driver.firstItemTop()

            driver.beginDrag(-96f)
            driver.frames(2)
            assertTrue(driver.firstItemTop() < top - 8f, "短页底部仍有原单层回弹")
            assertEquals(RefreshState.Idle, driver.state().refreshState, "向上越界不进入刷新")
            assertEquals(1, driver.overscrollOwners())
            driver.finishDrag()
            driver.frames(120)
            assertEquals(top, driver.firstItemTop(), 1f)

            driver.beginDrag(36f)
            assertEquals(RefreshState.Pulling, driver.state().refreshState)
            assertTrue(driver.state().dragOffset > 0f)
            assertEquals(1, driver.overscrollOwners(), "PTR 自有指示不能再给主列表挂回弹")
            driver.finishDrag()
            driver.until("短页未达阈值须复位") {
                abs(driver.state().dragOffset) < .5f && driver.state().pullProgress == 0f &&
                    abs(driver.firstItemTop() - top) < 1f
            }
            assertEquals(0, driver.acceptedRequests)
            driver.beginDrag(180f)
            driver.finishDrag()
            driver.until("短页再次达阈值手势不能被旧枚举锁死") { driver.acceptedRequests == 1 }
            assertEquals(1, driver.releaseCallbacks)
            assertEquals(1, driver.overscrollOwners())
        }
    } }

    private class PullCapabilityDriver(
        private val scheduler: TestCoroutineScheduler,
        shortPage: Boolean = false,
    ) : AutoCloseable {
        var pullAllowed by mutableStateOf(true)
        var refreshing by mutableStateOf(false)
        var admitRequest = true
        var releaseCallbacks = 0
        var acceptedRequests = 0
        var observedState: PullToRefreshState? = null
        var bodyIdentity: Any? = null
        var bodyMounts = 0
        var bodyDisposals = 0
        val contentState = LazyListState()
        val haptics = mutableListOf<HapticFeedbackType>()
        private var lastPoint = Offset.Zero
        private var initialItemTop: Float? = null
        private val window = object : WindowInfo {
            override val isWindowFocused = true
            override val containerSize = IntSize(390, 800)
            override val containerDpSize = DpSize(390.dp, 800.dp)
        }
        private val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
        }
        val environment = MotionTestEnvironment(scheduler, width = 390, height = 800) {
            CompositionLocalProvider(LocalWindowInfo provides window, LocalHapticFeedback provides feedback) {
                SoftVoWiFiTheme(false) {
                    CompositionLocalProvider(LocalOverscrollFactory provides null) {
                        // 列表与业务 level 外置；只移动一个真实内容，不复制整列或重建业务状态机。
                        val body = remember {
                            movableContentOf {
                                val identity = remember { Any() }
                                SideEffect { bodyIdentity = identity }
                                DisposableEffect(Unit) {
                                    bodyMounts++
                                    onDispose { bodyDisposals++ }
                                }
                                LazyColumn(state = contentState, overscrollEffect = null,
                                    // 短页留内区锚点，避免顶部裁剪把上移后的语义 bounds 夹成0。
                                    contentPadding = PaddingValues(top = if (shortPage) 200.dp else 0.dp),
                                    modifier = Modifier.fillMaxSize().managerVerticalScrollFeedback()) {
                                    items(if (shortPage) 1 else 40) { index ->
                                        Text("S3 库探针条目 $index", Modifier.fillMaxWidth().height(64.dp))
                                    }
                                }
                            }
                        }
                        if (pullAllowed) {
                            // 状态在准入分支内：关闭时释放库视觉状态，重开不沿用旧 currentTouch。
                            val state = rememberPullToRefreshState()
                            SideEffect { observedState = state }
                            DisposableEffect(state) {
                                onDispose { if (observedState === state) observedState = null }
                            }
                            PullToRefresh(
                                isRefreshing = refreshing,
                                onRefresh = {
                                    releaseCallbacks++
                                    // 最后准入读取实时属性：即使库取消过程触发旧回调，也不受理新请求。
                                    if (pullAllowed && admitRequest) {
                                        acceptedRequests++
                                        refreshing = true
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                                pullToRefreshState = state,
                                refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新", "刷新完成"),
                            ) { body() }
                        } else {
                            Column(Modifier.fillMaxSize()) { body() }
                        }
                    }
                }
            }
        }
        fun state() = requireNotNull(observedState)
        fun frames(count: Int) { repeat(count) { environment.advanceFrameBy() } }
        fun until(message: String, predicate: () -> Boolean) {
            repeat(150) {
                if (predicate()) return
                frames(1)
            }
            assertTrue(predicate(), "$message（最多150个呈现帧，不按延迟宣称请求完成）")
        }
        private fun nodes(): List<SemanticsNode> {
            fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
            return environment.scene.semanticsOwners.flatMap { tree(it.rootSemanticsNode) }
        }
        private fun list() = nodes().single { it.config.contains(SemanticsActions.ScrollToIndex) }
        fun listPosition() = contentState.firstVisibleItemIndex to contentState.firstVisibleItemScrollOffset
        fun firstItemTop() = nodes().single {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "S3 库探针条目 0" } == true
        }.boundsInRoot.top
        fun restingItemTop(): Float = requireNotNull(initialItemTop)
        fun beginDrag(distance: Float) {
            if (initialItemTop == null) initialItemTop = firstItemTop()
            val bounds = list().boundsInRoot
            val start = Offset(bounds.center.x, bounds.top + 220f)
            assertTrue(start.y + distance > bounds.top + 8f && start.y + distance < bounds.bottom - 8f)
            environment.press(start); frames(1)
            repeat(4) { index ->
                lastPoint = start + Offset(0f, distance * (index + 1) / 4f)
                environment.move(lastPoint); frames(1)
            }
        }
        fun finishDrag() { frames(12); environment.release(lastPoint) }
        fun overscrollOwners(): Int = generateSequence(list().layoutInfo) { it.parentInfo }
            .flatMap { it.getModifierInfo().asSequence() }
            .count { it.modifier.javaClass.name == "top.yukonga.miuix.kmp.utils.OverscrollElement" }
        override fun close() { environment.close() }
    }
}
