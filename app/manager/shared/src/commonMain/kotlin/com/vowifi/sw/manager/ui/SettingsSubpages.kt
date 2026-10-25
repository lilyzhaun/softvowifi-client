package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.ConfigurationProvisionState
import com.vowifi.sw.manager.data.RefreshResult
import kotlinx.coroutines.flow.StateFlow
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.DiagnosticCheckId
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogUiState
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.LogExportCompletion
import com.vowifi.sw.manager.model.LogExportOutcome
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh

internal data class SettingsConfigurationActions(
    val onSubmit: com.vowifi.sw.manager.model.ConfigurationSubmit,
    val onProvision: (SimHandle) -> Unit,
    val onRefresh: suspend () -> RefreshResult,
    val onOpenSim: (com.vowifi.sw.manager.ui.navigation.ConfigurationSimHandle) -> Unit = {},
)

internal data class SettingsLogsActions(
    val selectFile: (String) -> Unit,
    val setKind: (OperationalLogKind?) -> Unit,
    val setLevel: (OperationalLogLevel?) -> Unit,
    val setInstance: (String?) -> Unit,
    val onExport: (List<OperationalLogEntry>) -> Unit,
    val fullDiagnosticExport: Boolean = false,
    val exportWindow: com.vowifi.sw.manager.data.DiagnosticExportWindow = com.vowifi.sw.manager.data.DiagnosticExportWindow.HOUR,
    val setExportWindow: (com.vowifi.sw.manager.data.DiagnosticExportWindow) -> Unit = {},
)

@Composable
internal fun SettingsConfigurationScreen(
    state: ConfigurationUiState,
    simState: SimControlUiState?,
    actions: SettingsConfigurationActions,
    drafts: ConfigurationDraftStore = remember { ConfigurationDraftStore() },
    provisioning: StateFlow<ConfigurationProvisionState>? = null,
    provisionBusy: Boolean = false,
) {
    val presentation = configurationPresentation(state, simState)
    val progress = provisioning?.collectAsState()?.value ?: ConfigurationProvisionState()
    val draftPending = (state as? ConfigurationUiState.Ready)?.instances.orEmpty().any { drafts.draftFor(it).pending }
    val refresh = rememberManagerRefreshController(actions.onRefresh,
        enabled = configurationRefreshEnabled(state) && !draftPending && !provisionBusy && progress.pending.isEmpty())
    val rotation = rememberManagerRefreshRotation(refresh)
    RegisterManagerRefreshAction(refresh, "刷新配置", rotation)
    val loading = rememberManagerLoadingState(state == ConfigurationUiState.Loading, presentation.rows.isNotEmpty())
    val empty = rememberManagerEmptyFade(!loading.hidden && !loading.showLoading && presentation.panelKind == ConfigurationPanelKind.EMPTY)
    AppPageScaffold(title = "SIM 与网络配置", showTopBar = false, contentHorizontalInset = CONFIGURATION_PAGE_INSET,
        refreshController = refresh,
        refreshRotation = rotation, contentAlpha = loading.alpha, emptyAlpha = empty) {
        managerRefreshResultItem(refresh, "configuration-refresh-result")
        configurationItems(
            state = state,
            presentation = presentation,
            drafts = drafts,
            actions = ConfigurationActions(actions.onSubmit, actions.onProvision, { refresh.request() }, actions.onOpenSim),
            refreshEnabled = refresh.enabled,
            refreshRotation = rotation,
            loading = loading,
            provisioning = progress,
        )
    }
}

