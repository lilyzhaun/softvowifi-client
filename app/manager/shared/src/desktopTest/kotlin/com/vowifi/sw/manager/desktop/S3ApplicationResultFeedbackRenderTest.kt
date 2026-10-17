@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.UnavailableMessageSource
import com.vowifi.sw.manager.model.LogExportCompletion
import com.vowifi.sw.manager.model.LogExportOutcome
import com.vowifi.sw.manager.model.OperationalLogUiState
import com.vowifi.sw.manager.ui.AppDestination
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// L5：真实App消费平台完成边界，不替代MainActivity/SAF或实际writer的close证明。
class S3ApplicationResultFeedbackRenderTest {
    @Test fun realExportWaitsForWrittenThenShowsOnePoliteAppResultAndRestartsItsLifetime() = onMotionTestThread { runTest {
        AppExportFixture(testScheduler).use { f ->
            val buttonBefore = f.exportButton().boundsInRoot
            val logBefore = f.logBounds()
            f.export()
            assertEquals(1, f.requests)
            assertTrue(f.busy.value)
            assertTrue(requireNotNull(f.payload).isNotBlank(), "真实按钮须经过实际脱敏日志格式化回调")
            f.advance(2400)
            assertTrue(f.successNodes().isEmpty(), "平台结果仍未完成，不能按固定延时显示成功")
            f.finish(LogExportOutcome.Written)
            f.assertOneSuccess()
            assertEquals(buttonBefore, f.exportButton().boundsInRoot, "结果层不能推挤原导出按钮")
            assertEquals(logBefore, f.logBounds(), "结果层不能参与原日志布局")
            f.route(AppRoute.Home)
            assertTrue(f.hasText("SoftVoWiFi"), "前提：真实App已切换到首页根")
            f.assertOneSuccess()
            f.route(AppRoute.SettingsLogs)
            f.advance(1000)
            f.export()
            assertEquals(2, f.requests)
            f.finish(LogExportOutcome.Written)
            assertEquals(2L, f.completion.value?.sequence)
            f.assertOneSuccess()
            f.advance(1200) // 已越过第一次的2000ms期限，第二次仍应存在。
            f.assertOneSuccess()
            f.advance(1200)
            assertTrue(f.successNodes().isEmpty(), "最后一次2000ms结果到期后必须真正移除")
            f.advance(2400)
            assertTrue(f.successNodes().isEmpty(), "被替换的旧Written不能排队复活")
            assertEquals(2, f.requests)
        }
    } }

    @Test fun cancelledPlatformResultsNeverShowSuccessOrQueueAnotherExport() = onMotionTestThread { runTest {
        AppExportFixture(testScheduler).use { f ->
            repeat(2) {
                f.export()
                assertTrue(f.busy.value)
                assertTrue(f.successNodes().isEmpty())
                f.finish(LogExportOutcome.Cancelled)
                assertFalse(f.busy.value)
                assertTrue(f.successNodes().isEmpty(), "选择目标取消不等于Written")
                assertFalse(f.hasText(FAILURE), "取消也不能伪装写入失败")
                f.advance(2400)
                assertTrue(f.successNodes().isEmpty())
                assertEquals(it + 1, f.requests, "取消结果不得自动重试或触发下一次平台导出")
            }
        }
    } }

    @Test fun failedExportStaysInTheActualLogsPageAndItsRealButtonCanSubmitAgain() = onMotionTestThread { runTest {
        AppExportFixture(testScheduler).use { f ->
            f.export()
            f.finish(LogExportOutcome.Failed)
            assertTrue(f.hasText(FAILURE), "真实Logs页须有固定安全的持久失败说明，不只是系统toast")
            assertTrue(f.successNodes().isEmpty())
            f.advance(4200)
            assertTrue(f.hasText(FAILURE), "失败说明不能随2000ms结果提示超时消失")
            assertFalse(f.exportButton().config.contains(SemanticsProperties.Disabled), "导出失败后须保留真实再次导出入口")
            f.export()
            assertEquals(2, f.requests)
            assertTrue(f.busy.value)
            assertTrue(f.successNodes().isEmpty(), "重试仍须等待新的平台结果")
            f.finish(LogExportOutcome.Written)
            f.assertOneSuccess()
            assertFalse(f.hasText(FAILURE), "真实重试成功后旧写入失败应解决")
            assertEquals(2, f.requests)
        }
    } }

