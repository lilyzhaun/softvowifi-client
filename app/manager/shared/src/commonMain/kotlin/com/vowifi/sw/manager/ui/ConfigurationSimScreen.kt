package com.vowifi.sw.manager.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationNetworkOutlets
import com.vowifi.sw.manager.model.ConfigurationProvisionState
import com.vowifi.sw.manager.model.ConfigurationSubmit
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.ui.navigation.ConfigurationSection
import com.vowifi.sw.manager.ui.navigation.ConfigurationSimHandle
import kotlinx.coroutines.flow.StateFlow
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class ConfigurationEditorModel(
    val instance: ConfigurationInstance,
    val readyState: ConfigurationUiState.Ready,
    val enabled: Boolean,
    val status: String?,
    val callWarning: String? = null,
)

/** Per-SIM editor route: resolves the opaque handle against the live rows, never against labels or slots. */
@Composable
internal fun SettingsConfigurationSimScreen(
    handle: ConfigurationSimHandle,
    state: ConfigurationUiState,
    simState: SimControlUiState?,
    actions: SettingsConfigurationActions,
    drafts: ConfigurationDraftStore,
    provisioning: StateFlow<ConfigurationProvisionState>? = null,
    provisionBusy: Boolean = false,
    activeCallSims: Map<SimHandle, Int?> = emptyMap(),
) {
    // The loading panel has its own list. Keep only the scroll anchor of this handle, not its old rows.
    val editorListState = key(handle) { rememberLazyListState() }
    val presentation = configurationPresentation(state, simState)
    val selectedRow = presentation.rows.firstOrNull { it.key == handle.value }
    val row = selectedRow?.takeIf { it.kind == ConfigurationRowKind.CONFIGURED }
    val ready = state as? ConfigurationUiState.Ready
    val instance = row?.instance.takeIf { ready != null }
    val draft = instance?.let(drafts::draftFor)
    val progress = provisioning?.collectAsState()?.value ?: ConfigurationProvisionState()
    val refresh = rememberManagerRefreshController(actions.onRefresh,
        enabled = configurationRefreshEnabled(state) && draft?.pending != true && !provisionBusy && progress.pending.isEmpty())
    val rotation = rememberManagerRefreshRotation(refresh)
    RegisterManagerRefreshAction(refresh, "刷新配置", rotation)
    val title = LocalSubpageTitle.current
    val heading = row?.let { configurationHeader(it.label).title }
    // Keep the last known name while the SIM is briefly missing or the scene exits.
    SideEffect { if (heading != null) title?.value = heading }
    if (row == null || instance == null || ready == null || draft == null) {
        AppPageScaffold(title = "SIM 配置", showTopBar = false, contentHorizontalInset = CONFIGURATION_PAGE_INSET,
            refreshController = refresh) {
            managerRefreshResultItem(refresh, "configuration-sim-refresh-result")
            item(key = "configuration-sim-missing") {
                val panel = if (selectedRow?.kind == ConfigurationRowKind.REBUILD_REQUIRED) {
                    StatePanelSpec(MiuixIcons.Info, StatePanelTone.WARNING, "旧配置需重建",
                        "请返回 SIM 与网络配置页，确认清除旧自定义值后重建。原草稿不会自动提交。")
                } else configurationSimMissingPanel(state)
                StatePanel(panel, onRetry = if (refresh.enabled) ({ refresh.request() }) else null)
            }
        }
        return
    }
    val simHandle = SimHandle(handle.value)
    val callWarning = if (simHandle in activeCallSims) engineStopCallWarning(activeCallSims[simHandle]) else null
    ConfigurationSimEditor(
        model = ConfigurationEditorModel(instance, ready, row.canApply, row.status, callWarning),
        draft = draft,
        onSubmit = actions.onSubmit,
        refresh = refresh,
        readEntitlement = actions.readEntitlement,
        listState = editorListState,
        onOpenSection = actions.onOpenSection?.let { open -> { section -> open(handle, section) } },
    )
}

