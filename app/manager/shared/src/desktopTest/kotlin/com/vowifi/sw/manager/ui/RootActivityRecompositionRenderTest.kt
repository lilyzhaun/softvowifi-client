@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
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
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Slot commits are observations, not a wall-clock performance or backend claim. */
class RootActivityRecompositionRenderTest {
    @Test fun routePrivateResultClearsItsControllerAndRejectsAStaleCopy() = onMotionTestThread { runTest {
        val active = mutableStateOf(true)
        var result: ManagerResultFeedback? = null
        var copy: ((CommunicationCopyFeedback) -> Unit)? = null
        MotionTestEnvironment(testScheduler, 390, 844, durationScale = 0f) {
            CompositionLocalProvider(LocalAppRouteActive provides active.value) {
                SoftVoWiFiTheme(false) {
                    CommunicationFeedbackHost {
                        val controller = LocalManagerResultFeedback.current
                        val show = LocalCommunicationCopyFeedback.current
                        SideEffect { result = controller; copy = show }
                    }
                }
            }
        }.use { e ->
            fun frames() { repeat(2) { e.advanceFrameBy(16) } }
            fun labels(): Int {
                fun count(n: SemanticsNode): Int =
                    n.config.getOrNull(SemanticsProperties.Text).orEmpty().count { it.text == "已复制" } + n.children.sumOf(::count)
                return e.scene.semanticsOwners.sumOf { count(it.unmergedRootSemanticsNode) }
            }
            frames()
            val controller = requireNotNull(result)
            val stale = requireNotNull(copy)
            stale(CommunicationCopyFeedback.MESSAGE); frames()
            assertTrue(controller.visible, "前提：真实唯一host确已发布结果")
            assertEquals(1, labels())
            val generation = controller.generation
            active.value = false; frames()
            assertFalse(controller.visible, "失活必须真实清controller，不能只遮住文字")
            assertEquals(0, labels(), "零动画失活两帧后独立结果层不再保留文字")
            stale(CommunicationCopyFeedback.MESSAGE); frames()
            assertFalse(controller.visible)
            assertEquals(generation, controller.generation, "失活旧复制回调不得产生新结果")
            active.value = true; frames()
            assertFalse(controller.visible, "重新激活不能复活离开前的提示")
            assertEquals(0, labels())
        }
    } }

    @Test fun callsToMessagesDoesNotRecomposeUnobservingRootSlots() = verify { d ->
        d.ready()
        val slots = d.slotCommits.toList()
        val actions = d.actionCommits.toList()
        val stale = d.action(1).config[SemanticsActions.OnClick].action!!
        d.drag(left = true)
        d.frames(60)
        d.assertPage(2)
        d.assertActive()
        assertTrue(d.actionCommits[1] > actions[1] && d.actionCommits[2] > actions[2],
            "前提：Calls失活和Messages激活必须真实提交到消费者")
        d.rejectStale(stale)
        assertEquals(slots, d.slotCommits.toList(), "未读active的四根slot不应因切换重组")
        assertEquals(actions[0], d.actionCommits[0], "Home的active未变")
        assertEquals(actions[3], d.actionCommits[3], "Settings的active未变")
    }

    @Test fun sixReversalsKeepStaticSlotsAndLiveBusinessWithoutLosingInput() = verify { d ->
        d.ready()
        val slots = d.slotCommits.toList()
        val clicks = d.clicks.toList()
        val business = d.businessCommits.toList()
        val handoffs = mutableListOf<Pair<Int, Int>>()
        listOf(true, true, false, false, true, false).forEachIndexed { index, left ->
            val old = d.selected()
            val stale = d.action(old).config[SemanticsActions.OnClick].action!!
            d.drag(left, updateBusiness = index == 2)
            d.assertActive()
            val target = d.selected()
            if (old != target) {
                handoffs += old to target
                d.rejectStale(stale)
            }
            if (index >= 2) {
                assertTrue(d.businessCommits[target] > business[target], "前提：业务叶子必须消费新输入")
                assertTrue(d.hasText("business-$target=7"), "真实semantics必须显示最新业务标签")
            }
        }
        assertTrue((1 to 2) in handoffs && handoffs.any { it.second < it.first },
            "前提：六次真实指针必须产生Calls→Messages及反向active交接，实际=$handoffs")
        assertEquals(clicks, d.clicks.toList(), "内容drag及失活旧回调不能意外点击")
        d.frames(60)
        d.assertPage(d.page().roundToInt())
        d.assertActive()
        assertEquals(slots, d.slotCommits.toList(), "连续反转/业务叶子更新不应重组静态slot")
    }

