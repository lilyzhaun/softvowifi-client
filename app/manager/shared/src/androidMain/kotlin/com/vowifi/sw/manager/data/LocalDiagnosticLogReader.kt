package com.vowifi.sw.manager.data

import android.content.Context
import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogFile
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogMessage
import com.vowifi.sw.manager.model.OperationalLogOrigin
import com.vowifi.sw.manager.model.OperationalLogParseIssue
import com.vowifi.sw.manager.model.OperationalLogTail
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal class LocalDiagnosticLogReader(private val context: Context) {
    init { AndroidDiagnosticLog.start(context) }

    fun read(): OperationalLogGroupRead {
        val status = AndroidDiagnosticLog.status()
        if (status == null) return OperationalLogGroupRead(
            OperationalLogTail(ID, false, emptyList(), emptyList()), "全局诊断未启动，凭据存储尚不可用或原因未知",
        )
        val window = DiagnosticFiles.readWindow {
            context.filesDir?.toPath()?.let(DiagnosticFiles::directoryInAppStorage)
        }
        val issues = mutableListOf<OperationalLogParseIssue>()
        val entries = window.lines.mapIndexedNotNull { index, line ->
            val record = DiagnosticCodec.parse(line)
            // The file establishes App ownership. Body fields cannot impersonate a root component.
            if (record == null || record.event.component !in appComponents) {
                issues += OperationalLogParseIssue(index + 1, "诊断事件无法解析或版本/来源不受支持")
                null
            } else entry(record)
        }
        val problems = buildList {
            if (window.issue != null) add("全局诊断读取不完整（${window.issue.name.lowercase()}）")
            if (status.failure != null) add("诊断写入已停止（${status.failure.name.lowercase()}）")
            if (status.memory.dropped > 0 || status.writeDropped > 0) {
                add("当前进程诊断丢弃：内存/频率 ${status.memory.dropped}，写入 ${status.writeDropped}")
            }
        }
        return OperationalLogGroupRead(OperationalLogTail(ID, window.truncated, entries, issues),
            problems.takeIf { it.isNotEmpty() }?.joinToString("；"))
    }

    internal fun entry(record: DiagnosticRecord, root: Boolean = false): OperationalLogEntry {
        val event = record.event
        val summary = DiagnosticLabels.summary(event)
        val detail = buildString {
            append(summary); append("；事件 ${event.name.name.lowercase()}；UTC；启动 ${record.run}")
            append("；阶段 ${event.stage.name.lowercase()}；结果 ${event.result.name.lowercase()}；原因 ${event.reason.name.lowercase()}")
            append("；进程 ${record.pid}；单调毫秒 ${record.mono}")
            event.attempt?.let { append("；尝试 $it") }
            event.networkGeneration?.let { append("；网络代次 $it") }
            event.family?.let { append("；目标族 ${it.name.lowercase()}") }
            event.sourceFamily?.let { append("；源地址族 ${it.name.lowercase()}") }
            event.endpoint?.let { append("；本次读取端点假名 ${it.chunked(8).joinToString("-")}") }
            event.count?.let { append("；数量 $it") }
            event.accepted?.let { append("；保留 $it") }
            append("；合并 ${record.suppressed}（${record.first}–${record.last}）；丢弃 ${record.dropped}；淘汰 ${record.evicted}")
        }
        return OperationalLogEntry(
            time.format(Instant.ofEpochMilli(record.wall)),
            OperationalLogLevel.valueOf(event.level.name), OperationalLogKind.DIAGNOSTIC,
            event.slot?.let { "SIM ${it + 1}" } ?: if (root) "Root 诊断" else "全局诊断", OperationalLogMessage.EVENT,
            OperationalLogRedactor.boundedSafeSummary(summary),
            OperationalLogRedactor.normalizedSafeText(detail), origin = if (root) OperationalLogOrigin.ENGINE else OperationalLogOrigin.DIAGNOSTIC,
        )
    }

    companion object {
        const val ID = "app-diagnostics-v1"
        val FILE = OperationalLogFile(ID, "app.jsonl", "")
        private val appComponents = setOf(DiagnosticComponent.APP, DiagnosticComponent.SUBSCRIPTIONS,
            DiagnosticComponent.CONFIGURATION, DiagnosticComponent.UNDERLAY)
        private val time = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
    }
}
