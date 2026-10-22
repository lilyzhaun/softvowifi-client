package com.vowifi.sw.manager.ui

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogMessage
import com.vowifi.sw.manager.model.OperationalLogUiState
import com.vowifi.sw.manager.model.OperationalLogLoadInfo
import com.vowifi.sw.manager.model.OperationalLogParseIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogStateLanguageTest {
    @Test
    fun nonDataLogStatesMapToBoundedSafeManagerStatusWithoutRawExceptionsOrPaths() {
        // Given
        val states = listOf(
            OperationalLogUiState.Loading to ManagerStatus(
                kind = ManagerStatusKind.Loading,
                title = "正在读取运行日志",
                summary = "完成后将显示最近的运行事件。",
                retryDescription = null,
            ),
            OperationalLogUiState.Empty to ManagerStatus(
                kind = ManagerStatusKind.Empty,
                title = "暂无运行日志",
                summary = "当前没有可显示的事件。",
                retryDescription = null,
            ),
            OperationalLogUiState.Failure("IOException /data/local/tmp/app.log IMSI 310260123456789") to ManagerStatus(
                kind = ManagerStatusKind.Failure,
                title = "日志读取失败",
                summary = "无法读取运行日志，请稍后刷新。",
                retryDescription = null,
            ),
            OperationalLogUiState.PermissionDenied("Root permission denied for logcat") to ManagerStatus(
                kind = ManagerStatusKind.PermissionDenied,
                title = "日志权限不足",
                summary = "当前权限无法读取运行日志。",
                retryDescription = null,
            ),
            OperationalLogUiState.Unavailable("Log daemon not installed") to ManagerStatus(
                kind = ManagerStatusKind.Unavailable,
                title = "日志不可用",
                summary = "当前环境不提供运行日志。",
                retryDescription = null,
            ),
        )

        // When & Then
        states.forEach { (state, expectedStatus) ->
            val status = operationalLogManagerStatus(state)
            assertEquals(expectedStatus.kind, status.kind)
            assertEquals(expectedStatus.title, status.title)
            assertEquals(expectedStatus.summary, status.summary)
            assertFalse(status.summary.contains("/data/"))
            assertFalse(status.summary.contains("310260"))
            assertFalse(status.summary.contains("IOException"))
            assertFalse(status.summary.contains("脱敏"))
        }

        val warningStatus = operationalLogWarningStatus(OperationalLogUiState.Warning(
            emptyList(), false, listOf(OperationalLogParseIssue(1, "synthetic-private-detail")),
        ))
        assertEquals(ManagerStatusKind.Warning, warningStatus.kind)
        assertEquals("部分日志无法解析", warningStatus.title)
        assertEquals("已显示可用的运行事件。有1处日志格式无法解析。", warningStatus.summary)
        assertFalse(warningStatus.summary.contains("脱敏"))

        val filteredEmptyStatus = operationalLogFilteredEmptyStatus()
        assertEquals(ManagerStatusKind.Empty, filteredEmptyStatus.kind)
        assertEquals("暂无运行日志", filteredEmptyStatus.title)
        assertEquals("当前筛选条件下没有可显示的事件。", filteredEmptyStatus.summary)
        assertFalse(filteredEmptyStatus.summary.contains("脱敏"))
    }

    @Test fun truncatedWindowUsesNeutralAtMostCopyWithoutChangingLoadMetadata() {
        val info = OperationalLogLoadInfo(reading = true, cached = true, legacy = true)
        val state = OperationalLogUiState.Warning(emptyList(), true, emptyList(), loadInfo = info)
        val status = operationalLogWarningStatus(state)
        assertEquals(ManagerStatusKind.Unavailable, status.kind)
        assertEquals("最近日志窗口", status.title)
        // U4 unified groups retain a bounded window per source, not a fictitious 50-row merged cap.
        assertEquals("已显示可用的运行事件。每个来源最多显示最近50条运行记录；更早记录未在此窗口显示。", status.summary)
        assertEquals(StatePanelTone.NEUTRAL, status.toStatePanelSpec().tone)
        assertFalse(status.summary.contains("不可用"))
        assertEquals(info, state.loadInfo)
        assertTrue(state.truncated)
        assertTrue(state.entries.isEmpty())
    }

    @Test fun readIssueNeverBecomesNeutralEvenWhenOnlyCachedWindowWasTruncated() {
        for (issues in listOf(emptyList(), listOf(OperationalLogParseIssue(1, "synthetic-private-detail")))) {
            val state = OperationalLogUiState.Warning(emptyList(), true, issues,
                loadInfo = OperationalLogLoadInfo(cached = true, issue = "synthetic-private-read-detail"))
            val status = operationalLogWarningStatus(state)
            assertEquals(ManagerStatusKind.Failure, status.kind)
            assertEquals(StatePanelTone.ERROR, status.toStatePanelSpec().tone)
            assertEquals("日志读取失败", status.title)
            assertTrue(status.summary.contains("最多显示最近50条"))
            assertTrue(status.summary.contains("本次日志读取未完成，请刷新重试。"))
            assertEquals(issues.isNotEmpty(), status.summary.contains("有1处日志格式无法解析。"))
            assertFalse(status.summary.contains("synthetic-private"))
            assertEquals(issues, state.issues)
        }
    }

    @Test
    fun logExportActionProvidesConfirmHapticAndExactPointNinetySixPressScaleWhenEnabled() {
        // Given
        val presentation = logExportPresentation(hasFilteredEntries = true, busy = false)

        // When
        val feedback = managerActionFeedback(ManagerAction.ExportLogs, enabled = presentation.enabled)

        // Then
        assertTrue(presentation.enabled)
        assertEquals("导出日志", presentation.label)
        assertEquals("导出前会自动隐藏号码、密钥、地址、路径和短信内容。", logPrivacyNote())
        assertTrue(feedback.accepted)
        assertEquals(HapticFeedbackType.Confirm, feedback.hapticType)
        assertEquals(0.96f, feedback.pressScale)
    }

    @Test
    fun logExportActionIsDisabledAndProvidesNoHapticWhenBusyOrNoEntries() {
        // Given
        val busyPresentation = logExportPresentation(hasFilteredEntries = true, busy = true)
        val emptyPresentation = logExportPresentation(hasFilteredEntries = false, busy = false)

        // When
        val busyFeedback = managerActionFeedback(ManagerAction.ExportLogs, enabled = busyPresentation.enabled)
        val emptyFeedback = managerActionFeedback(ManagerAction.ExportLogs, enabled = emptyPresentation.enabled)

        // Then
        assertFalse(busyPresentation.enabled)
        assertEquals("正在准备日志", busyPresentation.label)
        assertFalse(busyFeedback.accepted)
        assertNull(busyFeedback.hapticType)

        assertFalse(emptyPresentation.enabled)
        assertEquals("导出日志", emptyPresentation.label)
        assertFalse(emptyFeedback.accepted)
        assertNull(emptyFeedback.hapticType)
    }

    @Test
    fun logEntrySemanticBadgesMapLevelToCorrectTone() {
        // Given
        val infoEntry = OperationalLogEntry("12:00", OperationalLogLevel.INFO, OperationalLogKind.ENGINE, "SIM 1", OperationalLogMessage.ENGINE_STARTED, "engine started")
        val warnEntry = OperationalLogEntry("12:01", OperationalLogLevel.WARN, OperationalLogKind.TUNNEL, "SIM 1", OperationalLogMessage.NEEDS_ATTENTION, "needs attention")
        val errorEntry = OperationalLogEntry("12:02", OperationalLogLevel.ERROR, OperationalLogKind.IMS, "SIM 1", OperationalLogMessage.OPERATION_FAILED, "operation failed")

        // When & Then
        assertEquals(SemanticTone.NEUTRAL, logEntrySemanticBadgeSpec(infoEntry).tone)
        assertEquals(SemanticTone.WARNING, logEntrySemanticBadgeSpec(warnEntry).tone)
        assertEquals(SemanticTone.ERROR, logEntrySemanticBadgeSpec(errorEntry).tone)
        assertEquals("信息", logEntrySemanticBadgeSpec(infoEntry).label)
        assertEquals("告警", logEntrySemanticBadgeSpec(warnEntry).label)
        assertEquals("错误", logEntrySemanticBadgeSpec(errorEntry).label)
    }

    @Test
    fun logFilterPresentationAndActionsMaintainCleanBundles() {
        // Given
        val filter = com.vowifi.sw.manager.model.OperationalLogFilter()
        val presentation = LogFilterPresentation(
            files = emptyList(),
            selectedFileId = "f1",
            filter = filter,
        )
        var selectedFileId = ""
        val actions = LogFilterActions(
            onSelectFile = { selectedFileId = it.id },
            onKindSelected = {},
            onLevelSelected = {},
        )

        // When
        actions.onSelectFile(com.vowifi.sw.manager.model.OperationalLogFile("f1", "engine.log", "1234"))

        // Then
        assertEquals("f1", presentation.selectedFileId)
        assertEquals("f1", selectedFileId)
    }

    @Test
    fun operationalLogEntryTitleAndMessageUseSafeSummaryAndKindSpecificTitles() {
        val knownEntry = OperationalLogEntry(
            timestamp = "2026-08-29 18:30:00.123",
            level = OperationalLogLevel.INFO,
            kind = OperationalLogKind.ENGINE,
            displayLabel = "实例 •••• 1234",
            message = OperationalLogMessage.ENGINE_STARTED,
            safeSummary = "engine started",
        )
        val eventEntry = OperationalLogEntry(
            timestamp = "2026-08-29 18:30:01.000",
            level = OperationalLogLevel.INFO,
            kind = OperationalLogKind.TUNNEL,
            displayLabel = "实例 •••• 1234",
            message = OperationalLogMessage.EVENT,
            safeSummary = "tunnel connection established",
        )
        val unavailableEntry = OperationalLogEntry(
            timestamp = "2026-08-29 18:30:02.000",
            level = OperationalLogLevel.INFO,
            kind = OperationalLogKind.IMS,
            displayLabel = "实例 •••• 1234",
            message = OperationalLogMessage.UNAVAILABLE,
            safeSummary = "unclassified event",
        )

        assertEquals("2026-08-29 18:30:00", operationalLogEntrySummary(knownEntry))
        assertEquals("软件引擎 · 实例 •••• 1234", operationalLogEntryContext(knownEntry))
        assertEquals("引擎已启动", operationalLogEntryTitle(knownEntry))
        assertEquals("engine started", operationalLogEntryMessage(knownEntry))

        assertEquals("隧道事件", operationalLogEntryTitle(eventEntry))
        assertEquals("tunnel connection established", operationalLogEntryMessage(eventEntry))

        assertEquals("未分类事件", operationalLogEntryTitle(unavailableEntry))
        assertEquals("unclassified event", operationalLogEntryMessage(unavailableEntry))
        assertFalse(operationalLogEntryTitle(unavailableEntry).contains("事件不可用"))
        assertFalse(operationalLogMessageLabel(OperationalLogMessage.UNAVAILABLE).contains("事件不可用"))
        assertEquals("未分类事件", operationalLogMessageLabel(OperationalLogMessage.UNAVAILABLE))
        assertEquals("运行事件", operationalLogMessageLabel(OperationalLogMessage.EVENT))
    }
}
