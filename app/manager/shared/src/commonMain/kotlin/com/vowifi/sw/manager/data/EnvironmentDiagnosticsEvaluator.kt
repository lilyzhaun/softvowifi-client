package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigDiagnostics
import com.vowifi.sw.manager.model.DiagnosticCheck
import com.vowifi.sw.manager.model.DiagnosticCheckId
import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.EngineDiagnostics
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsFormatException
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsSnapshot
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.ModuleDiagnostics
import com.vowifi.sw.manager.model.ModuleMarkers
import com.vowifi.sw.manager.model.ObserverDiagnostics
import com.vowifi.sw.manager.model.RootDiagnostics
import com.vowifi.sw.manager.model.StockWfcDiagnostics

object EnvironmentDiagnosticsEvaluator {
    fun permissionDenied(
        message: String = "请在 KernelSU 中授予 SoftVoWiFi root 权限",
    ): EnvironmentDiagnosticsUiState.PermissionDenied =
        EnvironmentDiagnosticsUiState.PermissionDenied(message)

    fun unavailable(
        message: String = "诊断命令不可用",
    ): EnvironmentDiagnosticsUiState.Unavailable =
        EnvironmentDiagnosticsUiState.Unavailable(message)

    fun failure(
        message: String = "无法读取环境诊断",
    ): EnvironmentDiagnosticsUiState.Failure =
        EnvironmentDiagnosticsUiState.Failure(message)

    fun fromOutput(output: String, stockWfc: StockWfcDiagnostics): EnvironmentDiagnosticsUiState {
        val commandFailure = EnvironmentDiagnosticsCodec.failure(output)
        if (commandFailure != null) {
            return when (commandFailure.code) {
                "USAGE" -> unavailable()
                else -> failure()
            }
        }
        return try {
            ready(EnvironmentDiagnosticsCodec.snapshot(output), stockWfc)
        } catch (_: EnvironmentDiagnosticsFormatException) {
            failure("诊断结果无法解析")
        }
    }

    fun ready(
        snapshot: EnvironmentDiagnosticsSnapshot,
        stockWfc: StockWfcDiagnostics,
    ): EnvironmentDiagnosticsUiState.Ready {
        val checks = listOf(
            moduleCheck(snapshot.module),
            rootCheck(snapshot.root),
            configCheck(snapshot.config),
            observerCheck(snapshot.observer),
            engineCheck(snapshot.engine),
            wfcCheck(stockWfc),
        )
        return EnvironmentDiagnosticsUiState.Ready(checks.maxOf { it.status }, checks, module = snapshot.module)
    }
}

private fun moduleCheck(module: ModuleDiagnostics): DiagnosticCheck {
    val meanings = activeMarkerMeanings(module.markers)
    val status = when {
        !module.installed -> DiagnosticCheckStatus.FAIL
        meanings.isNotEmpty() -> DiagnosticCheckStatus.WARN
        else -> DiagnosticCheckStatus.PASS
    }
    val detail = when (status) {
        DiagnosticCheckStatus.FAIL -> "未检测到 SoftVoWiFi 模块"
        DiagnosticCheckStatus.WARN -> "模块已安装，当前标记：${meanings.joinToString("、")}"
        DiagnosticCheckStatus.PASS -> module.version?.let { "模块已安装，版本 $it" } ?: "模块已安装"
    }
    return DiagnosticCheck(
        id = DiagnosticCheckId.MODULE,
        status = status,
        title = "模块",
        detail = detail,
        guidance = when (status) {
            DiagnosticCheckStatus.FAIL -> "请安装 SoftVoWiFi 模块后刷新。本页不会改写模块目录。"
            DiagnosticCheckStatus.WARN -> moduleGuidance(module.markers)
            DiagnosticCheckStatus.PASS -> null
        },
    )
}

private fun activeMarkerMeanings(markers: ModuleMarkers): List<String> = buildList {
    if (markers.disable) add("停用")
    if (markers.remove) add("待移除")
    if (markers.update) add("待更新")
}

private fun moduleGuidance(markers: ModuleMarkers): String = buildString {
    if (markers.disable || markers.remove) {
        append("请在 KernelSU 中处理停用或待移除标记后重启。")
    }
    if (markers.update) {
        if (isNotEmpty()) append(' ')
        append("待更新标记将在下次启动时生效。")
    }
    append("本页不会改写模块标记。")
}

private fun rootCheck(root: RootDiagnostics): DiagnosticCheck {
    val status = if (root.capable) DiagnosticCheckStatus.PASS else DiagnosticCheckStatus.WARN
    return DiagnosticCheck(
        id = DiagnosticCheckId.ROOT,
        status = status,
        title = "Root 权限",
        detail = if (root.capable) "已确认 Root 能力" else "当前会话未确认 Root 能力",
        guidance = if (root.capable) {
            null
        } else {
            "请在 KernelSU 中确认授权后刷新。本页不会申请或改写 Root 授权。"
        },
    )
}

