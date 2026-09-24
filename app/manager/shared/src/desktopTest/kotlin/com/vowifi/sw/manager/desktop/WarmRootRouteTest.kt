package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.UnavailableMessageSource
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import javax.swing.SwingUtilities
import java.lang.reflect.InvocationTargetException

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WarmRootRouteTest {
    @Test fun warmCallsAfterSettledHomeShowsCallsContent() = onEdt {
        Driver().use { driver ->
            driver.assertVisible("SoftVoWiFi")
            assertFalse(driver.isVisible("拨号盘"))
            driver.route(AppRoute.Calls)
            assertEquals(listOf<AppRoute>(AppRoute.Calls), driver.consumed)
            driver.assertVisible("拨号盘")
            driver.assertSelected(1)
        }
    }

    @Test fun queuedWarmCallsAndRepeatedRequestAfterManualHome() = onEdt {
        Driver().use { driver ->
            driver.assertVisible("SoftVoWiFi")
            assertFalse(driver.isVisible("拨号盘"))
            driver.route(AppRoute.Calls)
            driver.assertVisible("拨号盘")
            driver.assertSelected(1)
            driver.tab(0)
            driver.assertVisible("SoftVoWiFi")
            driver.route(AppRoute.Calls)
            driver.assertVisible("拨号盘")
            driver.assertSelected(1)
            assertEquals(listOf<AppRoute>(AppRoute.Calls, AppRoute.Calls), driver.consumed)
        }
    }

    @Test fun latestWarmRouteWinsAndSubpageRequestDoesNotReplayOnResume() = onEdt {
        Driver().use { driver ->
            driver.enqueue(AppRoute.Calls)
            driver.enqueue(AppRoute.Messages)
            driver.frame()
            driver.assertVisible("新建短信")
            driver.assertSelected(2)
            driver.route(AppRoute.Dialpad)
            driver.assertVisible("1")
            driver.route(AppRoute.Calls)
            driver.assertVisible("拨号盘")
            driver.back()
            driver.assertVisible("SoftVoWiFi")
            driver.route(AppRoute.Calls)
            driver.tab(0)
            val count = driver.consumed.size
            driver.resume()
            driver.assertVisible("SoftVoWiFi")
            driver.assertSelected(0)
            assertEquals(count, driver.consumed.size)
        }
    }

    @Test fun tabsAndUserSwipeStillSynchronizeAfterWarmSnapAndBack() = onEdt {
        Driver().use { driver ->
            driver.route(AppRoute.Calls)
            driver.swipeLeft()
            driver.assertVisible("新建短信")
            driver.assertSelected(2)
            driver.back()
            driver.assertVisible("SoftVoWiFi")
            driver.assertSelected(0)
            driver.tab(3)
            driver.assertSelected(3)
            driver.tab(1)
            driver.assertVisible("拨号盘")
            driver.back()
            driver.assertVisible("SoftVoWiFi")
        }
    }

    private fun onEdt(block: () -> Unit) {
        try { SwingUtilities.invokeAndWait(block) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    private class Driver : AutoCloseable {
        init { assertOwner() }
        private fun assertOwner() = assertTrue(SwingUtilities.isEventDispatchThread(), "WarmRoot scene access must run on EDT")
        private val scheduler = TestCoroutineScheduler()
        private val backInput = object : NavigationEventInput() {
            fun back() = dispatchOnBackCompleted()
        }
        private val backOwner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher().apply { addInput(backInput) }
        }
        private val routes = Channel<AppRoute>(Channel.CONFLATED)
        val consumed = mutableListOf<AppRoute>()
        private val incoming = routes.receiveAsFlow().onEach { consumed.add(it) }
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val sources = SoftVoWiFiSources(
            FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
            FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"),
            FixtureEnvironmentDiagnosticsSource("empty"), FixtureOperationalLogSource("empty"),
            UnavailableMessageSource(), FixtureCallSource("unavailable"),
        )
        private var time = 0L
        private val scene = ImageComposeScene(width = 420, height = 900,
            coroutineContext = StandardTestDispatcher(scheduler)) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle,
                LocalNavigationEventDispatcherOwner provides backOwner) {
                SoftVoWiFiApp(sources, {}, false, "test", incomingRoutes = incoming, forceDark = false)
            }
        }
        init { frame() }
        fun frame() { assertOwner(); repeat(40) {
            scheduler.runCurrent()
            time += 16_666_667L
            scene.render(time).close()
            scheduler.runCurrent()
        } }
        fun enqueue(route: AppRoute) { assertTrue(routes.trySend(route).isSuccess) }
        fun route(route: AppRoute) { enqueue(route); frame() }
        fun back() { backInput.back(); frame() }
        fun swipeLeft() {
            scene.sendPointerEvent(PointerEventType.Press, Offset(360f, 450f), type = PointerType.Touch)
            repeat(10) { step ->
                scheduler.runCurrent()
                time += 16_666_667L
                scene.sendPointerEvent(PointerEventType.Move, Offset(360f - (step + 1) * 30f, 450f), type = PointerType.Touch)
                scene.render(time).close()
            }
            scene.sendPointerEvent(PointerEventType.Release, Offset(60f, 450f), type = PointerType.Touch)
            frame()
        }
        fun resume() {
            lifecycle.lifecycle.currentState = Lifecycle.State.CREATED
            frame()
            lifecycle.lifecycle.currentState = Lifecycle.State.RESUMED
            frame()
        }
        private fun tabs() = visibleNodes().filter { it.config.getOrNull(SemanticsProperties.Selected) != null }
        fun assertSelected(index: Int) {
            assertEquals(4, tabs().size)
            assertEquals(listOf(index), tabs().mapIndexedNotNull { position, node ->
                position.takeIf { node.config[SemanticsProperties.Selected] }
            })
        }
        fun tab(index: Int) {
            assertTrue(requireNotNull(tabs()[index].config[SemanticsActions.OnClick].action).invoke())
            frame()
        }
        private fun visibleNodes() = nodes().filter { node ->
            val bounds = node.boundsInRoot
            bounds.width > 0 && bounds.height > 0 && bounds.center.x in 0f..420f && bounds.center.y in 0f..900f
        }
        fun isVisible(text: String) = visibleNodes().any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { item -> item.text == text } == true ||
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
        }
        fun assertVisible(text: String) = assertTrue(isVisible(text),
            "Expected visible $text, actual=" + visibleNodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text })
        private fun nodes(): List<SemanticsNode> { assertOwner(); return scene.semanticsOwners.flatMap { owner ->
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            descend(owner.rootSemanticsNode)
        } }
        override fun close() { assertOwner(); scene.close(); scheduler.runCurrent(); routes.close(); backOwner.navigationEventDispatcher.dispose() }
    }
}