/** Grouped detail subpage for one SIM's advanced/diagnostic section; shares the SIM's live draft. */
@Composable
internal fun SettingsConfigurationSimSectionScreen(
    handle: ConfigurationSimHandle,
    section: ConfigurationSection,
    state: ConfigurationUiState,
    simState: SimControlUiState?,
    actions: SettingsConfigurationActions,
    drafts: ConfigurationDraftStore,
    provisioning: StateFlow<ConfigurationProvisionState>? = null,
    provisionBusy: Boolean = false,
    activeCallSims: Map<SimHandle, Int?> = emptyMap(),
) {
    val editorListState = key(handle, section) { rememberLazyListState() }
    val presentation = configurationPresentation(state, simState)
    val selectedRow = presentation.rows.firstOrNull { it.key == handle.value }
    val row = selectedRow?.takeIf { it.kind == ConfigurationRowKind.CONFIGURED }
    val ready = state as? ConfigurationUiState.Ready
    val instance = row?.instance.takeIf { ready != null }
    val draft = instance?.let(drafts::draftFor)
    val progress = provisioning?.collectAsState()?.value ?: ConfigurationProvisionState()
    val refresh = rememberManagerRefreshController(
        actions.onRefresh,
        enabled = configurationRefreshEnabled(state) && draft?.pending != true && !provisionBusy && progress.pending.isEmpty(),
    )
    val rotation = rememberManagerRefreshRotation(refresh)
    RegisterManagerRefreshAction(refresh, "刷新配置", rotation)
    val title = LocalSubpageTitle.current
    SideEffect { title?.value = sectionTitle(section) }
    if (row == null || instance == null || ready == null || draft == null) {
        AppPageScaffold(
            title = sectionTitle(section),
            showTopBar = false,
            contentHorizontalInset = CONFIGURATION_PAGE_INSET,
            refreshController = refresh,
        ) {
            managerRefreshResultItem(refresh, "configuration-sim-section-refresh-result")
            item(key = "configuration-sim-section-missing") {
                StatePanel(configurationSimMissingPanel(state), onRetry = if (refresh.enabled) ({ refresh.request() }) else null)
            }
        }
        return
    }
    val simHandle = SimHandle(handle.value)
    val callWarning = if (simHandle in activeCallSims) engineStopCallWarning(activeCallSims[simHandle]) else null
    ConfigurationSimEditor(
        model = ConfigurationEditorModel(instance, ready, row.canApply, row.status, callWarning),
        draft = draft,
        onSubmit = actions.onSubmit,
        refresh = refresh,
        readEntitlement = actions.readEntitlement,
        listState = editorListState,
        section = section,
        guardLeave = false,
    )
}

internal fun configurationSimMissingPanel(state: ConfigurationUiState): StatePanelSpec =
    configurationPresentation(state).statePanel?.takeIf { state !is ConfigurationUiState.Ready && state !is ConfigurationUiState.Empty }
        ?: StatePanelSpec(MiuixIcons.Info, StatePanelTone.NEUTRAL, "此 SIM 当前不可用",
            "可能已移除或配置已变化；请返回 SIM 与网络配置重新选择。", retryDescription = "重试读取配置")

