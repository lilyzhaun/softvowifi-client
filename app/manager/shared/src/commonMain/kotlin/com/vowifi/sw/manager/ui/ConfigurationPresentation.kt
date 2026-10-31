package com.vowifi.sw.manager.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationFormatState
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import com.vowifi.sw.manager.model.SubscriptionDiscovery
import com.vowifi.sw.manager.data.homeCarrierLabel
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh

enum class ConfigurationPanelKind { LOADING, EMPTY, FAILURE, PERMISSION_DENIED, UNAVAILABLE }

enum class ConfigurationRowKind { CONFIGURED, REBUILD_REQUIRED, ACTIVE_UNCONFIGURED, UNAVAILABLE }

data class ConfigurationRowPresentation(
    val key: String,
    val label: String,
    val kind: ConfigurationRowKind,
    val instance: ConfigurationInstance? = null,
    val status: String? = null,
    val canApply: Boolean = false,
    val handle: SimHandle? = null,
    val canProvision: Boolean = false,
    val canRebuild: Boolean = false,
)

data class ConfigurationPresentation(
    val showEditor: Boolean,
    val showSaveAction: Boolean,
    val disclosure: String?,
    val panelKind: ConfigurationPanelKind?,
    val statePanel: StatePanelSpec?,
    val rows: List<ConfigurationRowPresentation> = emptyList(),
    val supplementalStatePanel: StatePanelSpec? = null,
)

internal fun configurationPresentation(
    state: ConfigurationUiState,
    simControlState: SimControlUiState? = null,
): ConfigurationPresentation {
    val base = configurationStatePresentation(state)
    if (simControlState == null) return base.copy(rows = legacyConfigurationRows(state))
    if (simControlState !is SimControlUiState.Ready) {
        return base.copy(
            rows = legacyConfigurationRows(state),
            supplementalStatePanel = simInventoryPanel(simControlState),
        )
    }

    val rows = simConfigurationRows(state, simControlState.instances)
    val combinedEmpty = isCombinedEmpty(state, rows)
    val statePanel = when {
        combinedEmpty -> emptyPresentation().statePanel
        state is ConfigurationUiState.Empty || state is ConfigurationUiState.Ready -> null
        else -> base.statePanel
    }
    val panelKind = when {
        combinedEmpty -> ConfigurationPanelKind.EMPTY
        state is ConfigurationUiState.Empty || state is ConfigurationUiState.Ready -> null
        else -> base.panelKind
    }
    return ConfigurationPresentation(
        showEditor = rows.any { it.kind == ConfigurationRowKind.CONFIGURED },
        showSaveAction = rows.any { it.kind == ConfigurationRowKind.CONFIGURED },
        disclosure = base.disclosure,
        panelKind = panelKind,
        statePanel = statePanel,
        rows = rows,
        supplementalStatePanel = simReadyMessagePanel(simControlState.message),
    )
}

private fun configurationStatePresentation(state: ConfigurationUiState): ConfigurationPresentation = when (state) {
    ConfigurationUiState.Loading -> panel(
        ConfigurationPanelKind.LOADING,
        MiuixIcons.Refresh,
        StatePanelTone.INTERACTIVE,
        "正在读取配置",
        "完成后将显示可编辑配置。",
        loading = true,
    )
    ConfigurationUiState.Empty -> emptyPresentation()
    is ConfigurationUiState.Ready -> ConfigurationPresentation(
        showEditor = state.instances.any { it.formatState == ConfigurationFormatState.CURRENT },
        showSaveAction = state.instances.any { it.formatState == ConfigurationFormatState.CURRENT },
        disclosure = FIXTURE_DISCLOSURE_TEXT.takeIf { state.isFixture },
        panelKind = null,
        statePanel = null,
    )
    is ConfigurationUiState.Failure -> panel(
        ConfigurationPanelKind.FAILURE,
        MiuixIcons.Close,
        StatePanelTone.ERROR,
        "配置读取失败",
        "无法读取配置，请稍后刷新。",
    )
    is ConfigurationUiState.PermissionDenied -> panel(
        ConfigurationPanelKind.PERMISSION_DENIED,
        MiuixIcons.Close,
        StatePanelTone.ERROR,
        "配置权限不足",
        "请在 KernelSU Manager 授权后刷新。",
    )
    is ConfigurationUiState.Unavailable -> panel(
        ConfigurationPanelKind.UNAVAILABLE,
        MiuixIcons.Info,
        StatePanelTone.NEUTRAL,
        "配置不可用",
        "当前环境不提供配置管理。",
    )
}

