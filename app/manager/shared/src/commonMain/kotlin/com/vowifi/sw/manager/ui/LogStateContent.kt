package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogFilter
import com.vowifi.sw.manager.model.OperationalLogUiState
import top.yukonga.miuix.kmp.basic.Text

internal fun LazyListScope.operationalLogItems(
    state: OperationalLogUiState,
    actions: SettingsLogsActions,
    exportBusy: Boolean,
) {
    when (state) {
        OperationalLogUiState.Loading,
        OperationalLogUiState.Empty,
        is OperationalLogUiState.Failure,
        is OperationalLogUiState.PermissionDenied,
        is OperationalLogUiState.Unavailable,
        -> item(key = "operational-logs-state") { StatePanel(operationalLogStateSpec(state)) }

        is OperationalLogUiState.Ready -> operationalLogDataItems(
            state = state,
            actions = actions,
            exportBusy = exportBusy,
        )
        is OperationalLogUiState.Warning -> operationalLogDataItems(
            state = state,
            actions = actions,
            exportBusy = exportBusy,
        )
    }
}

private fun LazyListScope.operationalLogDataItems(
    state: OperationalLogUiState,
    actions: SettingsLogsActions,
    exportBusy: Boolean,
) {
    val files = when (state) {
        is OperationalLogUiState.Ready -> state.files
        is OperationalLogUiState.Warning -> state.files
        else -> emptyList()
    }
    val selectedFileId = when (state) {
        is OperationalLogUiState.Ready -> state.selectedFileId
        is OperationalLogUiState.Warning -> state.selectedFileId
        else -> ""
    }
    val filter = when (state) {
        is OperationalLogUiState.Ready -> state.filter
        is OperationalLogUiState.Warning -> state.filter
        else -> OperationalLogFilter()
    }
    val filteredEntries = when (state) {
        is OperationalLogUiState.Ready -> state.filteredEntries
        is OperationalLogUiState.Warning -> state.filteredEntries
        else -> emptyList()
    }
    val loadInfo = when (state) {
        is OperationalLogUiState.Ready -> state.loadInfo
        is OperationalLogUiState.Warning -> state.loadInfo
        else -> com.vowifi.sw.manager.model.OperationalLogLoadInfo()
    }
    val sourceEntries = when (state) {
        is OperationalLogUiState.Ready -> state.entries
        is OperationalLogUiState.Warning -> state.entries
        else -> emptyList()
    }
    val matching = filteredEntries.toSet()
    val displayed = sourceEntries.withIndex().filter { it.value in matching }.asReversed()
    val fileSelection = LogFileSelection(actions.setInstance, actions.selectFile)
    val gap = logsChromeContract().cardGapDp.dp
    val sections = operationalLogDataSectionOrder(state is OperationalLogUiState.Warning)
    sections.forEach { section ->
        when (section) {
            OperationalLogDataSection.WARNING -> {
                if (state is OperationalLogUiState.Warning) {
                    item(key = "operational-logs-warning") {
                        StatePanel(
                            spec = operationalLogWarningSpec(state),
                            modifier = Modifier.padding(bottom = gap),
                        )
                    }
                }
            }
            OperationalLogDataSection.FILTERS -> {
                item(key = "operational-logs-filters") {
                    LogFilterRow(
                        presentation = LogFilterPresentation(files, selectedFileId, filter),
                        actions = LogFilterActions(
                            onSelectFile = fileSelection::select,
                            onKindSelected = actions.setKind,
                            onLevelSelected = actions.setLevel,
                        ),
                        modifier = Modifier.padding(bottom = gap),
                    )
                }
            }
            OperationalLogDataSection.EVENTS -> {
                item(key = "operational-log-read-status") {
                    val labels = listOfNotNull(
                        if (loadInfo.legacy) "旧模块兼容模式：文件序号可能随目录变化，未启用缓存" else null,
                        if (loadInfo.cached) "缓存快照 · 刷新可重新读取" else null,
                        if (loadInfo.reading) "正在读取所选日志" else null,
                        loadInfo.issue,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        labels.forEach { label -> Text(label, modifier = Modifier.padding(bottom = gap),
                            color = LocalSoftVoWiFiColors.current.textSecondary, fontSize = 14.sp, lineHeight = 20.sp) }
                    }
                }
                if (filteredEntries.isEmpty() && !loadInfo.reading && loadInfo.issue == null) {
                    item(key = "operational-logs-filtered-empty") {
                        StatePanel(
                            spec = operationalLogFilteredEmptySpec(),
                            modifier = Modifier.padding(bottom = gap),
                        )
                    }
                } else {
                    items(displayed.size, key = { index -> "operational-log-entry-${displayed[index].index}" }) { index ->
                        LogEntryRow(
                            entry = displayed[index].value,
                            modifier = Modifier.padding(bottom = gap),
                        )
                    }
                }
            }
            OperationalLogDataSection.EXPORT -> {
                item(key = "operational-logs-export") {
                    val colors = LocalSoftVoWiFiColors.current
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LogExportButton(
                            presentation = logExportPresentation(filteredEntries.isNotEmpty(), exportBusy),
                            onClick = { actions.onExport(filteredEntries) },
                        )
                        Text(
                            text = logPrivacyNote(),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

internal fun operationalLogManagerStatus(state: OperationalLogUiState): ManagerStatus = when (state) {
    OperationalLogUiState.Loading -> managerStatus(
        kind = ManagerStatusKind.Loading,
        title = "正在读取运行日志",
        summary = "完成后将显示最近的运行事件。",
    )
    OperationalLogUiState.Empty -> managerStatus(
        kind = ManagerStatusKind.Empty,
        title = "暂无运行日志",
        summary = "当前没有可显示的事件。",
    )
    is OperationalLogUiState.Failure -> managerStatus(
        kind = ManagerStatusKind.Failure,
        title = "日志读取失败",
        summary = "无法读取运行日志，请稍后刷新。",
    )
    is OperationalLogUiState.PermissionDenied -> managerStatus(
        kind = ManagerStatusKind.PermissionDenied,
        title = "日志权限不足",
        summary = "当前权限无法读取运行日志。",
    )
    is OperationalLogUiState.Unavailable -> managerStatus(
        kind = ManagerStatusKind.Unavailable,
        title = "日志不可用",
        summary = "当前环境不提供运行日志。",
    )
    is OperationalLogUiState.Ready,
    is OperationalLogUiState.Warning,
    -> error("Data states use filters and entries")
}

internal fun operationalLogWarningStatus(state: OperationalLogUiState.Warning): ManagerStatus {
    val readFailed = state.loadInfo.issue != null
    val parseIssues = state.issues.isNotEmpty()
    val windowOnly = state.truncated && !parseIssues && !readFailed
    return managerStatus(
        kind = when {
            readFailed -> ManagerStatusKind.Failure
            windowOnly -> ManagerStatusKind.Unavailable
            else -> ManagerStatusKind.Warning
        },
        title = when {
            readFailed -> "日志读取失败"
            parseIssues -> "部分日志无法解析"
            windowOnly -> "最近日志窗口"
            else -> "日志可能不完整"
        },
        summary = buildString {
            append("已显示可用的运行事件。")
            if (state.truncated) append("最多显示最近50条运行记录；更早记录未在此窗口显示。")
            if (parseIssues) append("有${state.issues.size}处日志格式无法解析。")
            if (readFailed) append("本次日志读取未完成，请刷新重试。")
        },
    )
}

internal fun operationalLogFilteredEmptyStatus(): ManagerStatus = managerStatus(
    kind = ManagerStatusKind.Empty,
    title = "暂无运行日志",
    summary = "当前筛选条件下没有可显示的事件。",
)

internal fun operationalLogStateSpec(state: OperationalLogUiState): StatePanelSpec =
    operationalLogManagerStatus(state).toStatePanelSpec()

internal fun operationalLogWarningSpec(state: OperationalLogUiState.Warning): StatePanelSpec =
    operationalLogWarningStatus(state).toStatePanelSpec()

internal fun operationalLogFilteredEmptySpec(): StatePanelSpec =
    operationalLogFilteredEmptyStatus().toStatePanelSpec()
