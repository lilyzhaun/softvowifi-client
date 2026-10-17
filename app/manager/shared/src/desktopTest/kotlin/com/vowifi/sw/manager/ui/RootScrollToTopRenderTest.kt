package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.ui.navigation.AppNavigationState
import com.vowifi.sw.manager.ui.navigation.AppNavigator
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import top.yukonga.miuix.kmp.basic.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Actual AppShell selection, root composition and LazyColumn scrolling; no shadow scroll model. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RootScrollToTopRenderTest {
    @Test fun reselectionExpandsTheRealManagementTitleWhenItsListIsStillAtZero() = onMotionTestThread { runTest {
        Driver(testScheduler, initialIndex = 0, managementScaffold = true).use { d ->
            d.frames(30)
            val expandedTitleBottom = d.managementTitleBottom()
            val expandedFirstItemTop = d.managementFirstItemTop()
            assertEquals(0f, d.managementScrollValue(), "前提：真实管理列表最初位于零位置")

            // The dispatcher is attached to an actual item inside AppPageScaffold's LazyColumn.
            // Its real parent chain owns the miuix TopAppBarState; no state or title flag is injected.
            val consumed = d.headerScroll.dispatchPreScroll(Offset(0f, -40f), NestedScrollSource.UserInput)
            d.headerScroll.dispatchPostScroll(Offset.Zero, Offset(0f, -40f) - consumed, NestedScrollSource.UserInput)
            d.frames(10)
            assertTrue(consumed.y < 0f, "前提：真实嵌套滚动父节点必须消费向上位移")
            assertEquals(0f, d.managementScrollValue(), "前提：标题缩起时真实列表index/offset仍为零")
            assertTrue(d.managementTitleBottom() < expandedTitleBottom - 1f, "前提：实际标题几何必须已经收缩")
            assertTrue(d.managementFirstItemTop() < expandedFirstItemTop - 1f, "前提：标题收缩须改变真实内容起始位置")

            d.tap("首页"); d.frames(120)
            assertEquals(expandedTitleBottom, d.managementTitleBottom(), 1f, "R8：列表为零也应展开尚未展开的大标题")
            assertEquals(expandedFirstItemTop, d.managementFirstItemTop(), 1f, "展开后恢复真实标题下方布局")
            assertEquals(0f, d.managementScrollValue())
            assertEquals(0, d.navigationChanges)
        }
    } }

    @Test fun distantReselectionApproachesBeforeAnimatingWithoutRenavigating() = onMotionTestThread { runTest {
        Driver(testScheduler, initialIndex = 120).use { d ->
            d.frames(20)
            assertEquals(120, d.home.firstVisibleItemIndex)
            d.tap("首页")
            d.frames(2)
            assertTrue(d.home.firstVisibleItemIndex in 0..8, "超过三屏应先靠近顶部，而非穿过百项")
            assertTrue(d.home.isScrollInProgress, "靠近后仍以真实列表动画回顶")
            d.frames(120)
            assertEquals(0, d.home.firstVisibleItemIndex)
            assertEquals(0, d.home.firstVisibleItemScrollOffset)
            assertEquals(0, d.navigationChanges, "重复点击不应再次提交导航")
        }
    } }

    @Test fun touchingTheListInterruptsBothScrollAndTitleExpansion() = onMotionTestThread { runTest {
        Driver(testScheduler, initialIndex = 120, holdExpansion = true).use { d ->
            d.frames(20)
            d.tap("首页"); d.frames(2)
            assertTrue(d.home.isScrollInProgress)
            assertTrue(d.expanding)
            val point = Offset(180f, 220f)
            d.environment.press(point); d.frames(2)
            assertFalse(d.expanding, "触摸起手即取消展开，不等待拖过slop")
            assertFalse(d.home.isScrollInProgress)
            val position = d.home.firstVisibleItemIndex to d.home.firstVisibleItemScrollOffset
            assertTrue(position != (0 to 0), "必须在动画中途拦下")
            d.frames(80)
            assertEquals(position, d.home.firstVisibleItemIndex to d.home.firstVisibleItemScrollOffset)
            d.environment.release(point); d.frames(10)
        }
    } }

    @Test fun reselectionKeepsTheOtherRootPositionAndAnAlreadyTopListDoesNothing() = onMotionTestThread { runTest {
        Driver(testScheduler, initialIndex = 18).use { d ->
            d.frames(20)
            d.tap("通话"); d.frames(120)
            assertEquals(AppRoute.Calls, d.navigation.currentRoot)
            assertEquals(24, d.calls.firstVisibleItemIndex)
            d.tap("通话"); d.frames(120)
            assertEquals(0, d.calls.firstVisibleItemIndex)
            assertEquals(18, d.home.firstVisibleItemIndex)
            val expansions = d.expansionStarts
            val changes = d.navigationChanges
            d.tap("通话"); d.frames(40)
            assertEquals(expansions, d.expansionStarts)
            assertEquals(changes, d.navigationChanges)
            d.tap("首页"); d.frames(120)
            assertEquals(18, d.home.firstVisibleItemIndex)
        }
    } }

    @Test fun aCoveredRootRejectsEvenAPreviouslyCapturedBottomAction() = onMotionTestThread { runTest {
        Driver(testScheduler, initialIndex = 18).use { d ->
            d.frames(20)
            val staleAction = d.nodes().first {
                it.config.getOrNull(SemanticsProperties.Selected) == true && it.config.contains(SemanticsActions.OnClick)
            }.config[SemanticsActions.OnClick].action!!
            d.navigation = AppNavigator.open(d.navigation, AppRoute.SettingsAbout)
            d.frames(120)
            assertTrue(d.navigation.topRoute is AppRoute.Subpage)
            staleAction()
            d.frames(120)
            assertTrue(d.navigation.topRoute is AppRoute.Subpage)
            assertEquals(18, d.home.firstVisibleItemIndex)
            assertEquals(32, d.subpage.firstVisibleItemIndex)
            assertEquals(0, d.expansionStarts)
        }
    } }

    @Test fun floatingBubbleDragBackToCurrentItemDoesNotReselect() = onMotionTestThread { runTest {
        Driver(testScheduler, initialIndex = 18, floating = true).use { d ->
            d.frames(30)
            val point = d.labelPoint("首页")
            d.environment.press(point); d.frames(2)
            d.environment.move(point + Offset(36f, 0f)); d.frames(8)
            d.environment.move(point); d.frames(8)
            d.environment.release(point); d.frames(120)
            assertEquals(18, d.home.firstVisibleItemIndex)
            assertEquals(0, d.expansionStarts)
            assertEquals(0, d.navigationChanges)
            d.tap("首页"); d.frames(120)
            assertEquals(0, d.home.firstVisibleItemIndex, "同一入口随后真正点击应可回顶")
        }
    } }

    // Use the same pinned scene overload as ManagerMultiPointerPressTest, never gesture callbacks.
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun floatingBubbleSecondTouchCancelsReselectionWithoutPointerMovement() = onMotionTestThread { runTest {
        fun pointer(point: Offset, id: Long, pressed: Boolean) = ComposeScenePointer(
            id = PointerId(id), position = point + Offset(if (id == 2L) 6f else 0f, 0f),
            pressed = pressed, type = PointerType.Touch,
        )
        fun event(d: Driver, type: PointerEventType, vararg pointers: ComposeScenePointer) {
            d.environment.scene.sendPointerEvent(type, pointers.toList(), timeMillis = testScheduler.currentTime)
            testScheduler.runCurrent()
            d.frames(1)
        }

        // Establish that this overload really reaches the selected floating bubble and returns its list to top.
        Driver(testScheduler, initialIndex = 18, floating = true).use { d ->
            d.frames(30)
            assertEquals(AppRoute.Home, d.navigation.topRoute)
            assertEquals(18, d.home.firstVisibleItemIndex, "单指对照前提：真实列表已经离顶")
            val point = d.labelPoint("首页")
            event(d, PointerEventType.Press, pointer(point, 1, true))
            event(d, PointerEventType.Release, pointer(point, 1, false))
            d.frames(120)
            assertEquals(0, d.home.firstVisibleItemIndex, "同一多指注入API的单指点击必须真实回顶")
            assertEquals(0, d.home.firstVisibleItemScrollOffset)
            assertEquals(1, d.expansionStarts)
            assertEquals(0, d.navigationChanges)
        }

        var observedPointers = 0
        Driver(testScheduler, initialIndex = 18, floating = true,
            onPointerCount = { observedPointers = maxOf(observedPointers, it) }).use { d ->
            d.frames(30)
            assertEquals(AppRoute.Home, d.navigation.topRoute)
            assertEquals(18, d.home.firstVisibleItemIndex, "多指取消前提：真实列表已经离顶")
            assertFalse(d.home.isScrollInProgress)
            val position = d.home.firstVisibleItemIndex to d.home.firstVisibleItemScrollOffset
            val point = d.labelPoint("首页")
            event(d, PointerEventType.Press, pointer(point, 1, true))
            event(d, PointerEventType.Press, pointer(point, 1, true), pointer(point, 2, true))
            event(d, PointerEventType.Release, pointer(point, 1, false), pointer(point, 2, true))
            event(d, PointerEventType.Release, pointer(point, 2, false))
            d.frames(120)
            assertEquals(2, observedPointers, "前提：真实指针链必须观察到两根同时按下的手指")
            assertEquals(position, d.home.firstVisibleItemIndex to d.home.firstVisibleItemScrollOffset,
                "§4.6/R8：无位移的多指介入必须取消，剩余指针抬起不得触发回顶")
            assertEquals(0, d.expansionStarts, "多指取消不得展开标题")
            assertEquals(0, d.navigationChanges)
            assertEquals(AppRoute.Home, d.navigation.topRoute)
        }
    } }

    private class Driver(scheduler: TestCoroutineScheduler, initialIndex: Int, floating: Boolean = false,
        private val holdExpansion: Boolean = false, private val managementScaffold: Boolean = false,
        private val onPointerCount: ((Int) -> Unit)? = null) : AutoCloseable {
        var navigation by mutableStateOf(AppNavigationState.initial())
        var navigationChanges = 0
        var expansionStarts = 0
        var expanding = false
        val home = LazyListState(initialIndex)
        val calls = LazyListState(24)
        val subpage = LazyListState(32)
        private val messages = LazyListState()
        private val settings = LazyListState()
        val headerScroll = NestedScrollDispatcher()
        private val headerInput = object : NestedScrollConnection {}
        private val back = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val window = object : WindowInfo {
            override val isWindowFocused = true
            override val containerSize = IntSize(390, 520)
            override val containerDpSize = DpSize(390.dp, 520.dp)
        }
        val environment = MotionTestEnvironment(scheduler, width = 390, height = 520) {
            WithPointerObservation {
                CompositionLocalProvider(LocalWindowInfo provides window, LocalLifecycleOwner provides lifecycle,
                    LocalNavigationEventDispatcherOwner provides back) {
                    SoftVoWiFiTheme(false) {
                        AppShell(navigation, { navigationChanges++; navigation = it },
                            appearanceSettings = AppearanceSettings.Default.copy(floatingNavigation = floating), blurSupported = false) { route ->
                            if (managementScaffold && route == AppRoute.Home) {
                                AppPageScaffold("标题回顶探针") {
                                    item {
                                        Text("标题下首项", Modifier.fillMaxWidth().height(60.dp).nestedScroll(headerInput, headerScroll))
                                    }
                                    items(80) { Text("管理条目 $it", Modifier.fillMaxWidth().height(60.dp)) }
                                }
                            } else {
                                val list = when (route) {
                                    AppRoute.Home -> home
                                    AppRoute.Calls -> calls
                                    AppRoute.Messages -> messages
                                    AppRoute.Settings -> settings
                                    is AppRoute.Subpage -> subpage
                                }
                                // Subpage deliberately attempts registration: the root context must not leak into it.
                                RegisterRootScrollToTop(list) {
                                    expansionStarts++
                                    expanding = true
                                    try { if (holdExpansion) awaitCancellation() } finally { expanding = false }
                                }
                                LazyColumn(Modifier.fillMaxSize(), state = list, overscrollEffect = null) {
                                    items(200) { Text("${route} 条目 $it", Modifier.fillMaxWidth().height(60.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        @Composable
        private fun WithPointerObservation(content: @Composable () -> Unit) {
            val observe = onPointerCount
            if (observe == null) {
                content()
            } else {
                Box(Modifier.fillMaxSize().pointerInput(observe) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            observe(event.changes.count { it.pressed })
                        }
                    }
                }) { content() }
            }
        }
        fun frames(count: Int) { repeat(count) { environment.advanceFrameBy() } }
        fun nodes(): List<SemanticsNode> {
            fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
            return environment.scene.semanticsOwners.flatMap { visit(it.rootSemanticsNode) }
        }
        fun labelPoint(label: String): Offset = nodes().first {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
        }.boundsInRoot.center
        fun managementTitleBottom(): Float = nodes().filter {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "标题回顶探针" } == true
        }.maxOf { it.boundsInRoot.bottom }
        fun managementFirstItemTop(): Float = nodes().first {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "标题下首项" } == true
        }.boundsInRoot.top
        fun managementScrollValue(): Float = nodes().first {
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange) && it.config.contains(SemanticsActions.ScrollBy)
        }.config[SemanticsProperties.VerticalScrollAxisRange].value()
        fun tap(label: String) {
            val point = labelPoint(label)
            environment.press(point); frames(2); environment.release(point)
        }
        override fun close() { environment.close(); back.navigationEventDispatcher.dispose() }
    }
}