private fun configCheck(config: ConfigDiagnostics): DiagnosticCheck {
    val status = if (config.count > 0) DiagnosticCheckStatus.PASS else DiagnosticCheckStatus.WARN
    return DiagnosticCheck(
        id = DiagnosticCheckId.CONFIG,
        status = status,
        title = "配置",
        detail = "配置数量 ${config.count}",
        guidance = if (status == DiagnosticCheckStatus.PASS) {
            null
        } else {
            "请放入已有 SIM 配置后刷新。本页不会创建或改写配置。"
        },
    )
}

private fun observerCheck(observer: ObserverDiagnostics): DiagnosticCheck {
    val present = observer.packagePid || observer.processRecord || observer.foreground
    val healthy = observer.packagePid && observer.processRecord && observer.foreground
    val status = when {
        !present -> DiagnosticCheckStatus.FAIL
        !healthy -> DiagnosticCheckStatus.WARN
        else -> DiagnosticCheckStatus.PASS
    }
    val detail = when (status) {
        DiagnosticCheckStatus.FAIL -> "未发现观测进程"
        DiagnosticCheckStatus.WARN -> "服务状态异常"
        DiagnosticCheckStatus.PASS -> "前台运行中"
    }
    return DiagnosticCheck(
        id = DiagnosticCheckId.OBSERVER,
        status = status,
        title = "观测服务",
        detail = detail,
        guidance = when (status) {
            DiagnosticCheckStatus.FAIL -> "请确认模块已启用并等待开机看门狗。本页不会改写服务。"
            DiagnosticCheckStatus.WARN -> "打开应用重试。\n仅执行检查。"
            DiagnosticCheckStatus.PASS -> null
        },
    )
}

private fun engineCheck(engine: EngineDiagnostics): DiagnosticCheck {
    val transitioning = engine.startingCount > 0 || engine.stoppingCount > 0
    val unstable = transitioning || engine.configMissingCount > 0
    val status = if (engine.instanceCount > 0 && !unstable) {
        DiagnosticCheckStatus.PASS
    } else {
        DiagnosticCheckStatus.WARN
    }
    return DiagnosticCheck(
        id = DiagnosticCheckId.ENGINE,
        status = status,
        title = "引擎",
        detail = if (status == DiagnosticCheckStatus.PASS) {
            healthyEngineCounts(engine)
        } else {
            "状态需关注：${engineCounts(engine)}"
        },
        guidance = when {
            status == DiagnosticCheckStatus.PASS -> null
            transitioning -> "请在过渡完成后刷新，或检查已有配置。本页不会启动或停止引擎。"
            else -> "请检查已有配置后刷新。本页不会启动或停止引擎。"
        },
    )
}

private fun healthyEngineCounts(engine: EngineDiagnostics): String = buildString {
    append("实例 ${engine.instanceCount}")
    if (engine.runningCount > 0) append(" · 运行 ${engine.runningCount}")
    if (engine.stoppedCount > 0) append(" · 停止 ${engine.stoppedCount}")
}

private fun engineCounts(engine: EngineDiagnostics): String = buildString {
    append("共 ${engine.instanceCount} 个实例")
    appendCount("运行", engine.runningCount)
    appendCount("停止", engine.stoppedCount)
    appendCount("启动中", engine.startingCount)
    appendCount("停止中", engine.stoppingCount)
    appendCount("缺配置", engine.configMissingCount)
}

private fun StringBuilder.appendCount(label: String, count: Int) {
    if (count > 0) {
        append('，')
        append(label)
        append(' ')
        append(count)
    }
}

// Stock WFC on a SIM registers IMS over Wi-Fi alongside SoftVoWiFi, so any enabled SIM is a warning.
private fun wfcCheck(wfc: StockWfcDiagnostics): DiagnosticCheck {
    val cards = (wfc as? StockWfcDiagnostics.Readable)?.cards?.sortedBy { it.slot }
    val enabled = cards.orEmpty().filter { it.enabled }
    val status = if (cards == null || enabled.isNotEmpty()) DiagnosticCheckStatus.WARN else DiagnosticCheckStatus.PASS
    val detail = when {
        cards == null -> "无法读取各 SIM 的原生 Wi-Fi Calling 设置"
        cards.isEmpty() -> "未发现活动 SIM"
        enabled.isNotEmpty() -> "${simLabels(enabled.map { it.slot })} 已开启"
        cards.size == 1 -> "${simLabels(cards.map { it.slot })} 未开启"
        else -> "${simLabels(cards.map { it.slot })} 均未开启"
    }
    return DiagnosticCheck(
        id = DiagnosticCheckId.WFC,
        status = status,
        title = "原生系统 Wi-Fi Calling",
        detail = detail,
        guidance = when {
            status == DiagnosticCheckStatus.PASS -> null
            cards == null -> "原生系统设置当前不可读。请刷新后重试。本页不会改写该设置。"
            else -> "原生 Wi-Fi Calling 与 SoftVoWiFi 冲突。请在系统设置中关闭对应 SIM 的 Wi-Fi Calling 后刷新。本页不会改写该设置。"
        },
    )
}

private fun simLabels(slots: List<Int>): String = slots.joinToString("、") { "SIM${it + 1}" }
