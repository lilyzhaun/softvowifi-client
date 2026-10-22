package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationSubmit
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.ConfigurationProvisionState
import com.vowifi.sw.manager.data.RefreshResult
import kotlinx.coroutines.flow.StateFlow
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.ui.navigation.ConfigurationSimHandle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh

internal data class ConfigurationActions(
    val submit: ConfigurationSubmit,
    val provision: (SimHandle) -> Unit,
    val refresh: () -> Unit,
    val openSim: (ConfigurationSimHandle) -> Unit = {},
)

@Composable
fun ConfigurationScreen(
    state: ConfigurationUiState,
    onSubmit: ConfigurationSubmit,
    onRefresh: suspend () -> RefreshResult,
    simControlState: SimControlUiState? = null,
    onProvision: (SimHandle) -> Unit = {},
    provisioning: StateFlow<ConfigurationProvisionState>? = null,
    provisionBusy: Boolean = false,
) {
    val drafts = remember { ConfigurationDraftStore() }
    SettingsConfigurationScreen(state, simControlState, SettingsConfigurationActions(onSubmit, onProvision, onRefresh),
        drafts, provisioning, provisionBusy)
}

internal fun LazyListScope.configurationItems(
    state: ConfigurationUiState,
    presentation: ConfigurationPresentation,
    drafts: ConfigurationDraftStore,
    actions: ConfigurationActions,
    refreshEnabled: Boolean = configurationRefreshEnabled(state),
    refreshRotation: State<Float>? = null,
    loading: ManagerLoadingState? = null,
    provisioning: ConfigurationProvisionState = ConfigurationProvisionState(),
) {
    if (loading?.hidden == true) return
    item(key = "draft-session") {
        // Hosts without a subpage top bar keep the same refresh icon inline; the subpage itself shows nothing here.
        if (LocalSubpageTopBarAction.current == null) {
            BasicComponent(title = "SIM 与网络配置", endActions = {
                ManagerIconAction(MiuixIcons.Refresh, "刷新配置", ManagerAction.Refresh, actions.refresh,
                    enabled = refreshEnabled, iconRotation = refreshRotation)
            })
        }
    }
    presentation.disclosure?.let { disclosure ->
        item(key = "fixture-disclosure") { FixtureDisclosureBand(disclosure) }
    }
    val shown = if (loading?.showLoading == true) configurationPresentation(ConfigurationUiState.Loading) else presentation
    shown.statePanel?.let { panel ->
        item(key = "configuration-state") {
            StatePanel(panel.copy(retryDescription = if (!panel.loading && refreshEnabled) "重试读取配置" else null),
                modifier = Modifier.padding(bottom = settingsHubLayoutContract().cardGap),
                onRetry = if (refreshEnabled) actions.refresh else null)
        }
    }
    if (loading?.showLoading == true) return
    if (state !is ConfigurationUiState.Ready && drafts.hasUnconfirmed) {
        item(key = "configuration-unconfirmed") {
            BasicComponent(title = "结果未确认", summary = "修改已保留；请刷新配置后核对，不会自动重试")
        }
    }
    presentation.supplementalStatePanel?.let { panel ->
        item(key = "sim-control-state") {
            StatePanel(panel, modifier = Modifier.padding(bottom = settingsHubLayoutContract().cardGap))
        }
    }
    if (state is ConfigurationUiState.Ready && presentation.rows.isNotEmpty()) {
        state.message?.let { message ->
            item(key = "configuration-message") {
                BasicComponent(title = "配置状态", summary = message)
            }
        }
    }
    items(presentation.rows, key = { "configuration-${it.key}" }) { row ->
        val instance = row.instance
        if (row.kind == ConfigurationRowKind.CONFIGURED && instance != null && state is ConfigurationUiState.Ready) {
            val handle = ConfigurationSimHandle.parse(row.key)
            ConfigurationSimEntry(row, instance, drafts.draftFor(instance),
                onOpen = handle?.let { { actions.openSim(it) } })
        } else {
            val pending = row.handle?.let { it in provisioning.pending } == true
            ConfigurationStatusCard(row, state, actions.provision, pending = pending,
                completion = row.handle?.let { provisioning.completions[it] })
        }
    }
    if (state is ConfigurationUiState.Ready && presentation.rows.any { it.kind == ConfigurationRowKind.CONFIGURED }) {
        item(key = "configuration-registration-note") {
            ConfigurationFootnote("配置就绪不代表注册成功；注册状态请在首页查看。")
        }
    }
}

internal fun configurationRefreshEnabled(state: ConfigurationUiState): Boolean =
    state !is ConfigurationUiState.Ready || state.applyingInstanceId == null

@Composable
private fun FixtureDisclosureBand(text: String) {
    val colors = LocalSoftVoWiFiColors.current
    top.yukonga.miuix.kmp.basic.Surface(color = colors.warningSurface) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(16.dp), softWrap = true)
    }
}