private fun emptyPresentation() = panel(
    ConfigurationPanelKind.EMPTY,
    MiuixIcons.Info,
    StatePanelTone.NEUTRAL,
    "暂无配置",
    "当前没有可编辑的配置实例。",
)

private fun isCombinedEmpty(state: ConfigurationUiState, rows: List<ConfigurationRowPresentation>): Boolean =
    rows.isEmpty() && (state is ConfigurationUiState.Empty || state is ConfigurationUiState.Ready)

private fun legacyConfigurationRows(state: ConfigurationUiState): List<ConfigurationRowPresentation> =
    configurationInstances(state).map { instance ->
        ConfigurationRowPresentation(
            key = instance.instanceId.value,
            label = instance.displayLabel,
            kind = when (instance.formatState) {
                ConfigurationFormatState.CURRENT -> ConfigurationRowKind.CONFIGURED
                ConfigurationFormatState.LEGACY -> ConfigurationRowKind.REBUILD_REQUIRED
                ConfigurationFormatState.UNSUPPORTED -> ConfigurationRowKind.UNAVAILABLE
            },
            instance = instance,
            status = when (instance.formatState) {
                ConfigurationFormatState.CURRENT -> null
                ConfigurationFormatState.LEGACY -> "旧配置需重建；请先确认活动订阅"
                ConfigurationFormatState.UNSUPPORTED -> "配置版本暂不支持，请更新 App"
            },
            canApply = canApplyConfiguration(state, instance.instanceId),
        )
    }

private fun simConfigurationRows(
    state: ConfigurationUiState,
    sims: List<SimControlInstance>,
): List<ConfigurationRowPresentation> {
    val configurations = configurationInstances(state).associateBy { it.instanceId }
    return sims.sortedBy { it.slotIndex }.mapNotNull { sim ->
        val tail = safeConfigurationLabel(sim.displayLabel).substringAfterLast(" · ").takeIf { MASKED_TAIL.matches(it) }
        val details = listOfNotNull(if (sim.mcc != null && sim.mnc != null) "${sim.mcc} / ${sim.mnc}" else null, tail)
            .joinToString(" · ").takeIf { it.isNotBlank() }
        val label = if (sim.slotIndex == null) safeConfigurationLabel(sim.displayLabel)
            else listOfNotNull(simCarrierTitle(sim.slotIndex, sim.homeCarrierLabel, sim.mcc), details).joinToString(" · ")
        when (sim.presence) {
            SimPresence.ACTIVE_UNCONFIGURED -> ConfigurationRowPresentation(
                key = sim.handle.value,
                label = label,
                kind = ConfigurationRowKind.ACTIVE_UNCONFIGURED,
                status = "未配置/未支持",
                handle = sim.handle,
                canProvision = sim.canProvision,
            )
            SimPresence.ACTIVE_MATCHED -> configuredSimRow(state, configurations, sim, label)
            SimPresence.REMOVED_CONFIG_RETAINED -> null
        }
    }
}

