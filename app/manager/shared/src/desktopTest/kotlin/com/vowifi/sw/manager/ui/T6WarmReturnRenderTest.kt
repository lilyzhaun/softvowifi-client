@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
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
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.CallSource
import com.vowifi.sw.manager.data.FixtureMessageSource
import com.vowifi.sw.manager.desktop.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.*
import com.vowifi.sw.manager.ui.testing.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

class T6WarmReturnRenderTest {
    @Test fun endedWarmRouteReceivedWhileMainIsPausedStaysOnCallsAfterResume() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) for (resize in listOf(false, true)) {
            Driver(testScheduler, AppDestination.HOME, scale).use { d ->
                d.frames(60); d.assertRoot(0)
                assertTrue(s4Nodes(d.e).any { it.config.getOrNull(SemanticsProperties.TestTag) == "ongoing-call-action" })
                d.lifecycle.currentState = Lifecycle.State.CREATED
                testScheduler.runCurrent()
                d.live.value = d.ready.copy(phase = CallPhase.Idle, audio = CallAudioState.Idle, clock = null, binding = null)
                d.incomingCalls()
                // No render while Main is covered; the incoming route is received before its next frame.
                testScheduler.runCurrent()
                assertEquals(listOf<AppRoute>(AppRoute.Calls), d.consumed)
                if (resize) d.resize()
                d.lifecycle.currentState = Lifecycle.State.RESUMED
                d.frames(60); d.assertRoot(1)
                for (elapsed in listOf(1200L, 4000L, 35_000L)) {
                    d.e.advanceFrameBy(elapsed); d.frames(2); d.assertRoot(1)
                }
                assertTrue(s4Nodes(d.e).any {
                    it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("搜索通话记录")
                })
                assertFalse(s4Nodes(d.e).any { it.config.getOrNull(SemanticsProperties.TestTag) == "ongoing-call-action" })
                assertEquals(listOf<AppRoute>(AppRoute.Calls), d.consumed)
            }
        }
    } }

    @Test fun forcedCallsReturnCancelsAnOlderPointerScrollEvenWhenCurrentPageIsAlreadyCalls() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) Driver(testScheduler, AppDestination.CALLS, scale).use { d ->
            d.frames(60); d.assertRoot(1)
            d.e.press(Offset(90f, 420f)); d.e.advanceFrameBy(16)
            d.e.move(Offset(210f, 420f)); d.e.advanceFrameBy(16)
            val dragged = d.position()
            assertTrue(dragged in .5f.. .99f, "前提：真实Pager已向Home拖动但currentPage仍为Calls，position=$dragged")
            d.incomingCalls(); d.e.advanceFrameBy(0); d.frames(2)
            d.assertRoot(1)
            d.e.move(Offset(360f, 420f)); d.e.advanceFrameBy(16)
            d.e.release(Offset(360f, 420f)); d.frames(60)
            d.assertRoot(1)
            assertEquals(listOf<AppRoute>(AppRoute.Calls), d.consumed)
            d.e.press(Offset(90f, 420f)); d.e.advanceFrameBy(16)
            d.e.move(Offset(210f, 420f)); d.e.advanceFrameBy(16)
            d.e.move(Offset(360f, 420f)); d.e.advanceFrameBy(16)
            d.e.release(Offset(360f, 420f)); d.frames(60)
            d.assertRoot(0)
            assertEquals(listOf<AppRoute>(AppRoute.Calls), d.consumed, "一次暖跳不锁住后继新手势，也不能重放路由")
        }
    } }

    private class Driver(val scheduler: TestCoroutineScheduler, initial: AppDestination, scale: Float) : AutoCloseable {
        val ready = FixtureCallSource("incall").state.value as CallUiState.Ready
        val live = MutableStateFlow<CallUiState>(ready)
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val lifecycle get() = owner.lifecycle as LifecycleRegistry
        private val back = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        private var size by mutableStateOf(IntSize(420, 900))
        private val window = object : WindowInfo {
            override val isWindowFocused = true
            override val containerSize get() = size
            override val containerDpSize get() = DpSize(size.width.dp, size.height.dp)
        }
        private val routes = Channel<AppRoute>(Channel.CONFLATED)
        val consumed = mutableListOf<AppRoute>()
        private val incoming = routes.receiveAsFlow().onEach { consumed += it }
        private val calls = object : CallSource by FixtureCallSource("incall") {
            override val state = live
            override fun refresh() = Unit
        }
        private val sources = SoftVoWiFiSources(FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
            FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"), FixtureEnvironmentDiagnosticsSource("empty"),
            FixtureOperationalLogSource("empty"), FixtureMessageSource("empty"), calls)
        private val appearance = InMemoryAppearanceSettingsSource(AppearanceSettings.Default.copy(blurRequested = false))
        val e = MotionTestEnvironment(scheduler, 420, 900, scale, dispatcher = StandardTestDispatcher(scheduler)) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalWindowInfo provides window,
                LocalNavigationEventDispatcherOwner provides back) {
                SoftVoWiFiApp(sources, {}, false, "test", initialDestination = initial, incomingRoutes = incoming,
                    forceDark = false, appearanceSource = appearance, onOpenCall = {})
            }
        }
        fun frames(count: Int) { repeat(count) { e.advanceFrameBy(16) } }
        fun incomingCalls() { assertTrue(routes.trySend(AppRoute.Calls).isSuccess) }
        fun resize() { size = IntSize(420, 940); e.scene.constraints = Constraints.fixed(size.width, size.height) }
        fun position(): Float = s4Nodes(e, false).first { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
            .config[SemanticsProperties.HorizontalScrollAxisRange].value() / 420f
        fun assertRoot(page: Int) {
            val selected = s4Nodes(e, false).filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
            assertEquals(1, selected.size, "真实底栏必须唯一选中")
            val roots = listOf("首页", "通话", "短信", "设置")
            assertTrue(s4Matches(selected.single(), roots[page]),
                "期望${roots[page]}，实际${roots.firstOrNull { s4Matches(selected.single(), it) }}，viewport=${position()}")
            assertEquals(page.toFloat(), position(), .001f, "内容viewport与所选tab必须一致且停稳")
        }
        override fun close() { e.close(); routes.close(); back.navigationEventDispatcher.dispose() }
    }
}