/** Editor body shared by the route and host tests: concise overview or one detail section, one pinned save bar. */
@Composable
internal fun ConfigurationSimEditor(
    model: ConfigurationEditorModel,
    draft: ConfigurationEditorDraft,
    onSubmit: ConfigurationSubmit,
    refresh: ManagerRefreshController? = null,
    readEntitlement: com.vowifi.sw.manager.model.EntitlementCacheReader? = null,
    listState: LazyListState = rememberLazyListState(),
    section: ConfigurationSection? = null,
    onOpenSection: ((ConfigurationSection) -> Unit)? = null,
    guardLeave: Boolean = section == null,
) {
    val instance = model.instance
    // Live transport facts do not change edit authority or the captured draft.
    val editAuthority = instance.configurationEditAuthority()
    LaunchedEffect(editAuthority) { draft.syncFrom(instance) }
    val routeActive = LocalAppRouteActive.current
    val enabled = model.enabled && routeActive && !draft.pending
    var localSection by remember(draft, section) { mutableStateOf<ConfigurationSection?>(null) }
    val shownSection = section ?: localSection
    val openSection: (ConfigurationSection) -> Unit = onOpenSection ?: { target -> localSection = target }
    // An unconfirmed dialog is local to the active editor; preserve the draft, not a suspended dialog.
    var editing by remember(draft, enabled) { mutableStateOf<ConfigurationFieldEdit?>(null) }
    var signalingEdit by remember(draft) { mutableStateOf<CallSignalingTextEdit?>(null) }
    var mediaEdit by remember(draft) { mutableStateOf<CallMediaTextEdit?>(null) }
    var timingEdit by remember(draft) { mutableStateOf<TunnelTimingTextEdit?>(null) }
    var imsTimingEdit by remember(draft) { mutableStateOf<ImsTimingTextEdit?>(null) }
    var epdgEdit by remember(draft) { mutableStateOf<EpdgCandidateTextEdit?>(null) }
    var identityEdit by remember(draft) { mutableStateOf<Long?>(null) }
    var swuAccessEdit by remember(draft) { mutableStateOf<Long?>(null) }
    var openCount by remember(draft) { mutableLongStateOf(0L) }
    LaunchedEffect(editAuthority, enabled) { signalingEdit = null; mediaEdit = null; timingEdit = null; imsTimingEdit = null; epdgEdit = null; identityEdit = null; swuAccessEdit = null }
    AppPageScaffold(title = shownSection?.let(::sectionTitle) ?: "SIM 配置", showTopBar = false, contentHorizontalInset = CONFIGURATION_PAGE_INSET,
        refreshController = refresh, listState = listState,
        bottomOverlay = { ConfigurationSaveBar(model.copy(enabled = model.enabled && routeActive), draft, onSubmit) }) {
        refresh?.let { managerRefreshResultItem(it, "configuration-sim-refresh-result") }
        when (shownSection) {
            null -> configurationSimOverviewSections(
                model = model,
                draft = draft,
                enabled = enabled,
                onOpenSection = openSection,
            )
            ConfigurationSection.CONNECTION -> configurationConnectionSectionItems(
                model = model,
                draft = draft,
                enabled = enabled,
                onEpdgEdit = { index, field -> epdgEdit = EpdgCandidateTextEdit(index, field, ++openCount) },
                onSwuAccessEdit = { swuAccessEdit = ++openCount },
                onEdit = { key -> editing = ConfigurationFieldEdit(key, ++openCount) },
            )
            ConfigurationSection.VOICE -> configurationVoiceSectionItems(
                model = model,
                draft = draft,
                enabled = enabled,
                onSignalingEdit = { field -> signalingEdit = CallSignalingTextEdit(field, ++openCount) },
                onMediaEdit = { index, field -> mediaEdit = CallMediaTextEdit(index, field, ++openCount) },
            )
            ConfigurationSection.PROTOCOL -> configurationProtocolSectionItems(
                model = model,
                draft = draft,
                enabled = enabled,
                onImsTimingEdit = { field -> imsTimingEdit = ImsTimingTextEdit(field, ++openCount) },
                onTimingEdit = { field -> timingEdit = TunnelTimingTextEdit(field, ++openCount) },
                onIdentityEdit = { identityEdit = ++openCount },
                onEdit = { key -> editing = ConfigurationFieldEdit(key, ++openCount) },
            )
            ConfigurationSection.STATUS -> configurationStatusSectionItems(
                instance = instance,
                entitlementEnabled = !draft.pending && model.readyState.applyingInstanceId == null,
                readEntitlement = readEntitlement,
            )
        }
    }
    ConfigurationFieldDialog(editing.takeIf { enabled }, instance, draft, onDismiss = { editing = null },
        outlets = model.readyState.networkOutlets)
    ConfigurationCallSignalingTextDialog(signalingEdit.takeIf { enabled && instance.callSignaling != null }, draft) { signalingEdit = null }
    ConfigurationCallMediaTextDialog(mediaEdit.takeIf { enabled && instance.callMedia != null }, draft) { mediaEdit = null }
    ConfigurationTunnelTimingTextDialog(timingEdit.takeIf { enabled && instance.tunnelTiming != null && draft.tunnelTiming?.policy != null }, draft) { timingEdit = null }
    ConfigurationImsTimingTextDialog(imsTimingEdit.takeIf { enabled && instance.imsTiming != null && draft.imsTiming?.policy != null }, draft) { imsTimingEdit = null }
    ConfigurationEpdgSelectionTextDialog(epdgEdit.takeIf { enabled && instance.epdgSelection != null && draft.epdgSelection?.candidates != null }, draft) { epdgEdit = null }
    ConfigurationTunnelIdentityDialog(identityEdit.takeIf { enabled && instance.tunnelIdentity != null }, draft) { identityEdit = null }
    ConfigurationSwuAccessDialog(swuAccessEdit.takeIf { enabled && instance.swuAccess != null && draft.swuAccess != null }, draft) { swuAccessEdit = null }
    if (guardLeave && shownSection == null) {
        ConfigurationLeaveGuard(model.copy(enabled = model.enabled && routeActive), draft, onSubmit)
    }
}

