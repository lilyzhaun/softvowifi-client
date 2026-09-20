package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogUiState
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimPresence

fun settingsCatalog(snapshot: SettingsCatalogSnapshot): SettingsCatalog {
    val appearance = SettingsPagePresentation(
        SettingsRouteId.APPEARANCE,
        "外观",
        SettingsRefreshPolicy.None,
    )
    val configuration = configurationPage(snapshot)
    val diagnostics = SettingsPagePresentation(
        SettingsRouteId.DIAGNOSTICS,
        "环境诊断",
        SettingsRefreshPolicy.OnEnter(setOf(RefreshTarget.ENVIRONMENT_DIAGNOSTICS)),
    )
    val operationalLogs = operationalLogsPage(snapshot.operationalLogs, snapshot.exportBusy)
    val callNotification = callNotificationPage(snapshot.callNotification)
    val about = SettingsAboutPresentation(
        SettingsRouteId.ABOUT,
        "关于",
        "版本 ${safeVersion(snapshot.appVersion)}",
    )
    return SettingsCatalog(
        groups = listOf(
            group(
                SettingsGroupId.APPEARANCE,
                "界面与交互",
                routeRow(
                    SettingsRowId.Root.APPEARANCE,
                    "外观设置",
                    "悬浮底栏与模糊效果",
                    appearance.route,
                ),
            ),
            group(
                SettingsGroupId.SIM_AND_NETWORK,
                "SIM 与网络",
                routeRow(
                    SettingsRowId.Root.CONFIGURATION,
                    "SIM 与网络配置",
                    configurationSummary(snapshot, configuration.rows),
                    configuration.route,
                    configurationAvailable(snapshot, configuration.rows),
                ),
            ),
            group(
                SettingsGroupId.TROUBLESHOOTING,
                "诊断与日志",
                routeRow(
                    SettingsRowId.Root.DIAGNOSTICS,
                    "环境诊断",
                    diagnosticsSummary(snapshot.diagnostics),
                    diagnostics.route,
                    snapshot.diagnostics !is EnvironmentDiagnosticsUiState.Unavailable,
                ),
                routeRow(
                    SettingsRowId.Root.OPERATIONAL_LOGS,
                    "运行日志与导出",
                    operationalLogsSummary(snapshot.operationalLogs),
                    operationalLogs.route,
                    snapshot.operationalLogs !is OperationalLogUiState.Unavailable,
                ),
            ),
            group(
                SettingsGroupId.INCOMING_CALL,
                "来电通知",
                routeRow(
                    SettingsRowId.Root.CALL_NOTIFICATION,
                    "通知设置",
                    callNotificationSummary(snapshot.callNotification),
                    callNotification.route,
                    callNotificationAvailable(snapshot.callNotification),
                ),
            ),
            group(
                SettingsGroupId.ABOUT,
                "关于",
                routeRow(
                    SettingsRowId.Root.ABOUT,
                    "关于 SoftVoWiFi",
                    about.versionLabel,
                    about.route,
                ),
            ),
        ),
        appearance = appearance,
        configuration = configuration,
        diagnostics = diagnostics,
        operationalLogs = operationalLogs,
        callNotification = callNotification,
        about = about,
    )
}

private fun configurationPage(snapshot: SettingsCatalogSnapshot) = SettingsPagePresentation(
    SettingsRouteId.CONFIGURATION,
    "SIM 与网络配置",
    SettingsRefreshPolicy.OnEnter(setOf(RefreshTarget.CONFIGURATION, RefreshTarget.SIM_CONTROL)),
    configurationRows(snapshot.configuration, snapshot.sims),
)

private fun configurationRows(
    configuration: ConfigurationUiState,
    sims: SimControlUiState,
): List<SettingsRowPresentation> = when (sims) {
    is SimControlUiState.Ready -> readySimRows(configuration, sims.instances)
    SimControlUiState.Loading,
    is SimControlUiState.PermissionDenied,
    is SimControlUiState.Failure,
    -> legacyConfigurationRows(configuration)
}

private fun readySimRows(
    state: ConfigurationUiState,
    sims: List<SimControlInstance>,
): List<SettingsRowPresentation> {
    val configurations = (state as? ConfigurationUiState.Ready)?.instances.orEmpty()
        .associateBy { it.instanceId }
    val busy = state is ConfigurationUiState.Ready && state.applyingInstanceId != null
    val canProvision = (state is ConfigurationUiState.Ready || state is ConfigurationUiState.Empty) && !busy
    return sims.mapNotNull { sim ->
        when (sim.presence) {
            SimPresence.ACTIVE_UNCONFIGURED -> actionRow(
                SettingsRowId.Configuration.ActiveSim(sim.handle),
                safeSettingsLabel(sim.displayLabel),
                "未配置/未支持",
                SettingsAction.ProvisionConfiguration(sim.handle).takeIf { canProvision && sim.canProvision },
            )
            SimPresence.ACTIVE_MATCHED -> configuredRow(state, configurations, sim)
            SimPresence.REMOVED_CONFIG_RETAINED -> null
        }
    }
}

