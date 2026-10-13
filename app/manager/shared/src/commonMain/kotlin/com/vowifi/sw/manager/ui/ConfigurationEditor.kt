package com.vowifi.sw.manager.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationSubmit
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimHandle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

internal data class ConfigurationEditorModel(
    val instance: ConfigurationInstance,
    val readyState: ConfigurationUiState.Ready,
    val enabled: Boolean,
    val status: String?,
)

@Composable
internal fun ConfigurationEditor(
    model: ConfigurationEditorModel,
    draft: ConfigurationEditorDraft,
    expanded: Boolean = false,
    onToggleExpand: () -> Unit = {},
    onSubmit: ConfigurationSubmit,
) {
    val instance = model.instance
    LaunchedEffect(instance) { draft.syncFrom(instance) }
    val contract = simCardPresentationContract(instance, instance.transport, model.status, expanded)
    val header = configurationHeader(instance.displayLabel)
    val interaction = configurationDisclosureInteractionContract(expanded)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = contract.cardGapDp.dp)
            .animateContentSize(spring()),
        insideMargin = PaddingValues(vertical = 4.dp),
        onClick = if (interaction.cardClickable) onToggleExpand else null,
        showIndication = interaction.cardClickable,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ConfigurationDisclosureHeader(
                title = header.title,
                summary = listOfNotNull(
                    header.details,
                    contract.collapsedSummary,
                    configurationEditSummary(draft).takeIf { draft.dirty || draft.status != ConfigurationEditStatus.Idle },
                ).joinToString("\n"),
                expanded = expanded,
                interaction = interaction,
                onToggleExpand = onToggleExpand,
            )
            if (expanded) {
                ConfigurationAdvancedSettings(instance, draft, model.enabled && !draft.pending)
                if (instance.draftOwnerId == null) {
                    BasicComponent(title = "草稿保留范围", summary = "身份暂无法稳定确认；重建页面数据源后不会恢复未保存修改")
                }
                ConfigurationEditActions(model, draft, onSubmit)
            }
        }
    }
}

@Composable
internal fun ConfigurationStatusCard(
    row: ConfigurationRowPresentation,
    state: ConfigurationUiState,
    onProvision: (SimHandle) -> Unit,
) {
    val header = configurationHeader(row.label)
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        insideMargin = PaddingValues(vertical = 4.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicComponent(
                title = header.title,
                summary = listOfNotNull(header.details, row.status ?: "配置暂不可用").joinToString("\n"),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Info,
                        contentDescription = null,
                        modifier = Modifier.padding(end = ManagerPreferenceLayoutContract.startIconEndPadding).size(20.dp),
                    )
                },
            )
            if (shouldShowProvisionAction(row)) {
                val provisionFeedback = configurationProvisionFeedback(state, row)
                val provisionHandler = rememberManagerActionHandler(provisionFeedback) { row.handle?.let(onProvision) }
                Button(
                    onClick = provisionHandler,
                    enabled = provisionFeedback.accepted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .managerPressScale(provisionFeedback),
                ) {
                    Text(configurationProvisionLabel())
                }
            }
        }
    }
}

@Composable
internal fun ConfigurationFields(draft: ConfigurationEditorDraft, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicComponent(title = "手动覆盖", summary = "留空则使用已解析值。")
        ConfigurationField("SMSC", draft.smsc, enabled, draft.errors[ConfigurationFieldKey.Smsc])
        ConfigurationField("ePDG FQDN", draft.epdgFqdn, enabled, draft.errors[ConfigurationFieldKey.Epdg])
        ConfigurationField("IMS realm", draft.imsRealm, enabled, draft.errors[ConfigurationFieldKey.Realm])
        ConfigurationField("DNS", draft.dns, enabled, draft.errors[ConfigurationFieldKey.Dns])
    }
}

@Composable
private fun ConfigurationField(label: String, state: TextFieldState, enabled: Boolean, error: String?) {
    TextField(
        state = state,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics {
            if (error != null) error(error)
        },
        label = label,
        useLabelAsPlaceholder = false,
        enabled = enabled,
    )
    if (error != null) {
        Text(error, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            color = LocalSoftVoWiFiColors.current.error, softWrap = true)
    }
}

