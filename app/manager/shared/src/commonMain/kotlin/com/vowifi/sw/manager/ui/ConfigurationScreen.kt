package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationSubmit
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text

internal data class ConfigurationActions(
    val submit: ConfigurationSubmit,
    val provision: (SimHandle) -> Unit,
    val refresh: () -> Unit,
)

@Composable
fun ConfigurationScreen(
    state: ConfigurationUiState,
    onSubmit: ConfigurationSubmit,
    onRefresh: () -> Unit,
    simControlState: SimControlUiState? = null,
    onProvision: (SimHandle) -> Unit = {},
) {
    val presentation = configurationPresentation(state, simControlState)
    val drafts = remember { ConfigurationDraftStore() }
    val expansionState = rememberConfigurationExpansionState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val inset = if (maxWidth >= 600.dp) 24.dp else 16.dp
        LazyColumn(
            modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth().align(Alignment.TopCenter),
            contentPadding = PaddingValues(inset),
        ) {
            configurationItems(state, presentation, drafts, expansionState, ConfigurationActions(onSubmit, onProvision, onRefresh))
        }
    }
}

internal fun LazyListScope.configurationItems(
    state: ConfigurationUiState,
    presentation: ConfigurationPresentation,
    drafts: ConfigurationDraftStore,
    expansionState: ConfigurationExpansionState,
    actions: ConfigurationActions,
) {
    item(key = "draft-session") {
        BasicComponent(title = "编辑配置", summary = "未保存修改仅在本次应用会话保留")
        ManagerTextAction("刷新配置", ManagerAction.Refresh, actions.refresh,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            enabled = state !is ConfigurationUiState.Ready || state.applyingInstanceId == null)
    }
    presentation.disclosure?.let { disclosure ->
        item(key = "fixture-disclosure") { FixtureDisclosureBand(disclosure) }
    }
    presentation.statePanel?.let { panel ->
        item(key = "configuration-state") { StatePanel(panel) }
    }
    if (state !is ConfigurationUiState.Ready && drafts.hasUnconfirmed) {
        item(key = "configuration-unconfirmed") {
            BasicComponent(title = "结果未确认", summary = "修改已保留；请刷新配置后核对，不会自动重试")
        }
    }
    presentation.supplementalStatePanel?.let { panel ->
        item(key = "sim-control-state") { StatePanel(panel) }
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
            ConfigurationEditor(
                model = ConfigurationEditorModel(instance, state, row.canApply, row.status),
                draft = drafts.draftFor(instance),
                expanded = expansionState.isExpanded(instance.instanceId),
                onToggleExpand = { expansionState.toggle(instance.instanceId) },
                onSubmit = actions.submit,
            )
        } else {
            ConfigurationStatusCard(row, state, actions.provision)
        }
    }
}

@Composable
private fun FixtureDisclosureBand(text: String) {
    val colors = LocalSoftVoWiFiColors.current
    top.yukonga.miuix.kmp.basic.Surface(color = colors.warningSurface) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(16.dp), softWrap = true)
    }
}