private fun configuredRow(
    state: ConfigurationUiState,
    configurations: Map<ConfigurationInstanceId, ConfigurationInstance>,
    sim: SimControlInstance,
): SettingsRowPresentation {
    val id = ConfigurationInstanceId(sim.handle.value)
    val configuration = configurations[id] ?: return actionRow(
        SettingsRowId.Configuration.UnavailableSim(sim.handle),
        safeSettingsLabel(sim.displayLabel),
        "配置暂不可用",
        null,
    )
    val action = SettingsAction.ApplyConfiguration(id).takeIf {
        state is ConfigurationUiState.Ready && state.applyingInstanceId == null && sim.canApply
    }
    return actionRow(
        SettingsRowId.Configuration.Configured(id),
        safeSettingsLabel(configuration.displayLabel),
        "编辑并保存现有配置",
        action,
    )
}

private fun legacyConfigurationRows(state: ConfigurationUiState): List<SettingsRowPresentation> {
    val ready = state as? ConfigurationUiState.Ready ?: return emptyList()
    return ready.instances.map { instance ->
        actionRow(
            SettingsRowId.Configuration.Configured(instance.instanceId),
            safeSettingsLabel(instance.displayLabel),
            "编辑并保存现有配置",
            SettingsAction.ApplyConfiguration(instance.instanceId).takeIf { ready.applyingInstanceId == null },
        )
    }
}

private fun operationalLogsPage(state: OperationalLogUiState, exportBusy: Boolean): SettingsPagePresentation {
    val entries = exportEntries(state)
    val action = if (entries.isNotEmpty() && !exportBusy) {
        SettingsAction.ExportOperationalLogs(entries)
    } else {
        null
    }
    val summary = when {
        exportBusy -> "正在导出日志"
        entries.isEmpty() -> "当前没有可导出的事件"
        else -> "导出当前 ${entries.size} 条事件"
    }
    return SettingsPagePresentation(
        SettingsRouteId.OPERATIONAL_LOGS,
        "运行日志",
        SettingsRefreshPolicy.OnEnter(setOf(RefreshTarget.OPERATIONAL_LOGS)),
        listOf(actionRow(SettingsRowId.OperationalLogExport, "导出日志", summary, action)),
    )
}

private fun exportEntries(state: OperationalLogUiState): List<OperationalLogEntry> = when (state) {
    is OperationalLogUiState.Ready -> state.filteredEntries
    is OperationalLogUiState.Warning -> state.filteredEntries
    OperationalLogUiState.Loading,
    OperationalLogUiState.Empty,
    is OperationalLogUiState.Failure,
    is OperationalLogUiState.PermissionDenied,
    is OperationalLogUiState.Unavailable,
    -> emptyList()
}

internal fun callNotificationPage(snapshot: CallNotificationSettingsSnapshot) = SettingsPagePresentation(
    SettingsRouteId.CALL_NOTIFICATION,
    "通知设置",
    SettingsRefreshPolicy.None,
    listOf(
        actionRow(
            SettingsRowId.NotificationPermission,
            "应用通知",
            permissionSummary(snapshot),
            SettingsAction.ManageNotificationPermission.takeIf {
                snapshot.freshness == NotificationSnapshotFreshness.CURRENT &&
                    snapshot.permission != NotificationPermissionStatus.UNAVAILABLE
            },
        ),
        actionRow(
            SettingsRowId.FullScreenIntent,
            "全屏来电",
            fullScreenSummary(snapshot),
            SettingsAction.ManageFullScreenIntent.takeIf {
                snapshot.freshness == NotificationSnapshotFreshness.CURRENT &&
                    snapshot.fullScreen in setOf(FullScreenStatus.DENIED, FullScreenStatus.ALLOWED)
            },
        ),
        actionRow(
            SettingsRowId.IncomingCallChannel,
            "来电通知渠道",
            channelSummary(snapshot),
            SettingsAction.ManageIncomingCallChannel.takeIf {
                snapshot.freshness == NotificationSnapshotFreshness.CURRENT &&
                    snapshot.channel in setOf(IncomingCallChannelStatus.BLOCKED, IncomingCallChannelStatus.REDUCED, IncomingCallChannelStatus.HIGH)
            },
        ),
    ),
)

private fun group(id: SettingsGroupId, title: String, vararg rows: SettingsRowPresentation) =
    SettingsGroupPresentation(id, title, rows.toList())

private fun routeRow(
    id: SettingsRowId.Root,
    title: String,
    summary: String,
    route: SettingsRouteId,
    enabled: Boolean = true,
) = SettingsRowPresentation(id, title, summary, enabled, SettingsRowTarget.Navigate(route).takeIf { enabled })

private fun actionRow(id: SettingsRowId, title: String, summary: String, action: SettingsAction?) =
    SettingsRowPresentation(id, title, summary, action != null, action?.let(SettingsRowTarget::Action))

private fun safeSettingsLabel(value: String): String = value.takeIf {
    it.isNotBlank() && !SENSITIVE_LABEL.containsMatchIn(it) && !LONG_DIGIT_RUN.containsMatchIn(it)
} ?: "实例 ••••"

private val SENSITIVE_LABEL = Regex("ICCID|IMSI|MSISDN|/data/|/proc/|owner|token", RegexOption.IGNORE_CASE)
private val LONG_DIGIT_RUN = Regex("""\d{15,}""")
private val SAFE_VERSION = Regex("[A-Za-z0-9._+-]{1,32}")

private fun safeVersion(value: String) = value.takeIf(SAFE_VERSION::matches) ?: "未知"
