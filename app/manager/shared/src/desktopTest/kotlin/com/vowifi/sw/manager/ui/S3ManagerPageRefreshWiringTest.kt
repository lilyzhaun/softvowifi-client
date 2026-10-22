package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.data.CallNotificationSettingsLaunchResult
import com.vowifi.sw.manager.data.CallNotificationSettingsSource
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Actual page/action-slot/PTR wiring; controlled producer completion, never delay-as-IO. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class S3ManagerPageRefreshWiringTest {
    @Test
    fun configurationDraftAndProvisionPendingCloseBothEntrancesWithoutReplacingTheList() = onMotionTestThread { runTest {
        PageDriver(testScheduler, Kind.CONFIG).use { page ->
            page.frames(24)
            val originalList = page.list().id
            val originalPosition = page.list().config[SemanticsProperties.VerticalScrollAxisRange].value()
            page.provisioning.value = ConfigurationProvisionState(pending = setOf(SimHandle("test-sim")))
            page.frames(3)
            assertTrue(page.action().config.contains(SemanticsProperties.Disabled))
            page.pull()
            page.frames(100)
            assertEquals(0, page.reads)
            assertEquals(originalList, page.list().id)
            assertEquals(originalPosition, page.list().config[SemanticsProperties.VerticalScrollAxisRange].value())

            page.provisioning.value = ConfigurationProvisionState()
            page.frames(12)
            val draft = page.drafts.draftFor(page.instance)
            draft.dns.edit { append("192.0.2.10:53") }
            draft.submit { _, _, _ -> ConfigurationApplyAcceptance.Accepted }
            assertTrue(draft.pending)
            page.frames(3)
            assertTrue(page.action().config.contains(SemanticsProperties.Disabled))
            page.pull()
            page.frames(100)
            assertEquals(0, page.reads)
            assertEquals(originalList, page.list().id)
            assertEquals(originalPosition, page.list().config[SemanticsProperties.VerticalScrollAxisRange].value())
        }
    } }

    @Test
    fun diagnosticsAndLogsExposeNoActionWithoutCallbackAndRealRetryJoinsTheButton() = onMotionTestThread { runTest {
        listOf(Kind.DIAGNOSTICS, Kind.LOGS).forEach { kind ->
            PageDriver(testScheduler, kind, callback = false).use { page ->
                page.frames(24)
                assertFalse(page.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(page.label) == true })
                assertFalse(page.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == page.retry } == true })
                assertEquals(0, page.reads)
            }
            PageDriver(testScheduler, kind).use { page ->
                page.frames(24)
                page.tap(page.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == page.retry } == true })
                page.frames(4)
                assertEquals(1, page.reads)
                assertTrue(page.producer?.isActive == true)
                assertTrue(page.action().config.contains(SemanticsProperties.Disabled))
                page.tap(page.action())
                page.pull()
                page.frames(16)
                assertEquals(1, page.reads)
                page.gate.complete(RefreshResult.Completed)
                page.frames(100)
                assertTrue(page.producer?.isCompleted == true)
                assertFalse(page.action().config.contains(SemanticsProperties.Disabled))
                assertEquals(1, page.reads)
            }
        }
    } }

    @Test
    fun notificationUserActionReadsSynchronouslyWhileEntryRefreshStaysAutomatic() = onMotionTestThread { runTest {
        PageDriver(testScheduler, Kind.NOTIFICATION).use { page ->
            page.frames(24)
            assertEquals(1, page.automaticReads)
            assertEquals(0, page.reads)
            page.tap(page.action())
            page.frames(2)
            assertEquals(1, page.reads)
            assertEquals(1, page.automaticReads)
            page.frames(100)
            page.tap(page.action())
            page.frames(2)
            assertEquals(2, page.reads)
            assertEquals(1, page.automaticReads)
        }
    } }

    private enum class Kind { CONFIG, DIAGNOSTICS, LOGS, NOTIFICATION }
    private class PageDriver(scheduler: TestCoroutineScheduler, kind: Kind, callback: Boolean = true) : AutoCloseable {
        val instance = ConfigurationInstance(ConfigurationInstanceId("test-config"), "测试配置", ConfigurationLifecycle.STOPPED,
            ConfigurationTransport.AUTO, "", "", "", "", resolvedEpdgFqdn = "epdg.example.invalid",
            resolvedImsRealm = "ims.example.invalid", mcc = "001", mnc = "01")
        val drafts = ConfigurationDraftStore()
        val provisioning = MutableStateFlow(ConfigurationProvisionState())
        val gate = CompletableDeferred<RefreshResult>()
        var producer: Deferred<RefreshResult>? = null
        private val producerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
        var reads = 0
        var automaticReads = 0
        private var diagnostic by mutableStateOf<EnvironmentDiagnosticsUiState>(EnvironmentDiagnosticsUiState.Failure("读取失败"))
        private var logs by mutableStateOf<OperationalLogUiState>(OperationalLogUiState.Failure("读取失败"))
        val label = when (kind) { Kind.CONFIG -> "刷新配置"; Kind.DIAGNOSTICS -> "刷新诊断"; Kind.LOGS -> "刷新运行日志"; Kind.NOTIFICATION -> "刷新通知状态" }
        val retry = if (kind == Kind.DIAGNOSTICS) "重试检查" else "重试读取日志"
        private val route: AppRoute.Subpage = when (kind) { Kind.CONFIG -> AppRoute.SettingsConfiguration; Kind.DIAGNOSTICS -> AppRoute.SettingsDiagnostics; Kind.LOGS -> AppRoute.SettingsLogs; Kind.NOTIFICATION -> AppRoute.SettingsCallNotifications }
        private val back = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val window = object : WindowInfo {
            override val isWindowFocused = true
            override val containerSize = IntSize(390, 800)
            override val containerDpSize = DpSize(390.dp, 800.dp)
        }
        private suspend fun awaitRead(): RefreshResult {
            producer?.takeIf { it.isActive }?.let { return it.await() }
            val read = producerScope.async {
                reads++
                val result = gate.await()
                if (result == RefreshResult.Completed) {
                    diagnostic = EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.PASS, emptyList())
                    logs = OperationalLogUiState.Empty
                }
                result
            }
            producer = read
            return read.await()
        }
        private val notifications = object : CallNotificationSettingsSource {
            override val state = MutableStateFlow(CallNotificationSettingsSnapshot(NotificationPermissionStatus.DENIED, FullScreenStatus.DENIED,
                channel = IncomingCallChannelStatus.MISSING))
            override fun refresh() { automaticReads++ }
            override suspend fun refreshAndAwait(): RefreshResult { reads++; return RefreshResult.Completed }
            override fun openSettings(action: SettingsAction) = CallNotificationSettingsLaunchResult.UNSUPPORTED
        }
        private val environment = MotionTestEnvironment(scheduler, width = 390, height = 800) {
            CompositionLocalProvider(LocalWindowInfo provides window, LocalLifecycleOwner provides lifecycle,
                LocalNavigationEventDispatcherOwner provides back) {
                SoftVoWiFiTheme(false) {
                    AppSubpageScene(route, appChromeSpec(AppearanceSettings.Default, false), onBack = {}) {
                        when (kind) {
                            Kind.CONFIG -> SettingsConfigurationScreen(ConfigurationUiState.Ready(listOf(instance)), null,
                                SettingsConfigurationActions({ _, _, _ -> ConfigurationApplyAcceptance.Unavailable }, {}, ::awaitRead),
                                drafts = drafts, provisioning = provisioning)
                            Kind.DIAGNOSTICS -> SettingsDiagnosticsScreen(diagnostic, if (callback) ::awaitRead else null)
                            Kind.LOGS -> SettingsLogsScreen(logs, SettingsLogsActions({}, {}, {}, {}, {}), false,
                                if (callback) ::awaitRead else null)
                            Kind.NOTIFICATION -> SettingsCallNotificationsScreen(notifications)
                        }
                    }
                }
            }
        }
        fun frames(count: Int) { repeat(count) { environment.advanceFrameBy() } }
        fun nodes(): List<SemanticsNode> {
            fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
            return environment.scene.semanticsOwners.flatMap { tree(it.rootSemanticsNode) }
        }
        fun list() = nodes().single { it.config.contains(SemanticsActions.ScrollToIndex) }
        fun action() = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
        fun tap(node: SemanticsNode) { environment.press(node.boundsInRoot.center); frames(2); environment.release(node.boundsInRoot.center) }
        fun pull() {
            val bounds = list().boundsInRoot
            val start = Offset(bounds.center.x, bounds.top + 220f)
            environment.press(start); frames(1)
            repeat(4) { index -> environment.move(start + Offset(0f, 180f * (index + 1) / 4)); frames(1) }
            frames(12); environment.release(start + Offset(0f, 180f))
        }
        override fun close() { environment.close(); producerScope.cancel(); back.navigationEventDispatcher.dispose() }
    }
}
