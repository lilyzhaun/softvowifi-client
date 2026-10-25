package com.vowifi.sw.manager.data

/** Presentation comes from validated event fields, never from parsing rendered text. */
internal object DiagnosticPresentation {
    fun isKey(event: DiagnosticEvent): Boolean =
        event.level != DiagnosticLevel.INFO ||
            event.result in setOf(DiagnosticResult.FAILURE, DiagnosticResult.UNAVAILABLE, DiagnosticResult.CANCELLED) ||
            event.name in setOf(DiagnosticName.REJECTED, DiagnosticName.RECOVERED,
                DiagnosticName.PROCESS_STARTED, DiagnosticName.PROCESS_STOPPING) ||
            event.stage in setOf(DiagnosticStage.CONNECT, DiagnosticStage.STOP,
                DiagnosticStage.CONFIG_CREATE, DiagnosticStage.CONFIG_APPLY, DiagnosticStage.CONFIG_REFRESH) ||
            event.component == DiagnosticComponent.UNDERLAY && event.stage == DiagnosticStage.SELECT

    fun title(event: DiagnosticEvent): String {
        if (event.reason == DiagnosticReason.NOT_ACTIVE) return "配置对应的 SIM 未启用"
        if (event.name == DiagnosticName.PROCESS_STARTED) return if (event.component == DiagnosticComponent.APP)
            "应用已启动" else "引擎已启动"
        if (event.name == DiagnosticName.PROCESS_STOPPING) return if (event.component == DiagnosticComponent.APP)
            "应用正在关闭" else "引擎正在停止"
        val copy = copy(event)
        return when (event.result) {
            DiagnosticResult.STARTED -> copy.started
            DiagnosticResult.SUCCESS -> copy.succeeded
            DiagnosticResult.FAILURE, DiagnosticResult.UNAVAILABLE -> copy.failed
            DiagnosticResult.CANCELLED -> if (event.stage == DiagnosticStage.CONNECT) "连接已取消" else "操作已取消"
            DiagnosticResult.STOPPED -> if (event.component == DiagnosticComponent.TUNNEL) "VoWiFi 隧道已停止" else "操作已停止"
            DiagnosticResult.OBSERVED -> when (event.stage) {
                DiagnosticStage.ENUMERATE -> "已读取 SIM 列表"
                DiagnosticStage.LISTENER -> "收到 SIM 状态更新"
                else -> "已记录运行步骤"
            }
        }
    }

    fun summary(event: DiagnosticEvent): String {
        if (event.reason == DiagnosticReason.NOT_ACTIVE) return "这份配置对应的 SIM 目前未在系统中启用。"
        if (event.result == DiagnosticResult.SUCCESS) {
            if (event.stage == DiagnosticStage.CONFIG_MATCH) return event.slot?.let {
                "已找到 SIM ${it + 1} 的配置。"
            } ?: "已找到对应的线路配置。"
            if (event.stage == DiagnosticStage.WRITE) {
                val amount = event.count?.let { "$it 字节" } ?: "数据"
                val result = if (event.component == DiagnosticComponent.DNS) "解析" else "对端处理"
                return "已将 $amount 交给系统发送；尚未确认${result}结果。"
            }
            if (event.stage == DiagnosticStage.RESPONSE) {
                val amount = event.count?.let { "$it 字节" } ?: "响应数据"
                return if (event.component == DiagnosticComponent.DNS) "收到 $amount；尚未确认解析结果。"
                else "收到 $amount；连接是否完成请查看后续连接结果。"
            }
            if (event.component == DiagnosticComponent.TUNNEL && event.stage == DiagnosticStage.CONNECT)
                return "用于软件 VoWiFi 的安全隧道已建立。IMS 注册状态另行记录。"
        }
        if (event.stage == DiagnosticStage.ENUMERATE && event.count != null) {
            val kept = event.accepted?.let { "，其中 $it 条可用于后续匹配" }.orEmpty()
            return "系统返回 ${event.count} 条 SIM 信息$kept。"
        }
        if (event.reason != DiagnosticReason.NONE) return when (event.reason) {
            DiagnosticReason.ROOT_DENIED -> "尚未获得 Root 授权，请在 Root 管理器中允许本应用。"
            DiagnosticReason.PERMISSION_DENIED -> "当前权限不足，无法完成这一步。"
            DiagnosticReason.USER_STOP -> "本次操作由用户停止。"
            DiagnosticReason.IDENTITY_MISMATCH -> "系统读取的 SIM 与当前配置不一致，请重新读取 SIM 信息。"
            DiagnosticReason.UNCONFIRMED -> "暂时无法确认操作结果，请刷新查看。"
            DiagnosticReason.UNKNOWN, DiagnosticReason.CANCEL_UNKNOWN -> "日志未提供具体原因。"
            else -> "${DiagnosticLabels.reason(event.reason)}。"
        }
        return "${title(event)}。"
    }