private fun LazyListScope.configurationSimOverviewSections(
    model: ConfigurationEditorModel,
    draft: ConfigurationEditorDraft,
    enabled: Boolean,
    onOpenSection: (ConfigurationSection) -> Unit,
) {
    val instance = model.instance
    val advancedCount = configurationTotalAdvancedOverrides(draft.snapshotValues())
    item(key = "configuration-sim-overview") { ConfigurationSimOverviewCard(instance, draft, advancedCount) }
    item(key = "configuration-sim-basic-title") { ConfigurationSectionTitle("常用设置") }
    item(key = "configuration-sim-network") {
        ConfigurationSectionCard(bottomGap = 0.dp) {
            ConfigurationNetworkPreference(draft, enabled, model.readyState.networkOutlets)
        }
    }
    item(key = "configuration-sim-basic-note") {
        ConfigurationFootnote("通常只需选择底层网络；其余参数默认自动适配运营商，无需手动调整。")
    }
    item(key = "configuration-sim-advanced-title") { ConfigurationSectionTitle("高级与诊断") }
    item(key = "configuration-sim-advanced-entries") {
        ConfigurationSectionCard(bottomGap = 0.dp) {
            for (entrySection in ConfigurationSection.entries) {
                ConfigurationSectionEntryRow(
                    state = configurationSectionState(instance, draft, entrySection),
                    enabled = !draft.pending,
                    onOpen = onOpenSection,
                )
            }
        }
    }
    item(key = "configuration-sim-advanced-note") {
        ConfigurationFootnote("高级参数仅在排障或运营商特殊要求时修改；保存前可随时放弃。")
    }
    if (advancedCount > 0) {
        item(key = "configuration-sim-reset-advanced") {
            ConfigurationResetAdvancedCard(overrideCount = advancedCount, enabled = enabled, draft = draft)
        }
    }
}

@Composable
private fun ConfigurationSimOverviewCard(
    instance: ConfigurationInstance,
    draft: ConfigurationEditorDraft,
    advancedCount: Int,
) {
    val header = configurationHeader(instance.displayLabel)
    ConfigurationSectionCard {
        ConfigurationInfoRow("运行状态", configurationLifecycleLabel(instance.lifecycle))
        ConfigurationInfoRow("SIM 标识", header.details ?: "${instance.mcc} / ${instance.mnc}")
        BasicComponent(
            title = "配置模式",
            summary = configurationModeSummary(advancedCount, draft.advancedModified),
            summaryColor = configurationSummaryColor(draft.advancedModified, error = false),
        )
        configurationSystemSubscriptionFact(instance)?.takeIf {
            instance.systemSubscription !is com.vowifi.sw.manager.model.SubscriptionDiscovery.Matched
        }?.let { BasicComponent(title = it.title, summary = it.summary) }
        if (hasIdentityRealmOverride(instance)) {
            BasicComponent(
                title = "已有 IMS 域值",
                summary = "当前 Digest 认证域来自身份域，已有 IMS 域值可能覆盖运营商认证域。可前往“连接与接入”中清空并保存。",
            )
        }
        if (instance.draftOwnerId == null) {
            BasicComponent(title = "草稿保留范围", summary = "身份暂无法稳定确认；重建页面数据源后不会恢复未保存修改")
        }
        if (!draft.dirty && draft.status != ConfigurationEditStatus.Idle) {
            ConfigurationCompletionSummary("最近一次保存", configurationEditSummary(draft), draft.completionSequence)
        }
    }
}

