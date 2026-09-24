package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.CountryCatalogGenerated
import com.vowifi.sw.manager.data.homeCarrierLabel
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import com.vowifi.sw.manager.ui.navigation.AppRoute

internal fun homeDashboardPresentation(
    registrationState: RegistrationUiState,
    engineState: SimControlUiState,
    diagnosticsState: EnvironmentDiagnosticsUiState,
    nowMs: Long,
    localDateLabel: (Long) -> String = { messageDateSpec(it).label },
): HomeDashboardPresentation {
    val ims = homeSoftwareImsProjection(engineState)
    return HomeDashboardPresentation(
        notices = homeNotices(engineState, diagnosticsState),
        hero = homeHeroPresentation(registrationState, engineState, diagnosticsState, ims),
        metrics = homeMetrics(ims, engineState),
        actions = (engineState as? SimControlUiState.Ready)?.instances.orEmpty().map(::homeSimAction),
        facts = homeFacts(ims, diagnosticsState),
    )
}

internal fun dispatchHomeAction(
    action: HomeActionPresentation,
    onStart: (SimHandle) -> Unit,
    onStop: (SimHandle) -> Unit,
    onProvision: (SimHandle) -> Unit,
) {
    when (action.id) {
        HomeActionId.START -> onStart(action.handle)
        HomeActionId.STOP -> onStop(action.handle)
        HomeActionId.PROVISION -> onProvision(action.handle)
    }
}

internal fun homeEventTime(timestampMs: Long, nowMs: Long, localDateLabel: (Long) -> String): String =
    formatCommunicationTime(timestampMs, nowMs, localDateLabel)

private fun homeNotices(
    engine: SimControlUiState,
    diagnostics: EnvironmentDiagnosticsUiState,
): List<HomeNoticePresentation> = buildList {
    when (engine) {
        is SimControlUiState.Failure -> add(
            HomeNoticePresentation(
                NoticeCardSpec(SemanticTone.ERROR, "无法读取 SIM 与引擎状态", "请刷新后重试。"),
                AppRoute.SettingsConfiguration,
            ),
        )
        is SimControlUiState.PermissionDenied -> add(
            HomeNoticePresentation(
                NoticeCardSpec(SemanticTone.WARNING, "SIM 控制权限不足", "请在 KernelSU Manager 授权后刷新。"),
                AppRoute.SettingsConfiguration,
            ),
        )
        is SimControlUiState.Ready -> engine.message?.let {
            add(
                HomeNoticePresentation(
                    NoticeCardSpec(SemanticTone.WARNING, "SIM 控制状态需注意", homeSafeText(it, "部分 SIM 状态暂时不可用。", 80)),
                    AppRoute.SettingsConfiguration,
                ),
            )
        }
        SimControlUiState.Loading -> Unit
    }
    when (diagnostics) {
        is EnvironmentDiagnosticsUiState.Failure -> add(
            HomeNoticePresentation(
                NoticeCardSpec(SemanticTone.ERROR, "环境检查失败", "请刷新后重试。"),
                AppRoute.SettingsDiagnostics,
            ),
        )
        is EnvironmentDiagnosticsUiState.PermissionDenied -> add(
            HomeNoticePresentation(
                NoticeCardSpec(SemanticTone.WARNING, "环境检查权限不足", "请在 KernelSU Manager 授权后刷新。"),
                AppRoute.SettingsDiagnostics,
            ),
        )
        is EnvironmentDiagnosticsUiState.Unavailable -> add(
            HomeNoticePresentation(
                NoticeCardSpec(SemanticTone.NEUTRAL, "环境检查不可用", "当前环境不提供诊断摘要。"),
                AppRoute.SettingsDiagnostics,
            ),
        )
        is EnvironmentDiagnosticsUiState.Ready -> when (diagnostics.severity) {
            DiagnosticCheckStatus.FAIL -> add(
                HomeNoticePresentation(
                    NoticeCardSpec(SemanticTone.ERROR, "环境检查未通过", "请前往诊断页查看详情。"),
                    AppRoute.SettingsDiagnostics,
                ),
            )
            DiagnosticCheckStatus.WARN -> add(
                HomeNoticePresentation(
                    NoticeCardSpec(SemanticTone.WARNING, "环境存在告警", "请前往诊断页查看详情。"),
                    AppRoute.SettingsDiagnostics,
                ),
            )
            DiagnosticCheckStatus.PASS -> Unit
        }
        EnvironmentDiagnosticsUiState.Loading -> Unit
    }
}

private fun homeMetrics(
    ims: HomeSoftwareImsProjection,
    engine: SimControlUiState,
): List<HomeMetricPresentation> {
    val instances = (engine as? SimControlUiState.Ready)?.instances.orEmpty()
    val running = instances.count { it.lifecycle == EngineLifecycle.RUNNING }
    return listOf(
        HomeMetricPresentation(ims.metric, "已注册 IMS", ims.tone),
        HomeMetricPresentation(
            if (engine is SimControlUiState.Ready) running.toString() else "未知",
            "运行中引擎",
            when {
                running > 0 -> SemanticTone.SUCCESS
                instances.any { it.lifecycle == EngineLifecycle.STARTING || it.lifecycle == EngineLifecycle.STOPPING } -> SemanticTone.WARNING
                else -> SemanticTone.NEUTRAL
            },
        ),
    )
}

