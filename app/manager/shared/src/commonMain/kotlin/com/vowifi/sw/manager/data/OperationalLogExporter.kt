package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogMessage
import com.vowifi.sw.manager.model.operationalLogSafeSummary

object OperationalLogExporter {
    fun utf8(entries: List<OperationalLogEntry>): String = buildString {
        append(HEADER)
        entries.forEach { entry ->
            append('\n')
            append(line(entry))
        }
    }

    private fun line(entry: OperationalLogEntry): String = OperationalLogRedactor.redact(
        listOf(
            entry.timestamp.takeIf(timestamp::matches) ?: "时间不可用",
            levelText(entry.level),
            kindText(entry.kind),
            if (entry.kind == OperationalLogKind.DIAGNOSTIC) {
                entry.displayLabel.takeIf { it == "SIM 1" || it == "SIM 2" } ?: "全局诊断"
            } else OperationalLogRedactor.displayLabel(entry.displayLabel),
            messageText(entry.message),
            operationalLogSafeSummary(entry),
        ).joinToString("\t"),
    )

    private fun levelText(level: OperationalLogLevel): String = when (level) {
        OperationalLogLevel.INFO -> "信息"
        OperationalLogLevel.WARN -> "告警"
        OperationalLogLevel.ERROR -> "错误"
    }

    private fun kindText(kind: OperationalLogKind): String = when (kind) {
        OperationalLogKind.DIAGNOSTIC -> "诊断"
        OperationalLogKind.ENGINE -> "引擎"
        OperationalLogKind.TUNNEL -> "隧道"
        OperationalLogKind.IMS -> "IMS"
    }

    private fun messageText(message: OperationalLogMessage): String = when (message) {
        OperationalLogMessage.ENGINE_STARTED -> "引擎已启动"
        OperationalLogMessage.ENGINE_STOPPED -> "引擎已停止"
        OperationalLogMessage.TUNNEL_CONNECTING -> "正在建立隧道"
        OperationalLogMessage.TUNNEL_CONNECTED -> "隧道已连接"
        OperationalLogMessage.TUNNEL_DISCONNECTED -> "隧道已断开"
        OperationalLogMessage.IMS_REGISTERING -> "IMS 正在注册"
        OperationalLogMessage.IMS_REGISTERED -> "IMS 已注册"
        OperationalLogMessage.IMS_UNREGISTERED -> "IMS 未注册"
        OperationalLogMessage.NEEDS_ATTENTION -> "操作需要注意"
        OperationalLogMessage.OPERATION_FAILED -> "操作未完成"
        OperationalLogMessage.EVENT -> "运行事件"
        OperationalLogMessage.UNAVAILABLE -> "未分类事件"
    }

    private const val HEADER = "SoftVoWiFi 运行日志"
    private val timestamp = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""")
}
