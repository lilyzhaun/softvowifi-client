package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogFilter
import com.vowifi.sw.manager.model.OperationalLogUiState
import com.vowifi.sw.manager.model.SYSTEM_REGISTRATION_LOG_ID
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Card

internal fun LazyListScope.operationalLogItems(
    state: OperationalLogUiState,
    actions: SettingsLogsActions,
    exportBusy: Boolean,
    onRetry: (() -> Unit)? = null,
    retryEnabled: Boolean = true,
    showTechnical: Boolean = false,
    onShowTechnical: ((Boolean) -> Unit)? = null,
) {
    item(key = "operational-logs-state") {
        val spec = when (state) {
            is OperationalLogUiState.Ready, is OperationalLogUiState.Warning -> null
            else -> operationalLogStateSpec(state)
        }
        RefreshFailurePresentation(spec?.copy(retryDescription =
            if (onRetry != null && !spec.loading) "重试读取日志" else null),
            onRetry = onRetry, retryEnabled = retryEnabled)
    }
    when (state) {
        is OperationalLogUiState.Ready -> operationalLogDataItems(
            state = state,
            actions = actions,
            exportBusy = exportBusy,
            onRetry = onRetry,
            retryEnabled = retryEnabled,
            showTechnical = showTechnical,
            onShowTechnical = onShowTechnical,
        )
        is OperationalLogUiState.Warning -> operationalLogDataItems(
            state = state,
            actions = actions,
            exportBusy = exportBusy,
            onRetry = onRetry,
            retryEnabled = retryEnabled,
            showTechnical = showTechnical,
            onShowTechnical = onShowTechnical,
        )
        else -> Unit
    }
}

private fun LazyListScope.operationalLogDataItems(
    state: OperationalLogUiState,
    actions: SettingsLogsActions,
    exportBusy: Boolean,
    onRetry: (() -> Unit)?,
    retryEnabled: Boolean,
    showTechnical: Boolean,
    onShowTechnical: ((Boolean) -> Unit)?,
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
    val displayed = sourceEntries.withIndex().filter { it.value in matching && (showTechnical || isKeyLogEntry(it.value)) }.asReversed()
    val technicalCount = filteredEntries.count { !isKeyLogEntry(it) }
    val fileSelection = LogFileSelection(actions.setInstance, actions.selectFile)
    val gap = logsChromeContract().cardGapDp.dp
    val warning = operationalLogDataWarningSpec(state)
    val sections = operationalLogDataSectionOrder(warning != null)
    sections.forEach { section ->
        when (section) {
            OperationalLogDataSection.WARNING -> {
                if (warning != null) {
                    item(key = "operational-logs-warning") {
                        LogReadNotice(
                            spec = warning,
                            onRetry = if (loadInfo.issue != null) onRetry else null,
                            retryEnabled = retryEnabled,
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
                        extraActions = { LogExportAction(actions, filteredEntries, exportBusy) },
                    )
                }
            }
            OperationalLogDataSection.EVENTS -> {
                item(key = "operational-log-read-status") {
                     val labels = listOfNotNull(
                          operationalLogWindowNote(state),
                          if (selectedFileId == SYSTEM_REGISTRATION_LOG_ID) "显示最近最多50条系统电话服务记录，与软件引擎的注册记录相互独立。" else null,
                         if (loadInfo.legacy) "正在按后端提供的旧格式文件读取日志。" else null,
                           if (loadInfo.issue == null && loadInfo.cached) "显示上次读取的记录，刷新可更新。" else null,
                        if (loadInfo.reading) "正在读取所选日志" else null,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                          if (sourceEntries.any { it.technical } && onShowTechnical != null) {
                               Text(if (showTechnical) "全部记录 · ${displayed.size} 条" else "关键记录 · ${displayed.size} 条 · 已收起 $technicalCount 条步骤",
                                  color = LocalSoftVoWiFiColors.current.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                              ManagerTextAction(if (showTechnical) "返回关键记录" else "查看全部步骤", ManagerAction.ContextAction,
                                  { onShowTechnical(!showTechnical) }, modifier = Modifier.padding(bottom = gap))
                          }
                         labels.forEach { label -> Text(label, modifier = Modifier.padding(bottom = gap),
                             color = LocalSoftVoWiFiColors.current.textSecondary, fontSize = 14.sp, lineHeight = 20.sp) }
                    }
                }
                if (displayed.isEmpty() && !loadInfo.reading && loadInfo.issue == null) {
                    item(key = "operational-logs-filtered-empty") {
                        StatePanel(
                            spec = if (technicalCount > 0 && !showTechnical) operationalLogKeyEmptySpec() else operationalLogFilteredEmptySpec(),
                            modifier = Modifier.padding(bottom = gap),
                        )
                    }
                } else {
                    item(key = "operational-log-entries") {
                        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                            Column {
                                displayed.forEachIndexed { position, indexed ->
                                    key(indexed.index) {
                                        if (position > 0) LogEntryDivider()
                                        LogEntryRow(indexed.value)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (!actions.fullDiagnosticExport) item(key = "operational-log-privacy") {
        Text(logPrivacyNote(), modifier = Modifier.padding(top = gap, start = 4.dp, end = 4.dp),
            color = LocalSoftVoWiFiColors.current.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
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

internal fun operationalLogFilteredEmptyStatus(): ManagerStatus = managerStatus(
    kind = ManagerStatusKind.Empty,
    title = "暂无运行日志",
    summary = "当前筛选条件下没有可显示的事件。",
)

internal fun operationalLogStateSpec(state: OperationalLogUiState): StatePanelSpec =
    operationalLogManagerStatus(state).toStatePanelSpec()

internal fun operationalLogDataWarningSpec(state: OperationalLogUiState): StatePanelSpec? {
    val loadInfo = when (state) {
        is OperationalLogUiState.Ready -> state.loadInfo
        is OperationalLogUiState.Warning -> state.loadInfo
        else -> return null
    }
    val readFailed = loadInfo.issue != null
    val parseCount = (state as? OperationalLogUiState.Warning)?.issues?.size ?: 0
    if (!readFailed && parseCount == 0 && loadInfo.notice == null) return null
    return managerStatus(
        kind = if (readFailed) ManagerStatusKind.Failure else ManagerStatusKind.Warning,
        title = when {
            readFailed && !loadInfo.fresh -> "日志读取失败"
            readFailed -> "部分日志未读全"
            parseCount > 0 -> "部分日志无法解析"
            else -> "部分过程未记录"
        },
        summary = buildString {
            append("已显示可用的运行事件。")
            if (parseCount > 0) append("有${parseCount}处日志格式无法解析。")
            if (readFailed) {
                append(if (loadInfo.fresh) "当前显示本次读取到的记录，部分来源未读全。" else "当前仍是上次读取的记录。本次日志读取未完成，请刷新重试。")
            }
            loadInfo.notice?.let { append(it) }
        },
    ).toStatePanelSpec()
}

internal fun operationalLogWindowNote(state: OperationalLogUiState): String? =
    if (state is OperationalLogUiState.Warning && state.truncated)
        "最近日志窗口：每个来源最多显示最近50条运行记录；更早记录未在此窗口显示。" else null

internal fun operationalLogFilteredEmptySpec(): StatePanelSpec =
    operationalLogFilteredEmptyStatus().toStatePanelSpec()

internal fun operationalLogKeyEmptySpec(): StatePanelSpec = managerStatus(
    kind = ManagerStatusKind.Empty,
    title = "最近没有关键事件",
    summary = "当前窗口只有技术步骤，可点击“查看全部步骤”了解详细过程。",
).toStatePanelSpec()
