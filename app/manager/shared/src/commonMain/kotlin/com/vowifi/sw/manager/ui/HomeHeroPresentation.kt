package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.RegistrationState
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimPresence

internal fun homeHeroPresentation(
    registration: RegistrationUiState,
    engine: SimControlUiState,
    diagnostics: EnvironmentDiagnosticsUiState,
): HomeHeroPresentation {
    if (registration is RegistrationUiState.Loading || engine is SimControlUiState.Loading || diagnostics is EnvironmentDiagnosticsUiState.Loading) {
        return homeHero(
            SemanticTone.INTERACTIVE,
            "正在读取",
            HomeHeroCopy("正在汇总 VoWiFi 状态", "读取 IMS、引擎与环境状态。", "正在读取", listOf("汇总 IMS、引擎与环境状态")),
        )
    }
    if (registration is RegistrationUiState.ReadError || registration is RegistrationUiState.ParseError || engine is SimControlUiState.Failure || diagnostics is EnvironmentDiagnosticsUiState.Failure) {
        return homeHero(
            SemanticTone.ERROR,
            "需要处理",
            HomeHeroCopy("状态读取失败", "部分状态暂时不可用，请刷新后重试。", "状态${homeNonBreakingPhrase("不可用")}", listOf("部分状态暂时不可用", "刷新后重试")),
        )
    }
    if (engine is SimControlUiState.PermissionDenied) {
        return homeHero(
            SemanticTone.WARNING,
            "权限不足",
            HomeHeroCopy("无法确认引擎状态", "KernelSU 尚未允许读取或控制 SIM 实例。", "权限不足", listOf("KernelSU 尚未授权", "无法确认 SIM 引擎状态")),
        )
    }
    val instances = (engine as? SimControlUiState.Ready)?.instances.orEmpty()
    if (instances.any { it.presence == SimPresence.ACTIVE_UNCONFIGURED }) {
        return homeHero(
            SemanticTone.WARNING,
            "未配置",
            HomeHeroCopy(
                "需要创建 SIM 配置",
                "至少一张活动 SIM 尚无可用配置。",
                "SIM ${homeNonBreakingPhrase("未配置")}",
                listOf("活动 SIM 尚无${homeNonBreakingPhrase("配置")}", "创建后才可启动引擎"),
            ),
        )
    }
    if (registration is RegistrationUiState.Empty) {
        return homeHero(
            SemanticTone.NEUTRAL,
            "等待事件",
            HomeHeroCopy("尚无 IMS 注册事件", "启动引擎后，observer 会在这里报告注册变化。", "等待 IMS", listOf("尚无注册事件", "启动引擎后显示注册状态")),
        )
    }
    val snapshot = (registration as RegistrationUiState.Data).snapshot
    val registered = snapshot.latestBySubId.count { it.normalizedState == RegistrationState.REGISTERED }
    val total = snapshot.latestBySubId.size
    val allRegistered = total > 0 && registered == total
    val runningReady = instances.any { it.lifecycle == EngineLifecycle.RUNNING && it.runtime?.tunnel == "CONNECTED" && it.runtime.ims == "REGISTERED" }
    val environmentPass = diagnostics is EnvironmentDiagnosticsUiState.Ready && diagnostics.severity == DiagnosticCheckStatus.PASS
    return when {
        runningReady && !allRegistered && environmentPass -> availableHero("引擎报告 IMS 已注册，隧道已连接，环境检查通过。")
        runningReady && !allRegistered -> registeredHero(
            "引擎报告 IMS 已注册，隧道已连接。",
            listOf("引擎报告 IMS ${homeNonBreakingPhrase("已注册")}", "隧道${homeNonBreakingPhrase("已连接")}"),
        )
        allRegistered && runningReady && environmentPass -> availableHero("IMS 已注册，隧道已连接，环境检查通过。")
        allRegistered -> registeredHero("当前 $total 张 SIM 报告 IMS 已注册。", listOf("$total 张 SIM ${homeNonBreakingPhrase("已注册")}"))
        registered > 0 -> homeHero(
            SemanticTone.WARNING,
            "部分可用",
            HomeHeroCopy(
                "部分 SIM 已注册",
                "$registered / $total 张 SIM 报告 IMS 已注册。",
                "部分${homeNonBreakingPhrase("可用")}",
                listOf("$registered / $total 张 SIM ${homeNonBreakingPhrase("已注册")}"),
            ),
        )
        instances.any { it.lifecycle == EngineLifecycle.RUNNING } -> homeHero(
            SemanticTone.WARNING,
            "注册中断",
            HomeHeroCopy("IMS ${homeNonBreakingPhrase("尚未注册")}", "引擎正在运行，但 observer 尚未报告 IMS 注册。", "IMS ${homeNonBreakingPhrase("未注册")}", listOf("引擎正在运行", "observer 尚无注册事件")),
        )
        else -> homeHero(
            SemanticTone.NEUTRAL,
            "未注册",
            HomeHeroCopy("IMS ${homeNonBreakingPhrase("尚未注册")}", "可从下方活动 SIM 卡片启动现有引擎。", "IMS ${homeNonBreakingPhrase("未注册")}", listOf("从下方活动 SIM 行启动引擎")),
        )
    }
}

private fun availableHero(summary: String): HomeHeroPresentation = homeHero(
    SemanticTone.SUCCESS,
    "正常",
    HomeHeroCopy(
        "VoWiFi 已就绪",
        summary,
        "VoWiFi ${homeNonBreakingPhrase("可用")}",
        listOf("IMS ${homeNonBreakingPhrase("已注册")}", "隧道${homeNonBreakingPhrase("已连接")}", "环境检查通过"),
    ),
)

private fun registeredHero(summary: String, largeSummaryLines: List<String>): HomeHeroPresentation = homeHero(
    SemanticTone.SUCCESS,
    "已注册",
    HomeHeroCopy("IMS 已注册", summary, "IMS ${homeNonBreakingPhrase("已注册")}", largeSummaryLines),
)

private fun homeHero(tone: SemanticTone, statusText: String, copy: HomeHeroCopy) = HomeHeroPresentation(
    tone = tone,
    statusText = statusText,
    regularText = HomeHeroTextBlock(copy.title, listOf(copy.summary)),
    largeText = HomeHeroTextBlock(copy.largeTitle, copy.largeSummaryLines),
)

private data class HomeHeroCopy(
    val title: String,
    val summary: String,
    val largeTitle: String,
    val largeSummaryLines: List<String>,
)