    private data class Copy(val started: String, val succeeded: String, val failed: String)

    private fun copy(event: DiagnosticEvent): Copy = when (event.stage) {
        DiagnosticStage.CONFIG_MATCH -> Copy("正在查找 SIM 对应配置", "已找到对应配置", "未能匹配 SIM 配置")
        DiagnosticStage.CONFIG_CREATE -> Copy("正在创建线路配置", "线路配置已创建", "线路配置创建失败")
        DiagnosticStage.CONFIG_APPLY -> Copy("正在保存配置", "配置已保存", "配置保存失败")
        DiagnosticStage.CONFIG_REFRESH -> Copy("正在更新 SIM 信息", "SIM 信息已更新", "SIM 信息更新失败")
        DiagnosticStage.ENUMERATE -> Copy("正在读取 SIM 列表", "已读取 SIM 列表", "无法读取 SIM 列表")
        DiagnosticStage.IDENTITY, DiagnosticStage.NORMALIZE, DiagnosticStage.STRICT_VERIFY ->
            Copy("正在核对 SIM 信息", "SIM 信息核对完成", "SIM 信息核对失败")
        DiagnosticStage.PERMISSION -> Copy("正在检查读取权限", "读取权限已确认", "读取权限不足")
        DiagnosticStage.LISTENER -> Copy("正在监听 SIM 变化", "SIM 变化监听已开启", "无法监听 SIM 变化")
        DiagnosticStage.PUBLISH -> Copy("正在更新连接网络信息", "连接网络信息已更新", "连接网络信息更新失败")
        DiagnosticStage.SNAPSHOT -> Copy("正在读取连接网络信息", "已读取连接网络信息", "无法读取连接网络信息")
        DiagnosticStage.DISCOVER -> Copy("正在查找可用网络", "可用网络检查完成", "可用网络检查失败")
        DiagnosticStage.SELECT -> Copy("正在选择连接网络", "已选定连接网络", "无法选择连接网络")
        DiagnosticStage.SOURCE -> Copy("正在选择发包地址", "已选定发包地址", "无法选择发包地址")
        DiagnosticStage.CONNECT -> if (event.component == DiagnosticComponent.TUNNEL)
            Copy("正在建立 VoWiFi 隧道", "VoWiFi 隧道已建立", "VoWiFi 隧道连接失败")
            else Copy("正在建立连接", "连接步骤已完成", "连接步骤失败")
        DiagnosticStage.STOP -> Copy("正在停止运行", "已停止运行", "停止操作未完成")
        DiagnosticStage.DNS -> Copy("正在执行 DNS 查询", "DNS 查询步骤已完成", "DNS 查询失败")
        DiagnosticStage.SOCKET -> if (event.component == DiagnosticComponent.DNS)
            Copy("正在打开 DNS 通信通道", "DNS 通信通道已打开", "无法打开 DNS 通信通道")
            else Copy("正在打开连接通道", "连接通道已打开", "无法打开连接通道")
        DiagnosticStage.SOCKET_CONTROL -> if (event.component == DiagnosticComponent.DNS)
            Copy("正在设置 DNS 通信参数", "DNS 通信参数已设置", "DNS 通信参数设置失败")
            else Copy("正在设置连接通信参数", "连接通信参数已设置", "连接通信参数设置失败")
        DiagnosticStage.WRITE -> if (event.component == DiagnosticComponent.DNS)
            Copy("正在提交 DNS 查询数据", "DNS 查询已交给系统发送", "DNS 查询数据发送失败")
            else Copy("正在提交连接数据", "连接数据已交给系统发送", "连接数据发送失败")
        DiagnosticStage.RESPONSE -> if (event.component == DiagnosticComponent.DNS)
            Copy("正在等待 DNS 响应", "收到 DNS 响应数据", "DNS 响应读取失败")
            else Copy("正在等待对端数据", "收到对端数据", "对端数据读取失败")
        DiagnosticStage.PROCESS -> Copy("进程正在启动", "进程启动完成", "进程启动失败")
        DiagnosticStage.NOTIFICATION -> Copy("正在执行通知步骤", "通知步骤已返回", "通知步骤异常")
    }
}
