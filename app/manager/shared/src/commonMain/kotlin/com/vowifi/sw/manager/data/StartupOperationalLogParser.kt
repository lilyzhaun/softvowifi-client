package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogMessage
import com.vowifi.sw.manager.model.OperationalLogOrigin
import com.vowifi.sw.manager.model.OperationalLogParseIssue
import com.vowifi.sw.manager.model.OperationalLogParseResult

/** Only the codec's identity-bound launcher context admits these fixed startup errors. */
internal class StartupOperationalLogParser {
    private val engineParser = EngineOperationalLogParser()

    fun parse(lines: List<String>, suffix: String): OperationalLogParseResult {
        val entries = mutableListOf<OperationalLogEntry>()
        val issues = mutableListOf<OperationalLogParseIssue>()
        lines.forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed
            if (line.any { it.isISOControl() }) {
                issues += OperationalLogParseIssue(index + 1)
                return@forEachIndexed
            }
            val standard = engineParser.parse(line).entries.singleOrNull()
            if (standard != null) {
                entries += standard.copy(origin = OperationalLogOrigin.STARTUP)
                return@forEachIndexed
            }
            val failure = when (line) {
                "错误: --iccid 必填" -> "缺少启动参数" to "缺少必需的启动参数。"
                else -> initializationFailure(line)?.let { "日志初始化失败" to "无法初始化日志：$it。" }
            }
            if (failure == null) {
                issues += OperationalLogParseIssue(index + 1)
            } else {
                // Never promote the dynamic stderr tail into safeDetail, even after root redaction.
                val detail = OperationalLogRedactor.normalizedSafeText(failure.second)
                entries += OperationalLogEntry(
                    timestamp = "时间未知",
                    level = OperationalLogLevel.ERROR,
                    kind = OperationalLogKind.ENGINE,
                    displayLabel = OperationalLogRedactor.displayLabel(suffix),
                    message = OperationalLogMessage.OPERATION_FAILED,
                    safeSummary = OperationalLogRedactor.boundedSafeSummary(failure.first),
                    safeDetail = OperationalLogRedactor.bounded(detail, 2048),
                    detailTruncated = detail.length > 2048,
                    origin = OperationalLogOrigin.STARTUP,
                )
            }
        }
        return OperationalLogParseResult(entries, issues)
    }

    private fun initializationFailure(line: String): String? {
        if (!line.startsWith(INITIALIZATION_PREFIX)) return null
        val tail = line.removePrefix(INITIALIZATION_PREFIX)
        return knownErrors.entries.firstOrNull { (error, _) ->
            pathPrefixes.any { prefix -> tail == prefix + error }
        }?.value
    }

    private companion object {
        const val INITIALIZATION_PREFIX = "错误: 初始化日志失败: "
        val pathPrefixes = listOf("", "open [path] ", "open [path]: ", "mkdir [path] ", "mkdir [path]: ")
        val knownErrors = mapOf(
            "permission denied" to "权限不足",
            "operation not permitted" to "操作不被允许",
            "no such file or directory" to "文件或目录不存在",
            "read-only file system" to "文件系统只读",
            "too many open files" to "打开文件过多",
            "no space left on device" to "存储空间不足",
        )
    }
}