/** Read-only fact with its value on the trailing side, as in miuix settings detail pages. */
@Composable
private fun ConfigurationInfoRow(title: String, value: String) {
    BasicComponent(title = title, endActions = {
        Text(value, color = LocalSoftVoWiFiColors.current.textSecondary,
            fontSize = MiuixTheme.textStyles.body2.fontSize, softWrap = true)
    })
}

@Composable
private fun ConfigurationNetworkPreference(
    draft: ConfigurationEditorDraft,
    enabled: Boolean,
    outlets: ConfigurationNetworkOutlets,
) {
    val choices = configurationNetworkChoices(outlets)
    val summary = listOfNotNull(configurationNetworkSummary(draft.transport, outlets),
        CONFIGURATION_UNSAVED_MARK.takeIf { draft.transportModified }).joinToString("\n")
    // miuix keeps the expanded menu even when its row is disabled.
    key(enabled) {
        OverlayDropdownPreference(
            items = choices.map { it.label },
            selectedIndex = choices.indexOfFirst { it.transport == draft.transport },
            title = "底层网络",
            summary = summary,
            summaryColor = configurationSummaryColor(draft.transportModified, error = false),
            enabled = enabled,
            onSelectedIndexChange = { index -> choices.getOrNull(index)?.let { draft.transport = it.transport } },
        )
    }
}

@Composable
internal fun ConfigurationValueRow(
    instance: ConfigurationInstance,
    draft: ConfigurationEditorDraft,
    key: ConfigurationFieldKey,
    enabled: Boolean,
    onEdit: (ConfigurationFieldKey) -> Unit,
    outlets: ConfigurationNetworkOutlets = ConfigurationNetworkOutlets(),
) {
    val spec = configurationFieldSpec(key)
    val value = draft.textFor(key)?.text?.toString().orEmpty()
    val modified = key in draft.modifiedFields()
    val error = draft.errors[key]
    val actionable = enabled && LocalAppRouteActive.current
    val handler = rememberManagerActionHandler(
        if (actionable) managerActionFeedback(ManagerAction.Navigate) else ManagerActionFeedback.None) { onEdit(key) }
    val indication = rememberManagerPressIndication(ManagerPressStyle.CardRow, actionable)
    CompositionLocalProvider(LocalIndication provides indication) {
        ArrowPreference(
            title = spec.title,
            summary = configurationValueSummary(value, configurationAutomaticValue(instance, key, draft.transport, outlets), modified, error),
            summaryColor = configurationSummaryColor(modified, error != null),
            // Keep the click owner while disabled so the row reports Disabled instead of disappearing as an action.
            onClick = handler,
            enabled = actionable,
            // miuix drops the clickable when disabled, so state Disabled explicitly (e.g. while a save is pending).
            modifier = Modifier.defaultMinSize(minHeight = ManagerPreferenceLayoutContract.minTouchTarget)
                .then(if (actionable) Modifier else Modifier.semantics(mergeDescendants = true) { disabled() })
                .managerPressInputGate(actionable).then(if (error != null) Modifier.configurationErrorSemantics(error) else Modifier),
        )
    }
}

@Composable
internal fun ConfigurationSectionCard(bottomGap: Dp = 12.dp, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = bottomGap),
        insideMargin = PaddingValues(vertical = ManagerPreferenceLayoutContract.cardInsideVerticalPadding),
    ) { Column { content() } }
}

@Composable
internal fun ConfigurationSectionTitle(text: String) {
    SmallTitle(text, insideMargin = PaddingValues(horizontal = CONFIGURATION_ROW_INSET, vertical = 8.dp))
}

