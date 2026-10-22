package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationProvisionCompletion
import com.vowifi.sw.manager.model.ConfigurationProvisionOutcome
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimHandle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.Info

/** Overview row: one SIM, its live state and any unsaved/outcome note; editing happens on the SIM's own page. */
@Composable
internal fun ConfigurationSimEntry(
    row: ConfigurationRowPresentation,
    instance: ConfigurationInstance,
    draft: ConfigurationEditorDraft,
    onOpen: (() -> Unit)?,
) {
    LaunchedEffect(instance) { draft.syncFrom(instance) }
    val header = configurationHeader(row.label)
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        insideMargin = PaddingValues(vertical = ManagerPreferenceLayoutContract.cardInsideVerticalPadding),
    ) {
        ManagerNavigatePreferenceItem(
            managerNavigatePreference(header.title, configurationSimEntrySummary(instance, draft, header.details), MiuixIcons.Phone,
                if (onOpen != null) ManagerPreferenceAvailability.Enabled
                else ManagerPreferenceAvailability.Disabled(configurationSimEntrySummary(instance, draft, header.details))),
            onClick = onOpen,
        )
    }
}

internal fun configurationSimEntrySummary(instance: ConfigurationInstance, draft: ConfigurationEditorDraft, details: String?): String =
    listOfNotNull(
        details,
        "${configurationLifecycleLabel(instance.lifecycle)} · 底层网络 ${transportLabel(instance.transport)}",
        configurationEditSummary(draft).takeIf { draft.dirty || draft.status != ConfigurationEditStatus.Idle },
    ).joinToString("\n")

@Composable
internal fun ConfigurationStatusCard(
    row: ConfigurationRowPresentation,
    state: ConfigurationUiState,
    onProvision: (SimHandle) -> Unit,
    pending: Boolean = false,
    completion: ConfigurationProvisionCompletion? = null,
) {
    val header = configurationHeader(row.label)
    ConfigurationCompletionFeedback(completion?.sequence ?: 0L,
        completion?.outcome == ConfigurationProvisionOutcome.Failed || completion?.outcome == ConfigurationProvisionOutcome.Unconfirmed)
    val provisionMessage = if (pending) null else when (completion?.outcome) {
        ConfigurationProvisionOutcome.Failed -> "创建配置失败，请重试"
        ConfigurationProvisionOutcome.Unconfirmed -> "创建结果未确认，请刷新核对；不会自动重试"
        ConfigurationProvisionOutcome.Cancelled -> "创建已取消，未确认完成"
        ConfigurationProvisionOutcome.Created, ConfigurationProvisionOutcome.Updated,
        ConfigurationProvisionOutcome.Unchanged, null -> null
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        insideMargin = PaddingValues(vertical = 4.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ConfigurationCompletionSummary(
                title = header.title,
                summary = listOfNotNull(header.details, if (pending) "正在创建配置" else row.status ?: "配置暂不可用", provisionMessage).joinToString("\n"),
                sequence = completion?.sequence ?: 0L,
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Info,
                        contentDescription = null,
                        modifier = Modifier.padding(end = ManagerPreferenceLayoutContract.startIconEndPadding).size(20.dp),
                    )
                },
            )
            if (shouldShowProvisionAction(row) || (pending && row.handle != null)) {
                val businessFeedback = configurationProvisionFeedback(state, row)
                ManagerTextAction(
                    label = configurationProvisionLabel(), action = ManagerAction.ProvisionConfiguration,
                    onClick = { row.handle?.let(onProvision) }, enabled = businessFeedback.accepted,
                    busy = pending, busyLabel = "正在创建",
                    labelCandidates = listOf(configurationProvisionLabel(), "正在创建"),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

internal fun configurationSaveLabel(state: ConfigurationUiState, id: ConfigurationInstanceId): String =
    if (state is ConfigurationUiState.Ready && state.applyingInstanceId == id) "正在保存配置" else "保存配置"

internal fun configurationProvisionLabel(): String = "重试创建配置"

internal fun shouldShowProvisionAction(row: ConfigurationRowPresentation): Boolean =
    row.kind == ConfigurationRowKind.ACTIVE_UNCONFIGURED && row.handle != null && row.canProvision

internal fun canProvisionConfiguration(
    state: ConfigurationUiState,
    row: ConfigurationRowPresentation,
): Boolean = shouldShowProvisionAction(row) &&
    (state is ConfigurationUiState.Empty || (state is ConfigurationUiState.Ready && state.applyingInstanceId == null))
