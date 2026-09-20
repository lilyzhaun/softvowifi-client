package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.OperationalLogUiState
import com.vowifi.sw.manager.model.SimControlUiState

internal fun configurationSummary(
    snapshot: SettingsCatalogSnapshot,
    rows: List<SettingsRowPresentation>,
): String = when (val configuration = snapshot.configuration) {
    is ConfigurationUiState.Ready -> readyConfigurationSummary(configuration, snapshot.sims, rows)
    ConfigurationUiState.Loading -> "正在读取 SIM 与网络配置"
    is ConfigurationUiState.PermissionDenied -> "需要 KernelSU 授权"
    is ConfigurationUiState.Failure -> "配置状态读取失败"
    is ConfigurationUiState.Unavailable -> "当前环境不提供配置管理"
    ConfigurationUiState.Empty -> emptyConfigurationSummary(snapshot.sims, rows)
}

private fun readyConfigurationSummary(
    state: ConfigurationUiState.Ready,
    sims: SimControlUiState,
    rows: List<SettingsRowPresentation>,
): String = when {
    state.applyingInstanceId != null -> "正在保存配置"
    rows.any { it.enabled } -> "${rows.size} 张 SIM 可配置"
    rows.isNotEmpty() -> "${rows.size} 张 SIM 状态可见"
    sims is SimControlUiState.Loading -> "正在读取 SIM 与网络配置"
    sims is SimControlUiState.PermissionDenied -> "需要 KernelSU 授权"
    sims is SimControlUiState.Failure -> "配置状态读取失败"
    else -> "当前没有可配置的 SIM"
}

private fun emptyConfigurationSummary(
    sims: SimControlUiState,
    rows: List<SettingsRowPresentation>,
): String = when {
    rows.any { it.enabled } -> "${rows.size} 张 SIM 可配置"
    rows.isNotEmpty() -> "${rows.size} 张 SIM 状态可见"
    sims is SimControlUiState.Loading -> "正在读取 SIM 与网络配置"
    sims is SimControlUiState.PermissionDenied -> "需要 KernelSU 授权"
    sims is SimControlUiState.Failure -> "配置状态读取失败"
    else -> "当前没有可配置的 SIM"
}

internal fun configurationAvailable(
    snapshot: SettingsCatalogSnapshot,
    rows: List<SettingsRowPresentation>,
): Boolean = rows.isNotEmpty() || snapshot.configuration !is ConfigurationUiState.Unavailable

internal fun diagnosticsSummary(state: EnvironmentDiagnosticsUiState): String = when (state) {
    EnvironmentDiagnosticsUiState.Loading -> "正在检查运行环境"
    is EnvironmentDiagnosticsUiState.Ready -> "${state.checks.size} 项检查 · ${diagnosticResult(state.severity)}"
    is EnvironmentDiagnosticsUiState.PermissionDenied -> "需要 KernelSU 授权"
    is EnvironmentDiagnosticsUiState.Failure -> "环境检查未完成"
    is EnvironmentDiagnosticsUiState.Unavailable -> "当前环境不提供诊断"
}

internal fun operationalLogsSummary(state: OperationalLogUiState): String = when (state) {
    OperationalLogUiState.Loading -> "正在读取运行日志"
    OperationalLogUiState.Empty -> "暂无运行日志"
    is OperationalLogUiState.Ready -> "${state.filteredEntries.size} 条事件"
    is OperationalLogUiState.Warning -> "${state.filteredEntries.size} 条事件\n可能不完整"
    is OperationalLogUiState.Failure -> "日志读取失败"
    is OperationalLogUiState.PermissionDenied -> "当前权限无法读取运行日志"
    is OperationalLogUiState.Unavailable -> "当前环境不提供运行日志"
}

internal fun callNotificationSummary(snapshot: CallNotificationSettingsSnapshot): String = when {
    snapshot.freshness != NotificationSnapshotFreshness.CURRENT -> "通知状态需要刷新"
    snapshot.permission == NotificationPermissionStatus.DENIED -> "需要允许来电通知"
    snapshot.permission == NotificationPermissionStatus.UNAVAILABLE -> "通知能力不可用"
    snapshot.channel == IncomingCallChannelStatus.BLOCKED -> "来电通知渠道已关闭"
    snapshot.channel == IncomingCallChannelStatus.REDUCED -> "来电渠道重要度降低，悬浮提醒可能不可用"
    snapshot.channel == IncomingCallChannelStatus.MISSING -> "来电通知渠道尚未创建"
    snapshot.channel == IncomingCallChannelStatus.UNAVAILABLE -> "来电渠道状态不可用"
    snapshot.fullScreen == FullScreenStatus.DENIED -> "需要允许全屏来电"
    snapshot.fullScreen == FullScreenStatus.UNAVAILABLE -> "全屏来电不可用"
    else -> "系统能力已允许，实际提醒由系统决定"
}