@Composable
internal fun ConfigurationFootnote(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth().padding(start = CONFIGURATION_ROW_INSET, end = CONFIGURATION_ROW_INSET, top = 8.dp, bottom = 12.dp),
        fontSize = MiuixTheme.textStyles.footnote1.fontSize, lineHeight = MiuixTheme.textStyles.footnote1.lineHeight,
        color = LocalSoftVoWiFiColors.current.textSecondary)
}

@Composable
internal fun configurationSummaryColor(modified: Boolean, error: Boolean) = LocalSoftVoWiFiColors.current.let { colors ->
    when {
        error -> BasicComponentDefaults.summaryColor(color = colors.error, disabledColor = colors.error)
        modified -> BasicComponentDefaults.summaryColor(color = MiuixTheme.colorScheme.primary)
        else -> BasicComponentDefaults.summaryColor()
    }
}

internal const val CONFIGURATION_UNSAVED_MARK = "未保存"

/** Card edges sit 12dp inside the page; both headings and preference text sit another 16dp in. */
internal val CONFIGURATION_PAGE_INSET = 12.dp
private val CONFIGURATION_ROW_INSET = 16.dp

internal fun Modifier.configurationErrorSemantics(message: String): Modifier = semantics { error(message) }

internal val configurationConnectionFields = listOf(
    ConfigurationFieldKey.Epdg, ConfigurationFieldKey.Realm, ConfigurationFieldKey.Smsc, ConfigurationFieldKey.Dns,
)

internal fun configurationValueSummary(value: String, automatic: String?, modified: Boolean, error: String?): String {
    val shown = when {
        error != null -> error
        value.isNotEmpty() -> value
        automatic.isNullOrBlank() -> "自动"
        else -> "自动：$automatic"
    }
    return if (modified && error == null) "$shown · $CONFIGURATION_UNSAVED_MARK" else shown
}

/** Authoritative automatic value for an empty field; unknown while a saved manual value masks it. */
internal fun configurationAutomaticValue(
    instance: ConfigurationInstance,
    key: ConfigurationFieldKey,
    transport: ConfigurationTransport = instance.transport,
    outlets: ConfigurationNetworkOutlets = ConfigurationNetworkOutlets(),
): String? {
    val policy = instance.imsPolicy
    return when (key) {
        ConfigurationFieldKey.Epdg -> instance.resolvedEpdgFqdn
        ConfigurationFieldKey.Realm -> instance.resolvedImsRealm
        ConfigurationFieldKey.Smsc -> when {
            instance.smsc.isNotEmpty() -> "保存留空并启动引擎后从 SIM 卡读取"
            instance.resolvedSmsc.isNotEmpty() -> "${instance.resolvedSmsc}（SIM 卡）"
            instance.lifecycle == ConfigurationLifecycle.RUNNING -> "尚未获取到 SIM 卡自动值，请刷新配置"
            else -> "尚未获取，启动引擎后从 SIM 卡读取"
        }
        ConfigurationFieldKey.Dns -> outlets.automaticDns[transport.wireValue]
            ?.takeIf { outlets.supported && outlets.issue == null }?.let { "$it（所选网络）" }
            ?: "尚未获取所选网络的 DNS，请刷新配置"
        ConfigurationFieldKey.PaniCountry -> policy.country.takeIf { policy.countrySource != "user" }
            ?.let { "${it.ifEmpty { "旧引擎未报告" }}（${configurationSourceLabel(policy.countrySource)}）" }
        ConfigurationFieldKey.DigestRealm -> policy.digestRealm.takeIf { policy.digestRealmSource != "user" }
            ?.let { "${it.ifEmpty { "旧引擎未报告" }}（${configurationSourceLabel(policy.digestRealmSource)}）" }
        ConfigurationFieldKey.SipSecurity, ConfigurationFieldKey.PaniInitial -> null
    }
}

internal fun configurationSourceLabel(source: String): String = when (source) {
    "carrier" -> "运营商策略"
    "catalog" -> "运营商目录"
    "user" -> "手动设置"
    "default" -> "默认值"
    "home_mcc" -> "归属国家，非当前位置"
    "identity" -> "身份域"
    else -> "来源未知"
}

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
