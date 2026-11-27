package com.vowifi.sw.manager.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.ConfigurationCallMedia
import com.vowifi.sw.manager.model.ConfigurationCallSignaling
import com.vowifi.sw.manager.model.ConfigurationEpdgSelection
import com.vowifi.sw.manager.model.ConfigurationImsProtection
import com.vowifi.sw.manager.model.ConfigurationImsTiming
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationSwuAccess
import com.vowifi.sw.manager.model.ConfigurationTunnelIdentity
import com.vowifi.sw.manager.model.ConfigurationTunnelSecurity
import com.vowifi.sw.manager.model.ConfigurationTunnelTiming
import com.vowifi.sw.manager.ui.navigation.ConfigurationSection
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun sectionTitle(section: ConfigurationSection): String = when (section) {
    ConfigurationSection.CONNECTION -> "连接与接入"
    ConfigurationSection.VOICE -> "通话与语音"
    ConfigurationSection.PROTOCOL -> "协议与安全"
    ConfigurationSection.STATUS -> "生效状态与协商快照"
}

internal data class ConfigurationSectionEntryState(
    val section: ConfigurationSection,
    val title: String,
    val summary: String,
    val overrideCount: Int,
    val unsaved: Boolean,
    val hasError: Boolean,
)

private val CONNECTION_FIELD_KEYS = setOf(
    ConfigurationFieldKey.Smsc,
    ConfigurationFieldKey.Epdg,
    ConfigurationFieldKey.Realm,
    ConfigurationFieldKey.Dns,
)

private val PROTOCOL_FIELD_KEYS = setOf(
    ConfigurationFieldKey.SipSecurity,
    ConfigurationFieldKey.PaniInitial,
    ConfigurationFieldKey.PaniCountry,
    ConfigurationFieldKey.DigestRealm,
)

internal fun configurationConnectionOverrideCount(values: ConfigurationDraftValues): Int {
    var count = 0
    if (values.smsc.isNotEmpty()) count++
    if (values.epdgFqdn.isNotEmpty()) count++
    if (values.imsRealm.isNotEmpty()) count++
    if (values.dnsOverrides.isNotEmpty()) count++
    if (!values.epdgAddressFamily.isNullOrEmpty()) count++
    if (values.epdgSelection != null && values.epdgSelection != ConfigurationEpdgSelection()) count++
    if (values.swuAccess != null && values.swuAccess != ConfigurationSwuAccess()) count++
    return count
}

internal fun configurationVoiceOverrideCount(values: ConfigurationDraftValues): Int {
    var count = 0
    if (values.callSignaling != null && values.callSignaling != ConfigurationCallSignaling()) count++
    if (values.callMedia != null && values.callMedia != ConfigurationCallMedia()) count++
    return count
}

internal fun configurationProtocolOverrideCount(values: ConfigurationDraftValues): Int {
    var count = 0
    if (values.imsSipSecurity.isNotEmpty()) count++
    if (values.paniInitial.isNotEmpty()) count++
    if (values.paniCountry.isNotEmpty()) count++
    if (values.imsDigestRealm.isNotEmpty()) count++
    if (values.imsProtection != null && values.imsProtection != ConfigurationImsProtection()) count++
    if (values.imsTiming != null && values.imsTiming != ConfigurationImsTiming()) count++
    if (values.tunnelSecurity != null && values.tunnelSecurity != ConfigurationTunnelSecurity()) count++
    if (values.tunnelTiming != null && values.tunnelTiming != ConfigurationTunnelTiming()) count++
    if (values.tunnelIdentity != null && values.tunnelIdentity != ConfigurationTunnelIdentity()) count++
    return count
}

internal fun configurationTotalAdvancedOverrides(values: ConfigurationDraftValues): Int =
    configurationConnectionOverrideCount(values) +
        configurationVoiceOverrideCount(values) +
        configurationProtocolOverrideCount(values)