internal fun permissionSummary(snapshot: CallNotificationSettingsSnapshot): String = when {
    snapshot.freshness != NotificationSnapshotFreshness.CURRENT -> "状态不可用"
    snapshot.permission == NotificationPermissionStatus.GRANTED -> "已允许 · 管理应用通知"
    snapshot.permission == NotificationPermissionStatus.DENIED -> "需要在系统设置中允许"
    else -> "当前环境不支持"
}

internal fun fullScreenSummary(snapshot: CallNotificationSettingsSnapshot): String = when {
    snapshot.freshness != NotificationSnapshotFreshness.CURRENT -> "状态不可用"
    snapshot.fullScreen == FullScreenStatus.ALLOWED -> "已允许 · 管理全屏访问"
    snapshot.fullScreen == FullScreenStatus.DENIED -> "需要在系统设置中允许"
    snapshot.fullScreen == FullScreenStatus.NOT_REQUIRED -> "此 Android 版本无需单独授权，实际展示由系统决定"
    else -> "当前环境不支持"
}

internal fun channelSummary(snapshot: CallNotificationSettingsSnapshot): String =
    if (snapshot.freshness != NotificationSnapshotFreshness.CURRENT) "状态不可用，请刷新" else when (snapshot.channel) {
        IncomingCallChannelStatus.UNAVAILABLE -> "当前环境不支持"
        IncomingCallChannelStatus.MISSING -> "尚未创建，无法确认来电提醒能力；可在应用通知设置中查看"
        IncomingCallChannelStatus.BLOCKED -> "已关闭，即使应用通知允许也无法显示来电通知"
        IncomingCallChannelStatus.REDUCED -> "重要度已降低，通知不代表可悬浮提醒；可在系统中管理"
        IncomingCallChannelStatus.HIGH -> "高重要度，悬浮与锁屏展示仍由系统决定；管理渠道"
    }

internal fun callNotificationAvailable(snapshot: CallNotificationSettingsSnapshot): Boolean =
    snapshot.freshness != NotificationSnapshotFreshness.MALFORMED &&
        (snapshot.permission != NotificationPermissionStatus.UNAVAILABLE ||
            snapshot.fullScreen != FullScreenStatus.UNAVAILABLE ||
            snapshot.observerChannel != ObserverChannelStatus.UNAVAILABLE)

internal fun observerChannelRow(snapshot: CallNotificationSettingsSnapshot): SettingsRowPresentation {
    val available = snapshot.freshness == NotificationSnapshotFreshness.CURRENT &&
        snapshot.observerChannel != ObserverChannelStatus.UNAVAILABLE
    val state = when {
        snapshot.freshness != NotificationSnapshotFreshness.CURRENT -> "状态不可用，请刷新"
        snapshot.observerChannel == ObserverChannelStatus.UNAVAILABLE -> "当前环境不支持"
        snapshot.permission == NotificationPermissionStatus.DENIED -> "应用通知已关闭，短信和来电通知也受影响"
        snapshot.observerChannel == ObserverChannelStatus.MISSING -> "渠道尚未创建，打开应用通知设置查看"
        snapshot.observerChannel == ObserverChannelStatus.BLOCKED -> "此渠道已关闭"
        else -> "此渠道已开启"
    }
    return SettingsRowPresentation(
        SettingsRowId.ObserverChannel, "后台常驻通知",
        "$state。可在系统设置中关闭此渠道，不影响短信与来电渠道；后台服务仍保留。",
        available, if (available) SettingsRowTarget.Action(SettingsAction.ManageObserverChannel) else null,
    )
}

private fun diagnosticResult(status: DiagnosticCheckStatus): String = when (status) {
    DiagnosticCheckStatus.PASS -> "通过"
    DiagnosticCheckStatus.WARN -> "需注意"
    DiagnosticCheckStatus.FAIL -> "未通过"
}
