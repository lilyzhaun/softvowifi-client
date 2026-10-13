package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogUiState
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.Refresh

internal data class SettingsConfigurationActions(
    val onSubmit: com.vowifi.sw.manager.model.ConfigurationSubmit,
    val onProvision: (SimHandle) -> Unit,
    val onRefresh: () -> Unit,
)

internal data class SettingsLogsActions(
    val selectFile: (String) -> Unit,
    val setKind: (OperationalLogKind?) -> Unit,
    val setLevel: (OperationalLogLevel?) -> Unit,
    val setInstance: (String?) -> Unit,
    val onExport: (List<OperationalLogEntry>) -> Unit,
)

@Composable
internal fun SettingsConfigurationScreen(
    state: ConfigurationUiState,
    simState: SimControlUiState,
    actions: SettingsConfigurationActions,
    drafts: ConfigurationDraftStore = remember { ConfigurationDraftStore() },
) {
    val presentation = configurationPresentation(state, simState)
    val expansionState = rememberConfigurationExpansionState()
    val topBar = LocalSubpageTopBarAction.current
    val refresh by rememberUpdatedState(actions.onRefresh)
    val refreshEnabled = configurationRefreshEnabled(state)
    DisposableEffect(topBar, refreshEnabled) {
        topBar?.value = SubpageTopBarAction(MiuixIcons.Refresh, "刷新配置", ManagerAction.Refresh, refreshEnabled) { refresh() }
        onDispose { topBar?.value = null }
    }
    AppPageScaffold(title = "SIM 与网络配置", showTopBar = false) {
        configurationItems(
            state = state,
            presentation = presentation,
            drafts = drafts,
            expansionState = expansionState,
            actions = ConfigurationActions(actions.onSubmit, actions.onProvision, actions.onRefresh),
        )
    }
}

@Composable
internal fun SettingsDiagnosticsScreen(state: EnvironmentDiagnosticsUiState) {
    AppPageScaffold(title = "诊断", showTopBar = false) { diagnosticItems(state) }
}

@Composable
internal fun SettingsLogsScreen(
    state: OperationalLogUiState,
    actions: SettingsLogsActions,
    exportBusy: Boolean,
) {
    val selected = when (state) {
        is OperationalLogUiState.Ready -> state.selectedFileId
        is OperationalLogUiState.Warning -> state.selectedFileId
        else -> ""
    }
    key(selected) {
        AppPageScaffold(title = "运行日志", showTopBar = false) {
            operationalLogItems(state = state, actions = actions, exportBusy = exportBusy)
        }
    }
}

internal fun aboutInformationRows(appVersion: String): List<ManagerPreferenceRow.Information> {
    val about = aboutPresentation(appVersion)
    return listOf(
        managerInformationPreference("应用版本", about.versionLabel, MiuixIcons.Info),
        managerInformationPreference("应用信息", about.description, MiuixIcons.Info),
    )
}

@Composable
internal fun SettingsAboutScreen(appVersion: String) {
    val about = aboutPresentation(appVersion)
    val rows = aboutInformationRows(appVersion)
    AppPageScaffold(title = about.title, showTopBar = false) {
        item(key = "about-card") {
            Card(insideMargin = PaddingValues(vertical = 4.dp)) {
                Column {
                    rows.forEach { row ->
                        ManagerInformationPreferenceItem(row)
                    }
                }
            }
        }
    }
}