private fun configuredSimRow(
    state: ConfigurationUiState,
    configurations: Map<ConfigurationInstanceId, ConfigurationInstance>,
    sim: SimControlInstance,
    label: String,
): ConfigurationRowPresentation {
    val id = ConfigurationInstanceId(sim.handle.value)
    val configuration = configurations[id] ?: return ConfigurationRowPresentation(
        key = sim.handle.value,
        label = label,
        kind = ConfigurationRowKind.UNAVAILABLE,
        status = "配置暂不可用",
    )
    if (configuration.formatState != ConfigurationFormatState.CURRENT) {
        val legacy = configuration.formatState == ConfigurationFormatState.LEGACY
        val stopped = configuration.lifecycle == ConfigurationLifecycle.STOPPED && sim.lifecycle == EngineLifecycle.STOPPED
        return ConfigurationRowPresentation(
            key = sim.handle.value, label = label,
            kind = if (legacy) ConfigurationRowKind.REBUILD_REQUIRED else ConfigurationRowKind.UNAVAILABLE,
            instance = configuration,
            status = when {
                !legacy -> "配置版本暂不支持，请更新 App"
                !stopped -> "旧配置需重建；请先正常停止该 SIM 的引擎"
                else -> "旧配置需重建；旧自定义值将全部清除，历史记录和日志保留"
            },
            handle = sim.handle,
            canRebuild = legacy && stopped && !sim.operationPending && sim.slotIndex?.let { it in 0..1 } == true &&
                state is ConfigurationUiState.Ready && state.applyingInstanceId == null,
        )
    }
    return ConfigurationRowPresentation(
        key = sim.handle.value,
        label = label,
        kind = ConfigurationRowKind.CONFIGURED,
        instance = configuration.copy(displayLabel = label,
            systemSubscription = (configuration.systemSubscription as? SubscriptionDiscovery.Matched)
                ?.copy(carrierLabel = homeCarrierLabel(sim.homeCarrierLabel)) ?: configuration.systemSubscription),
        canApply = sim.canApply && canApplyConfiguration(state, id),
    )
}

private fun configurationInstances(state: ConfigurationUiState): List<ConfigurationInstance> =
    (state as? ConfigurationUiState.Ready)?.instances.orEmpty()

private fun simInventoryPanel(state: SimControlUiState): StatePanelSpec = when (state) {
    SimControlUiState.Loading -> StatePanelSpec(
        MiuixIcons.Refresh,
        StatePanelTone.INTERACTIVE,
        "正在读取 SIM 状态",
        "现有配置仍可编辑。",
        loading = true,
    )
    is SimControlUiState.PermissionDenied -> StatePanelSpec(
        MiuixIcons.Close,
        StatePanelTone.ERROR,
        "SIM 状态权限不足",
        "现有配置仍可编辑。",
    )
    is SimControlUiState.Failure -> StatePanelSpec(
        MiuixIcons.Close,
        StatePanelTone.ERROR,
        "SIM 状态读取失败",
        "现有配置仍可编辑。",
    )
    is SimControlUiState.Ready -> error("Ready inventory does not need a supplemental panel")
}

private fun simReadyMessagePanel(message: String?): StatePanelSpec? = message?.let {
    StatePanelSpec(
        MiuixIcons.Info,
        StatePanelTone.WARNING,
        "SIM 状态可能不完整",
        "已显示可用的 SIM 状态。",
    )
}

private fun safeConfigurationLabel(label: String): String = label.takeIf {
    it.isNotBlank() &&
        !it.contains("subId", ignoreCase = true) &&
        "/data/" !in it &&
        "/proc/" !in it &&
        !LONG_DIGIT_RUN.containsMatchIn(it)
} ?: "实例 ••••"

internal fun canApplyConfiguration(state: ConfigurationUiState, id: ConfigurationInstanceId): Boolean =
    state is ConfigurationUiState.Ready && state.applyingInstanceId != id &&
        state.instances.any { it.instanceId == id && it.formatState == ConfigurationFormatState.CURRENT }

private fun panel(
    kind: ConfigurationPanelKind,
    icon: ImageVector,
    tone: StatePanelTone,
    title: String,
    description: String,
    loading: Boolean = false,
) = ConfigurationPresentation(false, false, null, kind, StatePanelSpec(icon, tone, title, description, loading))

private val LONG_DIGIT_RUN = Regex("""\d{15,}""")
private val MASKED_TAIL = Regex("•••• [0-9]{4}")