@Composable
internal fun ConfigurationFacts(instance: ConfigurationInstance) {
    BasicComponent(title = "引擎状态", summary = configurationLifecycleLabel(instance.lifecycle))
    BasicComponent(title = "MCC / MNC", summary = "${instance.mcc} / ${instance.mnc}")
    configurationSystemSubscriptionFact(instance)?.let { fact ->
        BasicComponent(title = fact.title, summary = fact.summary)
    }
    BasicComponent(title = "已解析 ePDG", summary = instance.resolvedEpdgFqdn)
    BasicComponent(title = "已解析 IMS realm", summary = instance.resolvedImsRealm)
    BasicComponent(title = "已解析 Digest 认证域", summary = "${instance.imsPolicy.digestRealm.ifEmpty { "旧引擎未报告" }}\n来源：${instance.imsPolicy.digestRealmSource}")
    BasicComponent(title = "已解析 SIP 安全策略", summary = "${instance.imsPolicy.security}\n来源：${instance.imsPolicy.securitySource}")
    BasicComponent(title = "已解析初始 PANI", summary = "${instance.imsPolicy.initial.ifEmpty { "旧引擎未报告" }}\n来源：${instance.imsPolicy.initialSource}")
    BasicComponent(title = "已解析国家参数", summary = "${instance.imsPolicy.country.ifEmpty { "旧引擎未报告" }}\n来源：${instance.imsPolicy.countrySource}（非当前位置）")
}

@Composable
internal fun ConfigurationPolicyFields(draft: ConfigurationEditorDraft, enabled: Boolean) {
    val security = listOf("", "tcp_ipsec_3gpp", "swu_udp_aka")
    val initial = listOf("", "absent", "wlan", "wlan-node", "wlan-country", "wlan-node-country")
    OverlayDropdownPreference(
        title = "IMS SIP 安全策略",
        items = listOf("自动（运营商策略优先）", "TCP + IPsec（显式）", "SWu 内 UDP + AKA"),
        selectedIndex = security.indexOf(draft.imsSipSecurity), enabled = enabled,
        onSelectedIndexChange = { draft.imsSipSecurity = security[it] },
    )
    if (draft.imsSipSecurity == "swu_udp_aka") {
        BasicComponent(title = "安全边界警告", summary = "仅限已认证的 SWu 隧道内部；不建立额外的 UE–P-CSCF IPsec。不是自动回退。短信和语音尚未经 LIVE 实机验证。选择不会立即应用，保存前可放弃修改。")
    }
    OverlayDropdownPreference(
        title = "初始 PANI 覆盖", items = listOf("继承目录 / 默认") + initial.drop(1),
        selectedIndex = initial.indexOf(draft.paniInitial), enabled = enabled,
        onSelectedIndexChange = { draft.paniInitial = initial[it] },
    )
    BasicComponent(title = "用户国家参数", summary = "留空优先继承匹配的运营商策略，未匹配时使用归属国家（Home MCC），不是当前位置。手动输入仅覆盖本字段，与安全策略、初始 PANI 独立。旧引擎可能拒绝新增字段，失败时保留草稿，不视为已应用。")
    ConfigurationField("国家代码（用户参数）", draft.paniCountry, enabled, draft.errors[ConfigurationFieldKey.PaniCountry])
    BasicComponent(title = "Digest 认证域", summary = "留空自动继承。仅用于 challenge / HA1 realm，不改变 IMPI、IMPU 或 REGISTER 地址；IMS realm 仍是身份域。")
    ConfigurationField("Digest认证域（可选）", draft.imsDigestRealm, enabled, draft.errors[ConfigurationFieldKey.DigestRealm])
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

internal fun configurationLifecycleLabel(lifecycle: ConfigurationLifecycle): String = when (lifecycle) {
    ConfigurationLifecycle.CONFIG_MISSING -> "缺少配置"
    ConfigurationLifecycle.STOPPED -> "已停止"
    ConfigurationLifecycle.STARTING -> "启动中"
    ConfigurationLifecycle.RUNNING -> "运行中"
    ConfigurationLifecycle.STOPPING -> "停止中"
}

internal fun configurationApplyConsequence(lifecycle: ConfigurationLifecycle): String = when (lifecycle) {
    ConfigurationLifecycle.STARTING, ConfigurationLifecycle.RUNNING -> "保存后将重启当前引擎实例。"
    ConfigurationLifecycle.CONFIG_MISSING, ConfigurationLifecycle.STOPPED, ConfigurationLifecycle.STOPPING ->
        "下次启动生效。"
}

internal fun transportLabel(value: ConfigurationTransport): String = when (value) {
    ConfigurationTransport.WIFI -> "Wi-Fi"
    ConfigurationTransport.SIM1 -> "SIM 1 数据"
    ConfigurationTransport.SIM2 -> "SIM 2 数据"
    ConfigurationTransport.AUTO -> "自动"
}
