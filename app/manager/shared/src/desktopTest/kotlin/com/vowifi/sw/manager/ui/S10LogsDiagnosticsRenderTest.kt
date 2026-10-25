@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.EnvironmentDiagnosticsSource
import com.vowifi.sw.manager.data.FixtureMessageSource
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.desktop.FixtureCallSource
import com.vowifi.sw.manager.desktop.FixtureConfigurationSource
import com.vowifi.sw.manager.desktop.FixtureEngineControlSource
import com.vowifi.sw.manager.desktop.FixtureObserverLogSource
import com.vowifi.sw.manager.desktop.FixtureOperationalLogSource
import com.vowifi.sw.manager.desktop.FixtureSimControlSource
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class S10LogsDiagnosticsRenderTest {
    @Test fun groupedRecordsFollowExportAndKeepOneRealDetailOwnerAndTheOriginalExportOrder() = onMotionTestThread { runTest {
        LogRig(testScheduler).use { f ->
            val first = s4Action(f.e, "合成告警").boundsInRoot
            val second = s4Action(f.e, "合成开始").boundsInRoot
            assertEquals(1f, second.top - first.bottom, .5f, "同卡记录之间只留1dp分隔线，不留独立卡间距")
            val export = s4Action(f.e, "导出日志")
            assertTrue(f.text("日志来源").boundsInRoot.top < export.boundsInRoot.top)
            assertTrue(export.boundsInRoot.bottom < first.top, "导出须在记录前而非滚到末尾")
            val oldExport = export.config[SemanticsActions.OnClick].action!!
            f.tap("导出日志")
            assertEquals(listOf(f.entries), f.exports, "不把逆序显示或关键记录窗口冒作原导出输入")
            f.busy.value = true; f.frames()
            oldExport()
            assertEquals(1, f.exports.size)
            f.tap("合成告警")
            // Overlay容器也有点击语义；只计真实按钮自身，不递归把容器再次计为详情owner。
            val copies = s4Actions(f.e, "复制详情").filter { node ->
                node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "复制详情" } == true
            }
            assertEquals(1, copies.size)
            assertEquals(1, s4Nodes(f.e).count { node -> node.config.getOrNull(SemanticsProperties.Text)
                ?.any { it.text == "技术详情" } == true }, "单一行点击只能持有一个详情窗口")
            assertTrue(s4HasText(f.e, "合成技术详情"))
            val close = s4Actions(f.e, "关闭").single { node ->
                node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "关闭" } == true
            }
            s4Tap(f.e, close.boundsInRoot.center); f.frames(12)
            assertFalse(s4HasText(f.e, "复制详情"))
            val oldEntry = s4Action(f.e, "合成告警").config[SemanticsActions.OnClick].action!!
            f.active.value = false; f.frames()
            oldEntry(); f.frames()
            assertFalse(s4HasText(f.e, "复制详情"), "失活不能从旧行重新打开详情")
        }
    } }

    @Test fun aWindowLimitIsOnlyARecordCaptionButReadFailureAndRetryStayAboveFilters() = onMotionTestThread { runTest {
        LogRig(testScheduler, truncated = true).use { f ->
            assertFalse(s4HasText(f.e, "最近日志窗口"), "仅截断不能再显示旧告警面板标题")
            val caption = s4Nodes(f.e).single { node -> node.config.getOrNull(SemanticsProperties.Text)
                ?.any { it.text.startsWith("最近日志窗口：") } == true }
            assertTrue(caption.boundsInRoot.top > s4Action(f.e, "导出日志").boundsInRoot.bottom)
            assertTrue(caption.boundsInRoot.bottom < s4Action(f.e, "合成告警").boundsInRoot.top)
            f.logs.value = OperationalLogUiState.Ready(f.entries, listOf(f.file), f.file.id,
                loadInfo = OperationalLogLoadInfo(cached = true, issue = "合成读取失败"))
            f.frames()
            assertTrue(f.text("日志读取失败").boundsInRoot.top < f.text("日志来源").boundsInRoot.top)
            assertEquals(1, s4Actions(f.e, "重试读取日志").size)
            f.tap("重试读取日志")
            assertEquals(1, f.refreshes)
            assertTrue(s4HasText(f.e, "合成告警"))
            assertFalse(s4HasText(f.e, "日志读取失败"))
        }
    } }

    @Test fun realDiagnosticDestinationOpensConfigurationAndHomeWithoutAnExternalSettingsHandler() = onMotionTestThread { runTest {
        DiagnosticRig(testScheduler, wholeApp = true).use { f ->
            assertEquals(1, s4Actions(f.e, CONFIGURATION).size)
            assertEquals(1, s4Actions(f.e, HOME).size)
            f.tap(CONFIGURATION)
            assertTrue(s4HasText(f.e, "SIM 与网络配置"))
            f.tap("返回")
            assertTrue(s4HasText(f.e, CONFIGURATION), "返回应保留诊断来源而非清成设置根")
            f.tap(HOME)
            assertEquals(1, s4Actions(f.e, "刷新首页状态").size, "必须由真实App路由切到首页")
            assertFalse(s4HasText(f.e, CONFIGURATION))
        }
    } }

    @Test fun healthyOrInactiveChecksRevokeTheirOldRecoveryActionsAtNormalAndLargeFont() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 2f)) DiagnosticRig(testScheduler, fontScale = scale).use { f ->
            val configuration = s4Action(f.e, CONFIGURATION)
            val home = s4Action(f.e, HOME)
            for (node in listOf(configuration, home)) assertTrue(node.boundsInRoot.height >= 48f &&
                node.boundsInRoot.left >= 0f && node.boundsInRoot.right <= 420f && node.boundsInRoot.bottom <= 1600f)
            val oldConfig = configuration.config[SemanticsActions.OnClick].action!!
            val oldHome = home.config[SemanticsActions.OnClick].action!!
            f.tap(CONFIGURATION); f.tap(HOME)
            assertEquals(1, f.opens); assertEquals(1, f.homes)
            f.diagnostics.value = diagnosticState(healthy = true); f.frames()
            assertFalse(s4HasText(f.e, CONFIGURATION)); assertFalse(s4HasText(f.e, HOME))
            oldConfig(); oldHome()
            assertEquals(1, f.opens, "已通过后旧恢复动作不能继续导航")
            assertEquals(1, f.homes)
            f.diagnostics.value = diagnosticState(); f.frames()
            val inactive = s4Action(f.e, CONFIGURATION).config[SemanticsActions.OnClick].action!!
            f.active.value = false; f.frames(); inactive()
            assertEquals(1, f.opens)
        }
    } }

    private class LogRig(scheduler: TestCoroutineScheduler, truncated: Boolean = false) : AutoCloseable {
        val entries = listOf(
            OperationalLogEntry("2026-10-24 00:00:01", OperationalLogLevel.INFO, OperationalLogKind.ENGINE,
                "合成线路", OperationalLogMessage.ENGINE_STARTED, "合成开始摘要", "合成开始详情", headline = "合成开始"),
            OperationalLogEntry("2026-10-24 00:00:02", OperationalLogLevel.WARN, OperationalLogKind.IMS,
                "合成线路", OperationalLogMessage.NEEDS_ATTENTION, "合成告警摘要", "合成技术详情", headline = "合成告警"))
        val file = OperationalLogFile("app-diagnostics-v1", "app.jsonl", "")
        val logs = mutableStateOf<OperationalLogUiState>(if (truncated) OperationalLogUiState.Warning(entries, true,
            emptyList(), listOf(file), file.id) else OperationalLogUiState.Ready(entries, listOf(file), file.id))
        val busy = mutableStateOf(false)
        val active = mutableStateOf(true)
        val exports = mutableListOf<List<OperationalLogEntry>>()
        var refreshes = 0
        val e = MotionTestEnvironment(scheduler, 420, 1600, durationScale = 0f) { s4SmsHost {
            CompositionLocalProvider(LocalAppRouteActive provides active.value) {
                SettingsLogsScreen(logs.value, SettingsLogsActions({}, {}, {}, {}, { exports += it }), busy.value,
                    onRefresh = {
                        refreshes++
                        val current = logs.value
                        if (current is OperationalLogUiState.Ready) logs.value = current.copy(loadInfo = OperationalLogLoadInfo())
                        RefreshResult.Completed
                    })
            }
        } }
        init { frames() }
        fun frames(count: Int = 4) { repeat(count) { e.advanceFrameBy(16) } }
        fun tap(label: String) { s4Tap(e, s4Action(e, label).boundsInRoot.center); frames() }
        fun text(value: String) = s4Nodes(e).single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        override fun close() = e.close()
    }

    private class DiagnosticRig(scheduler: TestCoroutineScheduler, wholeApp: Boolean = false,
        fontScale: Float = 1f) : AutoCloseable {
        val diagnostics = MutableStateFlow<EnvironmentDiagnosticsUiState>(diagnosticState())
        val active = mutableStateOf(true)
        var opens = 0; var homes = 0
        private val source = object : EnvironmentDiagnosticsSource {
            override val state = diagnostics
            override fun refresh() = Unit
        }
        private val sources = SoftVoWiFiSources(FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
            FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"), source,
            FixtureOperationalLogSource("empty"), FixtureMessageSource("empty"), FixtureCallSource("idle"))
        val e = MotionTestEnvironment(scheduler, 420, 1600, durationScale = 0f,
            dispatcher = StandardTestDispatcher(scheduler)) { s4SmsHost {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalAppRouteActive provides active.value) {
                if (wholeApp) SoftVoWiFiApp(sources, {}, false, "test", initialSubpage = AppRoute.SettingsDiagnostics,
                    appearanceSource = InMemoryAppearanceSettingsSource(AppearanceSettings.Default.copy(blurRequested = false)))
                else SettingsDiagnosticsScreen(diagnostics.collectAsState().value,
                    onOpenConfiguration = { opens++ }, onHome = { homes++ })
            }
        } }
        init { frames() }
        fun frames(count: Int = 6) { repeat(count) { e.advanceFrameBy(16) } }
        fun tap(label: String) { s4Tap(e, s4Action(e, label).boundsInRoot.center); frames(24) }
        override fun close() = e.close()
    }

    private companion object {
        const val CONFIGURATION = "前往 SIM 与网络配置"
        const val HOME = "前往首页"
        fun diagnosticState(healthy: Boolean = false): EnvironmentDiagnosticsUiState.Ready {
            val checks = listOf(DiagnosticCheckId.CONFIG, DiagnosticCheckId.ENGINE, DiagnosticCheckId.ROOT,
                DiagnosticCheckId.MODULE, DiagnosticCheckId.WFC).map { id -> DiagnosticCheck(id,
                if (healthy) DiagnosticCheckStatus.PASS else if (id == DiagnosticCheckId.ENGINE) DiagnosticCheckStatus.WARN else DiagnosticCheckStatus.FAIL,
                "合成 $id", "合成检查结果", "请查看应用内信息") }
            return EnvironmentDiagnosticsUiState.Ready(if (healthy) DiagnosticCheckStatus.PASS else DiagnosticCheckStatus.FAIL, checks)
        }
    }
}