    @Test fun writtenAppResultHonorsTheAccessibilityRecommendedTimeout() = onMotionTestThread { runTest {
        var recommended = false
        val accessibility = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(originalTimeoutMillis: Long, containsIcons: Boolean,
                containsText: Boolean, containsControls: Boolean): Long {
                if (originalTimeoutMillis == 2000L && containsText) { recommended = true; return 5000L }
                return originalTimeoutMillis
            }
        }
        AppExportFixture(testScheduler, accessibility).use { f ->
            f.export(); f.finish(LogExportOutcome.Written)
            f.advance(2500); f.assertOneSuccess()
            f.advance(2300); f.assertOneSuccess()
            f.advance(400)
            assertTrue(f.successNodes().isEmpty())
            assertTrue(recommended, "实际App结果层须请求2000ms文字反馈的推荐时长")
            assertEquals(1, f.requests)
        }
    } }

    private class AppExportFixture(private val scheduler: TestCoroutineScheduler,
        accessibility: AccessibilityManager? = null) : AutoCloseable {
        val busy = mutableStateOf(false)
        val completion = mutableStateOf<LogExportCompletion?>(null)
        var requests = 0
            private set
        var payload: String? = null
            private set
        private var sequence = 0L
        private val routes = Channel<AppRoute>(Channel.CONFLATED)
        private val incoming = routes.receiveAsFlow()
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val back = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        private val sources = SoftVoWiFiSources(
            FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
            FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"),
            FixtureEnvironmentDiagnosticsSource("empty"), FixtureOperationalLogSource("ready"),
            UnavailableMessageSource(), FixtureCallSource("unavailable"),
        )
        private val environment = MotionTestEnvironment(scheduler, width = 420, height = 1100, durationScale = 0f,
            dispatcher = StandardTestDispatcher(scheduler)) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalAccessibilityManager provides accessibility,
                LocalLifecycleOwner provides lifecycle,
                LocalNavigationEventDispatcherOwner provides back) {
                SoftVoWiFiApp(sources = sources, onExportLogs = { formatted ->
                    requests++
                    payload = formatted
                    busy.value = true // 平台写入/选择边界保持未完成，完成只能由测试显式发布。
                }, exportBusy = busy.value, appVersion = "test", initialDestination = AppDestination.SETTINGS,
                    initialSubpage = AppRoute.SettingsLogs, incomingRoutes = incoming, forceDark = false,
                    exportCompletion = completion.value)
            }
        }
        init { frames(40) }
        fun frames(count: Int = 2) { repeat(count) { environment.advanceFrameBy(16) } }
        fun advance(milliseconds: Long) { environment.advanceFrameBy(milliseconds); frames() }
        private fun nodes(merged: Boolean = false): List<SemanticsNode> = environment.scene.semanticsOwners.flatMap { owner ->
            fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
            tree(if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode)
        }.filter { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 &&
            it.boundsInRoot.center.x in 0f..420f && it.boundsInRoot.center.y in 0f..1100f }
        fun hasText(value: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        fun successNodes() = nodes().filter {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains(SUCCESS) } == true
        }
        fun assertOneSuccess() {
            val found = successNodes()
            assertEquals(1, found.size, "全App必须恰有一个Written结果，不叠放镜像host或重复结果")
            assertEquals(LiveRegionMode.Polite, found.single().config.getOrNull(SemanticsProperties.LiveRegion))
        }
        fun exportButton() = nodes(merged = true).single {
            it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "导出日志" } == true
        }
        fun logBounds(): Rect = nodes().single {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "2026-08-17 12:34:58.000" } == true
        }.boundsInRoot
        fun export() {
            val state = sources.logs.state.value as? OperationalLogUiState.Ready
            assertTrue(state != null && state.filteredEntries.isNotEmpty(), "前提：原fixture须有合法可导出数据，不绕过真实入口")
            val button = exportButton()
            assertFalse(button.config.contains(SemanticsProperties.Disabled), "前提：真实导出按钮须可用")
            val point = button.boundsInRoot.center
            environment.press(point); environment.advanceFrameBy(8); environment.release(point); frames()
        }
        fun finish(outcome: LogExportOutcome) {
            assertTrue(busy.value && payload != null, "平台完成必须对应真实按钮已提交的未完成导出")
            sequence++
            busy.value = false
            completion.value = LogExportCompletion(sequence, outcome)
            frames()
        }
        fun route(route: AppRoute) {
            assertTrue(routes.trySend(route).isSuccess)
            frames(4)
        }
        override fun close() {
            environment.close()
            routes.close()
            back.navigationEventDispatcher.dispose()
            sources.observer.close(); sources.engine.close(); sources.simControl.close()
            sources.configuration.close(); sources.diagnostics.close(); sources.logs.close()
            sources.messages.close(); sources.calls.close()
        }
    }
    companion object {
        private const val SUCCESS = "脱敏日志已导出"
        private const val FAILURE = "无法写入导出文件"
    }
}
