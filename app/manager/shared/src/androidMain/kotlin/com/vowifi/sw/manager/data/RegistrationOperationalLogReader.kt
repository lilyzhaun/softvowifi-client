package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogFile
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogMessage
import com.vowifi.sw.manager.model.OperationalLogOrigin
import com.vowifi.sw.manager.model.OperationalLogParseIssue
import com.vowifi.sw.manager.model.OperationalLogTail
import com.vowifi.sw.manager.model.RegistrationEvent
import com.vowifi.sw.manager.model.RegistrationState
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.SYSTEM_REGISTRATION_LOG_ID
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Reuses the Activity-owned observer source; no second watcher or engine request. */
internal class RegistrationOperationalLogReader(private val source: ObserverLogSource) {
    suspend fun read(): OperationalLogGroupRead {
        val refreshed = source.refreshAndAwait()
        val state = source.state.value
        val snapshot = (state as? RegistrationUiState.Data)?.snapshot
        val entries = snapshot?.events.orEmpty().sortedBy { it.timestampMs }.map(::entry)
        val issues = (snapshot?.issues ?: (state as? RegistrationUiState.ParseError)?.issues).orEmpty()
            .map { OperationalLogParseIssue(it.lineNumber, "系统注册记录格式无效") }
        val issue = when {
            state is RegistrationUiState.ReadError || refreshed is RefreshResult.Failed -> "系统注册记录读取不完整，请刷新重试。"
            state == RegistrationUiState.Loading -> "系统注册记录尚未读取完成。"
            else -> null
        }
        return OperationalLogGroupRead(OperationalLogTail(SYSTEM_REGISTRATION_LOG_ID, false, entries, issues), issue)
    }

    internal fun entry(event: RegistrationEvent): OperationalLogEntry {
        val state = event.normalizedState
        val headline = when (state) {
            RegistrationState.REGISTERED -> "系统 IMS 已注册"
            RegistrationState.REGISTERING -> "系统 IMS 正在注册"
            RegistrationState.UNREGISTERED -> "系统 IMS 未注册"
            RegistrationState.UNKNOWN -> "系统 IMS 状态未知"
        }
        val network = when (event.transportType) { 1 -> "蜂窝网络"; 2 -> "Wi-Fi"; else -> null }
        val summary = when (state) {
            RegistrationState.REGISTERED -> network?.let { "系统报告已通过$it 注册 IMS。" } ?: "系统报告 IMS 已注册，未提供承载网络。"
            RegistrationState.REGISTERING -> network?.let { "系统正在通过$it 注册 IMS。" } ?: "系统正在注册 IMS，未提供承载网络。"
            RegistrationState.UNREGISTERED -> "系统报告 IMS 未注册；这是系统电话服务的观测记录。"
            RegistrationState.UNKNOWN -> "系统记录的状态尚无法识别。"
        }
        val slot = event.slot?.takeIf { it in 0..1 }
        val label = slot?.let { "SIM ${it + 1}" } ?: "槽位未知"
        val detail = buildString {
            append(summary)
            append("\n来源：Android 系统 IMS 回调；不代表软件引擎注册结果。")
            append("\n线路：$label；系统订阅编号（非 SIM 槽位）：${event.subId}")
            append("\n承载网络：${network ?: "未提供"}")
            event.reasonCode?.let { append("\n系统原因码：$it") }
            event.extraCode?.let { append("；附加码：$it") }
        }
        return OperationalLogEntry(
            timestamp = if (event.timestampMs in 0..9_999_999_999_999L) time.format(Instant.ofEpochMilli(event.timestampMs)) else "时间未知",
            level = OperationalLogLevel.INFO, kind = OperationalLogKind.IMS, displayLabel = label,
            message = when (state) {
                RegistrationState.REGISTERED -> OperationalLogMessage.IMS_REGISTERED
                RegistrationState.REGISTERING -> OperationalLogMessage.IMS_REGISTERING
                RegistrationState.UNREGISTERED -> OperationalLogMessage.IMS_UNREGISTERED
                RegistrationState.UNKNOWN -> OperationalLogMessage.UNAVAILABLE
            },
            safeSummary = summary, safeDetail = OperationalLogRedactor.normalizedSafeText(detail),
            origin = OperationalLogOrigin.OBSERVER, headline = headline, timeZoneLabel = "UTC",
        )
    }

    companion object {
        val FILE = OperationalLogFile(SYSTEM_REGISTRATION_LOG_ID, "ims-registration.jsonl", "")
        private val time = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
    }
}
