package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.DiagnosticCheck
import com.vowifi.sw.manager.model.DiagnosticCheckId
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogFile
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogMessage
import com.vowifi.sw.manager.model.OperationalLogUiState
import com.vowifi.sw.manager.model.OperationalLogParseIssue
import com.vowifi.sw.manager.model.OperationalLogLoadInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Report

class DiagnosticsScreenTest {
    @Test fun statusIconsDistinguishPassFailAndWarningWithoutChangingTheirTones() {
        assertEquals(MiuixIcons.Ok, diagnosticStatusIcon(DiagnosticCheckStatus.PASS))
        assertEquals(MiuixIcons.Close, diagnosticStatusIcon(DiagnosticCheckStatus.FAIL))
        assertEquals(MiuixIcons.Report, diagnosticStatusIcon(DiagnosticCheckStatus.WARN))
        assertEquals(3, DiagnosticCheckStatus.entries.map(::diagnosticStatusIcon).distinct().size)
    }

    @Test fun onlyFailedOrWarningConfigurationAndEngineChecksHaveInternalRecoveryRoutes() {
        for (id in DiagnosticCheckId.entries) for (status in DiagnosticCheckStatus.entries) {
            val check = DiagnosticCheck(id, status, "合成标题不用于路由", "合成说明不用于路由")
            val expected: AppRoute? = if (status == DiagnosticCheckStatus.PASS) null else when (id) {
                DiagnosticCheckId.CONFIG -> AppRoute.SettingsConfiguration
                DiagnosticCheckId.ENGINE -> AppRoute.Home
                else -> null
            }
            assertEquals(expected, diagnosticRecoveryRoute(check), "$id/$status")
        }
    }

    @Test fun truncationAloneIsARecordWindowFactRatherThanATopWarning() {
        val truncated = OperationalLogUiState.Warning(emptyList(), true, emptyList())
        assertNull(operationalLogDataWarningSpec(truncated))
        assertTrue(operationalLogWindowNote(truncated)!!.contains("最近日志窗口"))
        assertTrue(operationalLogWindowNote(truncated)!!.contains("最近50条"))
        assertNull(operationalLogWindowNote(OperationalLogUiState.Ready(emptyList())))
    }

    @Test fun readyReadFailuresAndParseProblemsKeepTheirTopWarningAndOriginalMetadata() {
        val ready = OperationalLogUiState.Ready(emptyList(), loadInfo = OperationalLogLoadInfo(cached = true, issue = "合成读取超时"))
        val read = requireNotNull(operationalLogDataWarningSpec(ready))
        assertEquals("日志读取失败", read.title)
        assertEquals(StatePanelTone.ERROR, read.tone)
        assertTrue(read.description.contains("上次读取的记录"))
        val parse = OperationalLogUiState.Warning(emptyList(), true, listOf(OperationalLogParseIssue(2, "synthetic-private")))
        val warning = requireNotNull(operationalLogDataWarningSpec(parse))
        assertEquals("部分日志无法解析", warning.title)
        assertFalse(warning.description.contains("synthetic-private"))
        assertEquals("合成读取超时", ready.loadInfo.issue)
        assertEquals(1, parse.issues.size)
    }

    @Test
    fun diagnosticsUiContractDefines12DpCardGaps() {
        val contract = diagnosticsUiContract()
        assertEquals(12, contract.cardGapDp)
        assertEquals(12, contract.overallToChecksGapDp)
    }

    @Test
    fun mapsEveryDiagnosticStatusToItsSemanticTone() {
        assertEquals(DiagnosticTone.SUCCESS, diagnosticTone(DiagnosticCheckStatus.PASS))
        assertEquals(DiagnosticTone.WARNING, diagnosticTone(DiagnosticCheckStatus.WARN))
        assertEquals(DiagnosticTone.ERROR, diagnosticTone(DiagnosticCheckStatus.FAIL))
    }

