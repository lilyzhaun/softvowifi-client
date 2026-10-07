package com.vowifi.sw.manager.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vowifi.sw.manager.data.MessageRemarkSaveResult
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.MessageHistoryLine

internal fun MessageHistoryLine.lineTitle(): String = simLabel?.let { sim ->
    label?.remark?.takeIf { it.isNotBlank() }?.let { "$sim · $it" } ?: sim
} ?: label?.let { it.remark.ifBlank { "历史线路 ${it.ordinal}" } } ?: "正在读取线路"

internal fun MessageHistoryLine.lineSummary(): String {
    val activity = when (active) {
        true -> "SIM 已启用"
        false -> "SIM 未启用"
        null -> "SIM 状态未知"
    }
    return "$conversationCount 个会话 · $activity · " +
        "${if (connected) "已连接" else "未连接"} · ${if (sendable) "可发送" else "暂不可发送"}"
}

internal fun filterMessageLines(lines: List<MessageHistoryLine>, query: String): List<MessageHistoryLine> {
    val term = query.trim()
    return lines.filter { it.lineTitle().contains(term, ignoreCase = true) ||
        it.label?.let { label -> "历史线路 ${label.ordinal}".contains(term, ignoreCase = true) } == true }
}

internal class MessageRemarkEditor(val owner: SimOwnerId, initial: String, val lineNumber: Long? = null, val simLabel: String? = null) {
    var input by mutableStateOf(initial)
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    suspend fun save(submit: suspend (SimOwnerId, String) -> MessageRemarkSaveResult): Boolean {
        if (busy) return false
        busy = true
        error = null
        return try {
            when (submit(owner, input)) {
                MessageRemarkSaveResult.Saved -> true
                MessageRemarkSaveResult.Invalid -> {
                    error = "备注最多40个字符，不能包含控制字符"
                    false
                }
                MessageRemarkSaveResult.Failed -> {
                    error = "无法确认备注已保存，输入已保留，请重试"
                    false
                }
            }
        } finally { busy = false }
    }
}