    private fun verify(block: (Driver) -> Unit) = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher, timeout = 45.seconds) {
            Driver(testScheduler, dispatcher).use(block)
        }
    }

    private class Driver(scheduler: TestCoroutineScheduler, dispatcher: TestDispatcher) : AutoCloseable {
        private var navigation by mutableStateOf(AppNavigationState.initial(AppRoute.Calls))
        private val navigate: (AppNavigationState) -> Unit = { navigation = it }
        private val appearance = AppearanceSettings.Default.copy(blurRequested = false, liquidGlassRequested = false)
        val slotCommits = IntArray(4)
        val actionCommits = IntArray(4)
        val businessCommits = IntArray(4)
        val clicks = IntArray(4)
        private val active = arrayOfNulls<Boolean>(4)
        private val business = mutableIntStateOf(0)
        private val back = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val window = object : WindowInfo {
            override val isWindowFocused = true
            override val containerSize = IntSize(420, 900)
            override val containerDpSize = DpSize(420.dp, 900.dp)
        }
        private val environment = MotionTestEnvironment(scheduler, 420, 900, dispatcher = dispatcher) {
            CompositionLocalProvider(LocalWindowInfo provides window, LocalLifecycleOwner provides lifecycle,
                LocalNavigationEventDispatcherOwner provides back) {
                SoftVoWiFiTheme(false) {
                    // Stable identity: navigation changes must not manufacture a changed content argument.
                    val content: @Composable (AppRoute) -> Unit = remember {
                        { route ->
                            val index = AppRoute.rootRoutes.indexOf(route as? AppRoute.Root ?: error("Root slot expected"))
                            check(index >= 0)
                            SideEffect { slotCommits[index]++ }
                            Box(Modifier.fillMaxSize()) {
                                Text("static-$index", Modifier.align(Alignment.TopStart))
                                ActiveAction(index)
                                Box(Modifier.align(Alignment.TopEnd)) { BusinessLabel(index) }
                            }
                        }
                    }
                    AppShell(navigation, navigate, appearanceSettings = appearance, blurSupported = false,
                        destinationContent = content)
                }
            }
        }

        @Composable private fun ActiveAction(index: Int) {
            val current = LocalAppRouteActive.current
            SideEffect { active[index] = current; actionCommits[index]++ }
            CommunicationTextAction("action-$index", { clicks[index]++ }, Modifier.fillMaxSize())
        }

        @Composable private fun BusinessLabel(index: Int) {
            val value = business.intValue
            SideEffect { businessCommits[index]++ }
            Text("business-$index=$value")
        }

        fun frames(count: Int = 1) { repeat(count) { environment.advanceFrameBy(16) } }
        private fun nodes(): List<SemanticsNode> {
            fun visit(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::visit)
            return environment.scene.semanticsOwners.flatMap { visit(it.rootSemanticsNode) }
        }
        fun hasText(value: String) = nodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == value }
        }
        fun action(index: Int) = nodes().first {
            it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("action-$index") &&
                it.config.contains(SemanticsActions.OnClick)
        }
        fun page(): Float = nodes().first { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
            .config[SemanticsProperties.HorizontalScrollAxisRange].value() / 420f
        fun selected(): Int {
            val tabs = nodes().filter { it.config.contains(SemanticsProperties.Selected) &&
                it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f &&
                it.boundsInRoot.center.x in 0f..420f && it.boundsInRoot.top > 740f }
            assertEquals(4, tabs.size, "前提：真实四项底栏可观测")
            return tabs.mapIndexedNotNull { index, node ->
                index.takeIf { node.config[SemanticsProperties.Selected] }
            }.single()
        }
        fun assertActive() {
            val target = selected()
            assertEquals((0..3).map { it == target }, active.toList(), "前提：仅真实选中页的消费者active")
            assertEquals(AppRoute.rootRoutes[target], navigation.currentRoot, "前提：导航和真实Selected一致")
        }
        fun assertPage(expected: Int) {
            assertTrue(expected in 0..3)
            assertEquals(expected.toFloat(), page(), .01f, "前提：实际pager停在目标整数页")
            assertEquals(expected, selected())
        }
        fun ready() {
            frames(40)
            assertTrue(slotCommits.all { it > 0 }, "前提：四个根slot都已实际组合")
            assertPage(1); assertActive()
            val point = action(1).boundsInRoot.center
            environment.press(point); frames(); environment.release(point); frames(30)
            assertEquals(listOf(0, 1, 0, 0), clicks.toList(), "前提：真实动作命中且只点击Calls")
            assertPage(1); assertActive()
        }
        fun rejectStale(callback: () -> Boolean) {
            val before = clicks.toList()
            callback()
            assertEquals(before, clicks.toList(), "失活页先前捕获的动作必须撤销")
        }
        fun drag(left: Boolean, updateBusiness: Boolean = false) {
            val from = if (left) 350f else 70f
            val to = if (left) 70f else 350f
            val before = page()
            environment.press(Offset(from, 390f)); frames()
            repeat(10) { step ->
                environment.move(Offset(from + (to - from) * (step + 1) / 10f, 390f)); frames()
            }
            environment.release(Offset(to, 390f))
            if (updateBusiness) business.intValue = 7
            frames() // No inter-gesture settling or navigation-bar reset.
            // Commit pending target/layout effects without advancing animation time.
            repeat(4) { environment.advanceFrameBy(0) }
            val after = page()
            assertTrue(if (left) after > before + .1f else after < before - .1f,
                "前提：父pager须立即接管内容反向drag；before=$before after=$after left=$left")
        }
        override fun close() { environment.close(); back.navigationEventDispatcher.dispose() }
    }
}