    @Test
    fun formatsCompactSummaryAsOneCompleteLine() {
        assertEquals(
            listOf("已完成 6 项环境检查 · 通过"),
            diagnosticSummaryLines(DiagnosticCheckStatus.PASS, checkCount = 6, stacked = false),
        )
    }

    @Test
    fun formatsLargeFontSummaryAsTwoCompleteLines() {
        assertEquals(
            listOf("6 项检查已完成", "结果：需注意"),
            diagnosticSummaryLines(DiagnosticCheckStatus.WARN, checkCount = 6, stacked = true),
        )
        assertEquals(
            listOf("6 项检查已完成", "结果：未通过"),
            diagnosticSummaryLines(DiagnosticCheckStatus.FAIL, checkCount = 6, stacked = true),
        )
    }

    @Test
    fun mapsEveryOperationalLogLevelToLabelAndTone() {
        assertEquals("信息", operationalLogLevelLabel(OperationalLogLevel.INFO))
        assertEquals("告警", operationalLogLevelLabel(OperationalLogLevel.WARN))
        assertEquals("错误", operationalLogLevelLabel(OperationalLogLevel.ERROR))
        assertEquals(OperationalLogTone.INFORMATION, operationalLogTone(OperationalLogLevel.INFO))
        assertEquals(OperationalLogTone.WARNING, operationalLogTone(OperationalLogLevel.WARN))
        assertEquals(OperationalLogTone.ERROR, operationalLogTone(OperationalLogLevel.ERROR))
    }

    @Test
    fun mapsEveryOperationalLogKindToLocalizedLayer() {
        assertEquals("引擎", operationalLogKindLabel(OperationalLogKind.ENGINE))
        assertEquals("隧道", operationalLogKindLabel(OperationalLogKind.TUNNEL))
        assertEquals("IMS", operationalLogKindLabel(OperationalLogKind.IMS))
    }

    @Test
    fun exposesEveryKindAndLevelFilterLabel() {
        assertEquals(listOf("全部", "引擎", "隧道", "IMS", "诊断"), operationalLogKindOptions().map { it.second })
        assertEquals(listOf("全部", "信息", "告警", "错误"), operationalLogLevelOptions().map { it.second })
    }

    @Test
    fun mapsEveryTypedMessageWithoutRawFallback() {
        val expected = listOf(
            "引擎已启动",
            "引擎已停止",
            "正在建立隧道",
            "隧道已连接",
            "隧道已断开",
            "IMS 正在注册",
            "IMS 已注册",
            "IMS 未注册",
            "操作需要注意",
            "操作未完成",
            "运行事件",
            "未分类事件",
        )
        assertEquals(expected, OperationalLogMessage.entries.map(::operationalLogMessageLabel))
    }

    @Test
    fun derivesCatalogLabelsWithoutDisplayingIdOrPath() {
        // U4取代文件来源标签；身份/路径仍不展示，事件kind标签守护不变。
        assertEquals("运行日志 •••• 1234", operationalLogFileLabel(OperationalLogFile("secret-id", "engine.log", "1234")))
        assertEquals("运行日志 •••• 5678", operationalLogFileLabel(OperationalLogFile("/private/id", "launcher.log", "5678")))
        assertEquals("运行日志 •••• 9999", operationalLogFileLabel(OperationalLogFile("raw-id", "/private/raw.log", "token-9999")))
        assertEquals("运行日志 ••••", operationalLogFileLabel(OperationalLogFile("raw-id", "engine.log", "../")))
    }