@Composable
internal fun SettingsDiagnosticsScreen(
    state: EnvironmentDiagnosticsUiState,
    onRefresh: (suspend () -> RefreshResult)? = null,
    onOpenConfiguration: (() -> Unit)? = null,
    onHome: (() -> Unit)? = null,
) {
    val refresh = rememberManagerRefreshController(onRefresh)
    val rotation = rememberManagerRefreshRotation(refresh)
    if (onRefresh != null) RegisterManagerRefreshAction(refresh, "刷新诊断", rotation)
    val currentState = rememberUpdatedState(state)
    val currentActive = rememberUpdatedState(LocalAppRouteActive.current)
    fun recover(id: DiagnosticCheckId) {
        if (!currentActive.value) return
        val check = (currentState.value as? EnvironmentDiagnosticsUiState.Ready)?.checks?.firstOrNull { it.id == id }
        when (check?.let(::diagnosticRecoveryRoute)) {
            AppRoute.SettingsConfiguration -> onOpenConfiguration?.invoke()
            AppRoute.Home -> onHome?.invoke()
            else -> Unit
        }
    }
    val loading = rememberManagerLoadingState(state == EnvironmentDiagnosticsUiState.Loading,
        state is EnvironmentDiagnosticsUiState.Ready)
    val inlineRefresh = LocalSubpageTopBarAction.current == null
    AppPageScaffold(title = "诊断", showTopBar = false, refreshController = refresh,
        refreshRotation = rotation, contentAlpha = loading.alpha) {
        managerRefreshResultItem(refresh, "diagnostics-refresh-result")
        if (!loading.hidden) diagnosticItems(if (loading.showLoading) EnvironmentDiagnosticsUiState.Loading else state,
            onRetry = if (refresh.enabled) ({ refresh.request() }) else null,
            onOpenConfiguration = onOpenConfiguration?.let { { recover(DiagnosticCheckId.CONFIG) } },
            onHome = onHome?.let { { recover(DiagnosticCheckId.ENGINE) } })
        if (inlineRefresh && onRefresh != null) {
            item(key = "diagnostics-refresh-action") {
                ManagerIconAction(MiuixIcons.Refresh, "刷新诊断", ManagerAction.Refresh, { refresh.request() },
                    enabled = refresh.enabled, iconRotation = rotation)
            }
        }
    }
}

@Composable
internal fun SettingsLogsScreen(
    state: OperationalLogUiState,
    actions: SettingsLogsActions,
    exportBusy: Boolean,
    onRefresh: (suspend () -> RefreshResult)? = null,
    exportCompletion: LogExportCompletion? = null,
) {
    var showTechnical by remember { mutableStateOf(false) }
    var exportFailed by remember { mutableStateOf(exportCompletion?.outcome == LogExportOutcome.Failed) }
    LaunchedEffect(exportBusy, exportCompletion) {
        exportFailed = !exportBusy && exportCompletion?.outcome == LogExportOutcome.Failed
    }
    val selected = when (state) {
        is OperationalLogUiState.Ready -> state.selectedFileId
        is OperationalLogUiState.Warning -> state.selectedFileId
        else -> ""
    }
    val selectedFiles = when (state) {
        is OperationalLogUiState.Ready -> state.files
        is OperationalLogUiState.Warning -> state.files
        else -> emptyList()
    }
    val selectionScope = selectedFiles.firstOrNull { it.id == selected }?.groupKey ?: selected
    val refresh = rememberManagerRefreshController(onRefresh, presentationScope = selectionScope)
    // File selection resets only the list, not the current user's original 400ms window.
    val indicator = rememberManagerRefreshIndicator(refresh)
    val rotation = rememberManagerRefreshRotation(refresh)
    if (onRefresh != null) RegisterManagerRefreshAction(refresh, "刷新运行日志", rotation)
    val loading = rememberManagerLoadingState(state == OperationalLogUiState.Loading,
        state is OperationalLogUiState.Ready || state is OperationalLogUiState.Warning)
    val empty = rememberManagerEmptyFade(!loading.hidden && !loading.showLoading && state == OperationalLogUiState.Empty)
    val inlineRefresh = LocalSubpageTopBarAction.current == null
    val filter = when (state) {
        is OperationalLogUiState.Ready -> state.filter
        is OperationalLogUiState.Warning -> state.filter
        else -> null
    }
    key(selectionScope) {
        AppPageScaffold(title = "运行日志", showTopBar = false, scrollResetCondition = filter to showTechnical, refreshController = refresh,
            refreshRotation = rotation, refreshIndicator = indicator, contentAlpha = loading.alpha, emptyAlpha = empty) {
            managerRefreshResultItem(refresh, "logs-refresh-result")
            if (exportFailed) item(key = "logs-export-failure") {
                top.yukonga.miuix.kmp.basic.BasicComponent(title = "无法写入导出文件", summary = "请重新选择目标后导出。")
            }
            if (!loading.hidden) operationalLogItems(state = if (loading.showLoading) OperationalLogUiState.Loading else state,
                actions = actions, exportBusy = exportBusy, onRetry = if (onRefresh != null) ({ refresh.request() }) else null,
                retryEnabled = refresh.enabled, showTechnical = showTechnical, onShowTechnical = { showTechnical = it })
            if (inlineRefresh && onRefresh != null) {
                item(key = "logs-refresh-action") {
                    ManagerIconAction(MiuixIcons.Refresh, "刷新运行日志", ManagerAction.Refresh, { refresh.request() },
                        enabled = refresh.enabled, iconRotation = rotation)
                }
            }
        }
    }
}