internal fun configurationSectionState(
    instance: ConfigurationInstance,
    draft: ConfigurationEditorDraft,
    section: ConfigurationSection,
): ConfigurationSectionEntryState {
    val values = draft.snapshotValues()
    val modifiedFields = draft.modifiedFields()
    val fieldErrors = draft.errors.keys
    return when (section) {
        ConfigurationSection.CONNECTION -> {
            val count = configurationConnectionOverrideCount(values)
            val unsaved = modifiedFields.any { it in CONNECTION_FIELD_KEYS } ||
                draft.addressFamilyModified || draft.selectionModified || draft.swuAccessModified
            val hasError = fieldErrors.any { it in CONNECTION_FIELD_KEYS } ||
                draft.addressFamilyError != null || draft.selectionError != null || draft.swuAccessError != null
            ConfigurationSectionEntryState(
                section = section,
                title = sectionTitle(section),
                summary = sectionSummaryText(
                    count = count,
                    unsaved = unsaved,
                    hasError = hasError,
                    defaultCopy = "自动 · ePDG、IMS 域、短信中心、DNS、接入点",
                ),
                overrideCount = count,
                unsaved = unsaved,
                hasError = hasError,
            )
        }
        ConfigurationSection.VOICE -> {
            val count = configurationVoiceOverrideCount(values)
            val unsaved = draft.signalingModified || draft.mediaModified
            val hasError = draft.signalingError != null || draft.mediaError != null
            ConfigurationSectionEntryState(
                section = section,
                title = sectionTitle(section),
                summary = sectionSummaryText(
                    count = count,
                    unsaved = unsaved,
                    hasError = hasError,
                    defaultCopy = "自动 · 呼叫信令、语音编码、音频加密",
                ),
                overrideCount = count,
                unsaved = unsaved,
                hasError = hasError,
            )
        }
        ConfigurationSection.PROTOCOL -> {
            val count = configurationProtocolOverrideCount(values)
            val unsaved = modifiedFields.any { it in PROTOCOL_FIELD_KEYS } ||
                draft.protectionModified || draft.imsTimingModified ||
                draft.tunnelModified || draft.timingModified || draft.identityModified
            val hasError = fieldErrors.any { it in PROTOCOL_FIELD_KEYS } ||
                draft.protectionError != null || draft.imsTimingError != null ||
                draft.tunnelError != null || draft.timingError != null || draft.identityError != null
            ConfigurationSectionEntryState(
                section = section,
                title = sectionTitle(section),
                summary = sectionSummaryText(
                    count = count,
                    unsaved = unsaved,
                    hasError = hasError,
                    defaultCopy = "默认 · 仅排查注册或兼容性问题时修改",
                ),
                overrideCount = count,
                unsaved = unsaved,
                hasError = hasError,
            )
        }
        ConfigurationSection.STATUS -> {
            val summary = if (instance.lifecycle == ConfigurationLifecycle.RUNNING) {
                "运行中 · 查看参数来源、解析快照与实际协商算法"
            } else {
                "只读 · 查看参数来源、解析快照与协商详情"
            }
            ConfigurationSectionEntryState(
                section = section,
                title = sectionTitle(section),
                summary = summary,
                overrideCount = 0,
                unsaved = false,
                hasError = false,
            )
        }
    }
}

private fun sectionSummaryText(
    count: Int,
    unsaved: Boolean,
    hasError: Boolean,
    defaultCopy: String,
): String = when {
    hasError -> "含无效设置，请进入检查"
    count > 0 && unsaved -> "已自定义 $count 项 · $CONFIGURATION_UNSAVED_MARK"
    count > 0 -> "已自定义 $count 项"
    unsaved -> "已恢复自动 · $CONFIGURATION_UNSAVED_MARK"
    else -> defaultCopy
}

internal fun configurationModeSummary(advancedCount: Int, advancedUnsaved: Boolean): String = when {
    advancedCount == 0 && advancedUnsaved -> "已恢复全部自动 · $CONFIGURATION_UNSAVED_MARK"
    advancedCount == 0 -> "全部自动 · 仅需按需调整底层网络"
    advancedUnsaved -> "已自定义 $advancedCount 项高级参数 · $CONFIGURATION_UNSAVED_MARK"
    else -> "已自定义 $advancedCount 项高级参数"
}

@Composable
internal fun ConfigurationSectionEntryRow(
    state: ConfigurationSectionEntryState,
    enabled: Boolean,
    onOpen: (ConfigurationSection) -> Unit,
) {
    val actionable = enabled && LocalAppRouteActive.current
    val handler = rememberManagerActionHandler(
        if (actionable) managerActionFeedback(ManagerAction.Navigate) else ManagerActionFeedback.None,
    ) { onOpen(state.section) }
    val indication = rememberManagerPressIndication(ManagerPressStyle.CardRow, actionable)
    CompositionLocalProvider(LocalIndication provides indication) {
        ArrowPreference(
            title = state.title,
            summary = state.summary,
            summaryColor = configurationSummaryColor(modified = state.unsaved, error = state.hasError),
            onClick = handler,
            enabled = actionable,
            modifier = Modifier
                .defaultMinSize(minHeight = ManagerPreferenceLayoutContract.minTouchTarget)
                .then(if (actionable) Modifier else Modifier.semantics(mergeDescendants = true) { disabled() })
                .managerPressInputGate(actionable),
        )
    }
}

