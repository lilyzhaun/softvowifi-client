package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogFile
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogMessage
import com.vowifi.sw.manager.model.OperationalLogUiState
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticsScreenTest {
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
        assertEquals(listOf("全部", "引擎", "隧道", "IMS"), operationalLogKindOptions().map { it.second })
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
        assertEquals("引擎 •••• 1234", operationalLogFileLabel(OperationalLogFile("secret-id", "engine.log", "1234")))
        assertEquals("启动 •••• 5678", operationalLogFileLabel(OperationalLogFile("/private/id", "launcher.log", "5678")))
        assertEquals("日志 •••• 9999", operationalLogFileLabel(OperationalLogFile("raw-id", "/private/raw.log", "token-9999")))
        assertEquals("引擎 ••••", operationalLogFileLabel(OperationalLogFile("raw-id", "engine.log", "../")))
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
        assertEquals(StatePanelTone.WARNING, operationalLogWarningSpec().tone)
        assertEquals("日志可能不完整", operationalLogWarningSpec().title)
        assertEquals("已显示可用的运行事件；部分来源暂时不可用。", operationalLogWarningSpec().description)
        assertEquals("暂无运行日志", operationalLogFilteredEmptySpec().title)
        assertEquals("当前筛选条件下没有可显示的事件。", operationalLogFilteredEmptySpec().description)
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
