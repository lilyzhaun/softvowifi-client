package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimPresence

internal fun homeHeroPresentation(
    registration: RegistrationUiState,
    engine: SimControlUiState,
    diagnostics: EnvironmentDiagnosticsUiState,
    ims: HomeSoftwareImsProjection = homeSoftwareImsProjection(engine),
): HomeHeroPresentation {
    if (engine is SimControlUiState.Loading || diagnostics is EnvironmentDiagnosticsUiState.Loading) {
        return homeHero(
            SemanticTone.INTERACTIVE,
            "正在读取",
            HomeHeroCopy("正在汇总 VoWiFi 状态", "读取 IMS、引擎与环境状态。", "正在读取", listOf("汇总 IMS、引擎与环境状态")),
        )
    }
    if (engine is SimControlUiState.Failure || diagnostics is EnvironmentDiagnosticsUiState.Failure) {
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
    val instances = ims.active
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
    if (ims.total == 0) {
        return homeHero(
            SemanticTone.NEUTRAL,
            "无 SIM",
            HomeHeroCopy("暂无活动 SIM", "检测到活动 SIM 后显示软件 IMS 状态。", "无活动 SIM", listOf("等待活动 SIM")),
        )
    }
    val environmentPass = diagnostics is EnvironmentDiagnosticsUiState.Ready && diagnostics.severity == DiagnosticCheckStatus.PASS
    val controlWarning = (engine as? SimControlUiState.Ready)?.message != null || instances.any { it.message != null }
    return when {
        ims.unknown > 0 -> homeHero(
            SemanticTone.WARNING, "待确认",
            HomeHeroCopy("IMS 状态待确认", ims.overview, "IMS 待确认", listOf("${ims.registered} / ${ims.total} 张已注册", "${ims.unknown} 张状态未知")),
        )
        ims.allRegistered && ims.connected == ims.total && environmentPass && !controlWarning -> availableHero("IMS 已注册，隧道已连接，环境检查通过。")
        ims.allRegistered -> homeHero(
            SemanticTone.WARNING, if (ims.connected != ims.total) "隧道未就绪" else "需要检查",
            HomeHeroCopy("IMS 已注册", if (ims.connected != ims.total) "IMS 已注册，但并非所有隧道已连接。" else "IMS 已注册，仍需确认控制与环境状态。", "IMS ${homeNonBreakingPhrase("已注册")}", listOf("${ims.total} 张 SIM 已注册", "隧道、控制与环境仍需确认")),
        )
        ims.registered > 0 -> homeHero(
            SemanticTone.WARNING,
            "部分注册",
            HomeHeroCopy(
                "部分 SIM 已注册",
                ims.overview,
                "部分${homeNonBreakingPhrase("已注册")}",
                listOf("${ims.registered} / ${ims.total} 张 SIM ${homeNonBreakingPhrase("已注册")}"),
            ),
        )
        instances.any { it.lifecycle == EngineLifecycle.STARTING || it.lifecycle == EngineLifecycle.STOPPING } -> homeHero(
            SemanticTone.INTERACTIVE, "引擎切换中",
            HomeHeroCopy("引擎状态切换中", "等待启动或停止完成后确认 IMS 状态。", "引擎切换中", listOf("等待启动或停止完成")),
        )
        instances.any { it.lifecycle == EngineLifecycle.RUNNING } -> homeHero(
            SemanticTone.WARNING,
            "注册中断",
            HomeHeroCopy("IMS ${homeNonBreakingPhrase("尚未注册")}", "引擎正在运行，软件 IMS 尚未注册。", "IMS ${homeNonBreakingPhrase("未注册")}", listOf("引擎正在运行", "软件 IMS 尚未注册")),
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
        "IMS 已就绪",
        summary,
        "IMS ${homeNonBreakingPhrase("已就绪")}",
        listOf("IMS ${homeNonBreakingPhrase("已注册")}", "隧道${homeNonBreakingPhrase("已连接")}", "环境检查通过"),
    ),
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