@Composable
internal fun ConfigurationExpertNoticeCard() {
    val colors = LocalSoftVoWiFiColors.current
    val shape = RoundedCornerShape(CardDefaults.CornerRadius)
    Surface(
        color = colors.warningSurface,
        shape = shape,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "专家参数 · 谨慎修改",
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                color = colors.warning,
            )
            Text(
                text = "随意修改 IMS 或隧道的加密、计时与身份参数可能导致无法注册或断连。不确定时请保持“自动 / 继承”；所有修改仅在点击保存后生效。",
                modifier = Modifier.padding(top = 4.dp),
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                lineHeight = MiuixTheme.textStyles.footnote1.lineHeight,
                color = colors.textSecondary,
                softWrap = true,
            )
        }
    }
}

@Composable
internal fun ConfigurationResetAdvancedCard(
    overrideCount: Int,
    enabled: Boolean,
    draft: ConfigurationEditorDraft,
) {
    var confirming by remember(draft, enabled) { mutableStateOf(false) }
    val routeActive = LocalAppRouteActive.current
    val actionable = enabled && routeActive && !draft.pending && overrideCount > 0
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        insideMargin = PaddingValues(vertical = ManagerPreferenceLayoutContract.cardInsideVerticalPadding),
    ) {
        Column {
            BasicComponent(
                title = "恢复全部高级参数为自动",
                summary = "当前有 $overrideCount 项高级参数覆盖；将保留底层网络选择，清空后需点击保存生效。",
            )
            ManagerTextAction(
                label = "恢复为自动",
                action = ManagerAction.ContextAction,
                onClick = { if (actionable) confirming = true },
                enabled = actionable,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    OverlayDialog(
        show = confirming && routeActive && enabled,
        title = "恢复全部高级参数为自动？",
        summary = "将保留当前的底层网络选择，清空所有连接、通话与协议手动覆盖。操作仅写入草稿，点击底部保存后才会生效。",
        onDismissRequest = { confirming = false },
    ) {
        ConfigurationActionPair(
            listOf("取消"),
            listOf("恢复为自动"),
            secondary = { m ->
                ManagerTextAction("取消", ManagerAction.Rejected, { confirming = false }, modifier = m)
            },
            primary = { m ->
                ManagerTextAction(
                    "恢复为自动",
                    ManagerAction.ContextAction,
                    {
                        confirming = false
                        if (actionable) draft.resetAdvancedToAutomatic()
                    },
                    modifier = m,
                    enabled = actionable,
                    primary = true,
                )
            },
        )
    }
}

internal fun LazyListScope.configurationConnectionSectionItems(
    model: ConfigurationEditorModel,
    draft: ConfigurationEditorDraft,
    enabled: Boolean,
    onEpdgEdit: (Int, EpdgCandidateField) -> Unit,
    onSwuAccessEdit: () -> Unit,
    onEdit: (ConfigurationFieldKey) -> Unit,
) {
    val instance = model.instance
    item(key = "configuration-sim-connection-title") { ConfigurationSectionTitle("连接参数") }
    item(key = "configuration-sim-connection") {
        ConfigurationSectionCard(bottomGap = 0.dp) {
            for (key in configurationConnectionFields) {
                ConfigurationValueRow(instance, draft, key, enabled, onEdit, model.readyState.networkOutlets)
            }
            ConfigurationAddressFamilyPreference(instance, draft, enabled)
            if (hasIdentityRealmOverride(instance)) {
                BasicComponent(
                    title = "已有 IMS 域值",
                    summary = "当前 Digest 认证域来自身份域，已有 IMS 域值可能覆盖运营商认证域。请核对后在“IMS 域”中清空并保存；不会自动修改旧值。",
                )
            }
        }
    }
    item(key = "configuration-sim-connection-note") {
        ConfigurationFootnote("留空即使用自动解析值，通常无需修改。")
    }
    configurationEpdgSelectionSections(instance, draft, enabled, onEpdgEdit)
    configurationSwuAccessSections(instance, draft, enabled, onSwuAccessEdit)
}

internal fun LazyListScope.configurationVoiceSectionItems(
    model: ConfigurationEditorModel,
    draft: ConfigurationEditorDraft,
    enabled: Boolean,
    onSignalingEdit: (CallSignalingField) -> Unit,
    onMediaEdit: (Int, CallMediaField) -> Unit,
) {
    val instance = model.instance
    item(key = "configuration-sim-voice-note") {
        ConfigurationFootnote("仅在通话无法接通、无声或运营商有特定语音要求时调整；修改仅影响后续呼叫，不会中断当前注册。")
    }
    configurationCallSignalingSections(instance, draft, enabled, onSignalingEdit)
    configurationCallMediaSections(instance, draft, enabled, onMediaEdit)
}

internal fun LazyListScope.configurationProtocolSectionItems(
    model: ConfigurationEditorModel,
    draft: ConfigurationEditorDraft,
    enabled: Boolean,
    onImsTimingEdit: (ImsTimingField) -> Unit,
    onTimingEdit: (TunnelTimingField) -> Unit,
    onIdentityEdit: () -> Unit,
    onEdit: (ConfigurationFieldKey) -> Unit,
) {
    val instance = model.instance
    item(key = "configuration-sim-protocol-warning") { ConfigurationExpertNoticeCard() }
    item(key = "configuration-sim-ims-title") { ConfigurationSectionTitle("IMS 高级参数") }
    item(key = "configuration-sim-ims") {
        ConfigurationSectionCard(bottomGap = 0.dp) {
            ConfigurationImsAdvancedRows(instance, draft, enabled, onEdit)
        }
    }
    item(key = "configuration-sim-ims-note") {
        ConfigurationFootnote("仅在运营商要求时修改；选择不会立即应用，保存前可放弃。")
    }
    configurationImsProtectionSections(instance, draft, enabled)
    configurationImsTimingSections(instance, draft, enabled, onImsTimingEdit)
    configurationTunnelSecuritySections(instance, draft, enabled)
    configurationTunnelTimingSections(instance, draft, enabled, onTimingEdit)
    configurationTunnelIdentitySections(instance, draft, enabled, onIdentityEdit)
}

internal fun LazyListScope.configurationStatusSectionItems(
    instance: ConfigurationInstance,
    entitlementEnabled: Boolean = true,
    readEntitlement: com.vowifi.sw.manager.model.EntitlementCacheReader? = null,
) {
    item(key = "configuration-sim-status-runtime-title") { ConfigurationSectionTitle("运行与匹配") }
    item(key = "configuration-sim-status-runtime") {
        ConfigurationSectionCard {
            BasicComponent(title = "参数来源", summary = configurationSourceSummary(instance))
            BasicComponent(title = "SIM 匹配身份", summary = configurationSIMIdentitySummary(instance.simAttributeReadStatus))
            BasicComponent(title = "配置生效", summary = configurationApplicationSummary(instance))
        }
    }
    if (readEntitlement != null) {
        item(key = "configuration-sim-entitlement") {
            ConfigurationEntitlementRead(instance, entitlementEnabled, readEntitlement)
        }
    }
    item(key = "configuration-sim-facts-title") { ConfigurationSectionTitle("解析与运行") }
    item(key = "configuration-sim-facts") {
        ConfigurationSectionCard {
            if (instance.facts == null && instance.runtimeFacts == null) {
                BasicComponent(title = "参数快照未知", summary = "当前后端未提供参数快照，可刷新核对。")
            } else configurationFactLabels.forEach { (field, label) ->
                BasicComponent(title = "$label · 快照", summary = configurationFactsSummary(instance, field))
            }
        }
    }
    item(key = "configuration-sim-negotiated-title") { ConfigurationSectionTitle("当前协商快照") }
    item(key = "configuration-sim-negotiated") {
        ConfigurationSectionCard {
            if (instance.runtimeFacts?.registrationSeconds == null) {
                BasicComponent(title = "注册结果未知", summary = "未读取到当前连接已确认的注册期限或算法；不从本地配置推断。")
            } else {
                BasicComponent(title = "注册期限", summary = "${instance.runtimeFacts.registrationSeconds} 秒")
                BasicComponent(title = "IMS 完整性算法", summary = instance.runtimeFacts.integrity ?: "未知")
                BasicComponent(title = "IMS 加密算法", summary = instance.runtimeFacts.encryption ?: "未知")
            }
            BasicComponent(title = "隧道所选算法", summary = configurationTunnelFactsSummary(instance))
            BasicComponent(title = "当前呼叫配置", summary = configurationCallFactsSummary(instance))
        }
    }
    item(key = "configuration-sim-facts-note") {
        ConfigurationFootnote("以上为最近一次读取的快照，可用顶栏刷新核对。加载配置或取得注册结果，不代表短信或通话已成功。")
    }
}
