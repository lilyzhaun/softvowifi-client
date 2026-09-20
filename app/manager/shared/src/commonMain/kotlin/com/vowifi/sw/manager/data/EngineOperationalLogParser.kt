package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogMessage
import com.vowifi.sw.manager.model.OperationalLogParseIssue
import com.vowifi.sw.manager.model.OperationalLogParseResult

class EngineOperationalLogParser {
    fun parse(content: String): OperationalLogParseResult {
        val entries = mutableListOf<OperationalLogEntry>()
        val issues = mutableListOf<OperationalLogParseIssue>()
        content.lineSequence().forEachIndexed { index, rawLine ->
            if (rawLine.isBlank()) return@forEachIndexed
            val entry = parseLine(rawLine)
            if (entry == null) {
                issues += OperationalLogParseIssue(index + 1)
            } else {
                entries += entry
            }
        }
        return OperationalLogParseResult(entries, issues)
    }

    internal fun messageLeaks(line: String): Boolean {
        val match = linePattern.matchEntire(line) ?: return false
        return OperationalLogRedactor.changesMessage(match.groupValues[5])
    }

    private fun parseLine(line: String): OperationalLogEntry? {
        val match = linePattern.matchEntire(line) ?: return null
        val level = when (match.groupValues[2]) {
            "DEBUG", "INFO" -> OperationalLogLevel.INFO
            "WARN" -> OperationalLogLevel.WARN
            "ERROR" -> OperationalLogLevel.ERROR
            else -> return null
        }
        val layer = match.groupValues[3].lowercase()
        val rawMessage = match.groupValues[5]
        val safeText = OperationalLogRedactor.normalizedSafeText(rawMessage)
        val safeSummary = OperationalLogRedactor.bounded(safeText, OPERATIONAL_LOG_SAFE_SUMMARY_MAX_LENGTH)
        val message = catalog(safeSummary, level)
        val kind = when (message) {
            OperationalLogMessage.TUNNEL_CONNECTING,
            OperationalLogMessage.TUNNEL_CONNECTED,
            OperationalLogMessage.TUNNEL_DISCONNECTED,
            -> OperationalLogKind.TUNNEL

            OperationalLogMessage.IMS_REGISTERING,
            OperationalLogMessage.IMS_REGISTERED,
            OperationalLogMessage.IMS_UNREGISTERED,
            -> OperationalLogKind.IMS

            OperationalLogMessage.ENGINE_STARTED,
            OperationalLogMessage.ENGINE_STOPPED,
            -> OperationalLogKind.ENGINE

            OperationalLogMessage.NEEDS_ATTENTION,
            OperationalLogMessage.OPERATION_FAILED,
            OperationalLogMessage.EVENT,
            OperationalLogMessage.UNAVAILABLE,
            -> when (layer) {
                "tunnel" -> OperationalLogKind.TUNNEL
                "ims" -> OperationalLogKind.IMS
                else -> OperationalLogKind.ENGINE
            }
        }
        return OperationalLogEntry(
            timestamp = match.groupValues[1],
            level = level,
            kind = kind,
            displayLabel = OperationalLogRedactor.displayLabel(match.groupValues[4]),
            message = message,
            safeSummary = safeSummary,
            safeDetail = OperationalLogRedactor.bounded(safeText, 2048),
            detailTruncated = safeText.length > 2048,
        )
    }

    private fun catalog(
        safeSummary: String,
        level: OperationalLogLevel,
    ): OperationalLogMessage {
        val normalized = safeSummary.lowercase().trim().replace("：", ":")
        return when {
            normalized == "engine started" || normalized.startsWith("引擎启动") ->
                OperationalLogMessage.ENGINE_STARTED
            normalized == "engine stopped" || normalized == "引擎退出" || normalized.startsWith("引擎退出") ->
                OperationalLogMessage.ENGINE_STOPPED
            normalized == "tunnel connecting" || normalized.startsWith("隧道状态: connecting") ->
                OperationalLogMessage.TUNNEL_CONNECTING
            normalized == "tunnel connected" || normalized.startsWith("隧道状态: connected") ->
                OperationalLogMessage.TUNNEL_CONNECTED
            normalized == "tunnel disconnected" || normalized.startsWith("隧道状态: disconnected") ->
                OperationalLogMessage.TUNNEL_DISCONNECTED
            normalized == "ims registering" || normalized.startsWith("ims 注册启动") || normalized.startsWith("ims 状态: registering") ->
                OperationalLogMessage.IMS_REGISTERING
            normalized == "ims unregistered" || normalized.startsWith("ims 状态: unregistered") ->
                OperationalLogMessage.IMS_UNREGISTERED
            normalized == "ims registered" || normalized.startsWith("ims 状态: registered") || normalized.startsWith("ims 注册成功") ->
                OperationalLogMessage.IMS_REGISTERED
            else -> when (level) {
                OperationalLogLevel.ERROR -> OperationalLogMessage.OPERATION_FAILED
                OperationalLogLevel.WARN -> OperationalLogMessage.NEEDS_ATTENTION
                OperationalLogLevel.INFO -> OperationalLogMessage.EVENT
            }
        }
    }

    private companion object {
        val linePattern = Regex(
            """^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[([A-Z]+)] \[([^]]+)] \[([^]]+)] (.*)$""",
        )
    }
}