private fun homeSimAction(instance: SimControlInstance): HomeSimActionPresentation {
    val busy = instance.lifecycle == EngineLifecycle.STARTING || instance.lifecycle == EngineLifecycle.STOPPING
    val actionId = when {
        instance.presence == SimPresence.ACTIVE_UNCONFIGURED -> HomeActionId.PROVISION
        instance.lifecycle == EngineLifecycle.RUNNING || instance.lifecycle == EngineLifecycle.STOPPING -> HomeActionId.STOP
        else -> HomeActionId.START
    }
    val enabled = when (actionId) {
        HomeActionId.START -> instance.canStart
        HomeActionId.STOP -> instance.canStop
        HomeActionId.PROVISION -> instance.canProvision
    } && !busy
    val label = when {
        instance.lifecycle == EngineLifecycle.STARTING -> "启动中"
        instance.lifecycle == EngineLifecycle.STOPPING -> "停止中"
        actionId == HomeActionId.START -> "启动引擎"
        actionId == HomeActionId.STOP -> "停止引擎"
        else -> "创建配置"
    }
    val status = if (instance.presence == SimPresence.ACTIVE_UNCONFIGURED) "未配置" else engineLabel(instance.lifecycle)
    val summary = when {
        instance.presence == SimPresence.ACTIVE_UNCONFIGURED && instance.canProvision -> "运营商资料已就绪，可以创建现有配置。"
        instance.presence == SimPresence.ACTIVE_UNCONFIGURED -> "当前运营商资料不足，无法自动创建配置。"
        else -> "进程 ${processLabel(instance.pid)} · 隧道 ${homeSafeText(instance.runtime?.tunnel, "未连接", 24)} · IMS ${homeImsStatusLabel(instance.runtime?.ims)}"
    }
    return HomeSimActionPresentation(
        title = listOfNotNull(instance.slotIndex?.let { "SIM ${it + 1}" }, listOfNotNull(
            homeCountryFlag(CountryCatalogGenerated.countryForMcc(instance.mcc).uppercase()),
            homeCarrierLabel(instance.homeCarrierLabel),
        ).joinToString(" ")).joinToString(" · "),
        summary = listOfNotNull(
            if (instance.mcc != null && instance.mnc != null) homeSafeText("PLMN ${instance.mcc}/${instance.mnc}", "网络未知", 24) else null,
            summary,
            instance.message?.takeIf { instance.presence != SimPresence.ACTIVE_UNCONFIGURED }
                ?.let { homeSafeText(it, "操作未完成，请前往配置查看", 80) },
        ).joinToString("\n"),
        statusText = status,
        tone = instance.homeTone(),
        action = HomeActionPresentation(actionId, instance.handle, label, enabled, busy),
    )
}

private fun SimControlInstance.homeTone(): SemanticTone = when {
    message != null -> SemanticTone.ERROR
    presence == SimPresence.ACTIVE_UNCONFIGURED || lifecycle == EngineLifecycle.STARTING || lifecycle == EngineLifecycle.STOPPING -> SemanticTone.WARNING
    lifecycle == EngineLifecycle.RUNNING && runtime?.tunnel == "CONNECTED" -> SemanticTone.SUCCESS
    else -> SemanticTone.NEUTRAL
}

private fun homeFacts(
    ims: HomeSoftwareImsProjection,
    diagnostics: EnvironmentDiagnosticsUiState,
): List<HomeFactPresentation> {
    return listOf(
        HomeFactPresentation("IMS 注册概况", ims.overview),
        HomeFactPresentation("活动 SIM", if (ims.readable) "${ims.total} 张" else "未知"),
        HomeFactPresentation("隧道连接", when {
            !ims.readable -> "未知"
            ims.total == 0 -> "暂无活动 SIM"
            ims.active.any { it.lifecycle == EngineLifecycle.RUNNING && it.runtime == null } -> "未知"
            else -> "${ims.connected} / ${ims.total} 张活动 SIM 已连接"
        }),
        HomeFactPresentation("环境检查", diagnostics.homeDiagnosticFact()),
    )
}

private fun EnvironmentDiagnosticsUiState.homeDiagnosticFact(): String = when (this) {
    EnvironmentDiagnosticsUiState.Loading -> "正在检查"
    is EnvironmentDiagnosticsUiState.Ready -> "${checks.size} 项 · ${diagnosticStatusLabel(severity)}"
    is EnvironmentDiagnosticsUiState.Failure -> "检查失败"
    is EnvironmentDiagnosticsUiState.PermissionDenied -> "权限不足"
    is EnvironmentDiagnosticsUiState.Unavailable -> "不可用"
}

private fun homeSafeText(value: String?, fallback: String, limit: Int): String {
    val normalized = value?.replace(Regex("\\s+"), " ")?.trim()?.takeIf(String::isNotEmpty) ?: return fallback
    val lower = normalized.lowercase()
    if (HOME_SENSITIVE.any(lower::contains) || Regex("\\d{13,}").containsMatchIn(normalized)) return fallback
    return if (normalized.length <= limit) normalized else normalized.take(limit - 1) + "…"
}

private fun homeImsStatusLabel(ims: String?): String {
    val fallback = homeNonBreakingPhrase("未注册")
    val text = ims?.trim()?.takeIf(String::isNotEmpty) ?: return fallback
    return if (text == "未注册" || text.equals("unregistered", ignoreCase = true)) {
        fallback
    } else {
        homeSafeText(text, fallback, 24)
    }
}

internal fun homeCountryFlag(iso2: String?): String? {
    if (iso2 == null || iso2.length != 2 || iso2.any { it !in 'A'..'Z' }) return null
    return buildString {
        iso2.forEach { letter ->
            append('\uD83C')
            append((0xDDE6 + (letter - 'A')).toChar())
        }
    }
}

private val HOME_SENSITIVE = listOf("iccid", "imsi", "msisdn", "subid", "token", "/data/", "/proc/", "sip:", "raw:")