    @Test
    fun mapsEveryBoundedLogStateCopyAndFilteredEmpty() {
        val states = listOf(
            OperationalLogUiState.Loading to ("正在读取运行日志" to "完成后将显示最近的运行事件。"),
            OperationalLogUiState.Empty to ("暂无运行日志" to "当前没有可显示的事件。"),
            OperationalLogUiState.Failure("raw failure") to ("日志读取失败" to "无法读取运行日志，请稍后刷新。"),
            OperationalLogUiState.PermissionDenied("raw permission") to ("日志权限不足" to "当前权限无法读取运行日志。"),
            OperationalLogUiState.Unavailable("raw unavailable") to ("日志不可用" to "当前环境不提供运行日志。"),
        )
        states.forEach { (state, copy) ->
            val spec = operationalLogStateSpec(state)
            assertEquals(copy.first, spec.title)
            assertEquals(copy.second, spec.description)
        }
        val warning = requireNotNull(operationalLogDataWarningSpec(OperationalLogUiState.Warning(
            emptyList(), false, listOf(OperationalLogParseIssue(1)),
        )))
        assertEquals(StatePanelTone.WARNING, warning.tone)
        assertEquals("部分日志无法解析", warning.title)
        assertEquals("已显示可用的运行事件。有1处日志格式无法解析。", warning.description)
        assertEquals("暂无运行日志", operationalLogFilteredEmptySpec().title)
        assertEquals("当前筛选条件下没有可显示的事件。", operationalLogFilteredEmptySpec().description)
    }

    @Test fun parseIssuesStayWarningsWithOrWithoutTruncationAndNeverExposePayload() {
        for (truncated in listOf(false, true)) {
            val state = OperationalLogUiState.Warning(emptyList(), truncated,
                listOf(OperationalLogParseIssue(1, "synthetic-private-detail"), OperationalLogParseIssue(2)))
            val spec = requireNotNull(operationalLogDataWarningSpec(state))
            assertEquals(StatePanelTone.WARNING, spec.tone)
            assertEquals("部分日志无法解析", spec.title)
            assertTrue(spec.description.contains("有2处日志格式无法解析。"))
            assertFalse(spec.description.contains("最多显示最近50条"))
            assertEquals(truncated, operationalLogWindowNote(state)?.contains("最多显示最近50条") == true)
            assertFalse(spec.description.contains("来源暂时不可用"))
            assertFalse(spec.description.contains("synthetic-private"))
            assertFalse(spec.loading)
        }
    }

    @Test fun windowPresentationRetainsEntriesFiltersAndReadyReadFailureMetadata() {
        val entry = OperationalLogEntry("12:00", OperationalLogLevel.INFO, OperationalLogKind.ENGINE,
            "合成实例", OperationalLogMessage.EVENT, "synthetic event")
        val state = OperationalLogUiState.Warning(listOf(entry), true, emptyList(), filteredEntries = emptyList())
        assertNull(operationalLogDataWarningSpec(state))
        assertTrue(requireNotNull(operationalLogWindowNote(state)).startsWith("最近日志窗口："))
        assertEquals(listOf(entry), state.entries)
        assertTrue(state.filteredEntries.isEmpty())
        val ready = OperationalLogUiState.Ready(state.entries,
            loadInfo = OperationalLogLoadInfo(cached = true, issue = "日志读取超时"))
        assertEquals("日志读取超时", ready.loadInfo.issue)
        assertEquals(state.entries, ready.entries)
        assertEquals(StatePanelTone.ERROR, operationalLogStateSpec(OperationalLogUiState.Failure("raw")).tone)
        assertEquals(StatePanelTone.ERROR, operationalLogStateSpec(OperationalLogUiState.PermissionDenied("raw")).tone)
    }

    @Test
    fun readyFilteredEmptyRemainsATypedEmptyResult() {
        val state = OperationalLogUiState.Ready(
            entries = listOf(
                OperationalLogEntry(
                    timestamp = "2026-08-17 12:34:56.789",
                    level = OperationalLogLevel.INFO,
                    kind = OperationalLogKind.ENGINE,
                    displayLabel = "实例 •••• 1234",
                    message = OperationalLogMessage.ENGINE_STARTED,
                    safeSummary = "engine started",
                ),
            ),
            filteredEntries = emptyList(),
        )
        assertEquals(emptyList(), state.filteredEntries)
        assertEquals("暂无运行日志", operationalLogFilteredEmptySpec().title)
    }
}
