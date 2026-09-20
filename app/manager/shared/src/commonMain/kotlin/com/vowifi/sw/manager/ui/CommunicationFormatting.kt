package com.vowifi.sw.manager.ui

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
private const val MAX_PHONE_DIGITS = 20
private const val MAX_DURATION_SECONDS = 359_999L

internal fun formatCommunicationTime(
    timestampMs: Long,
    nowMs: Long,
    localDateLabel: (Long) -> String,
): String {
    if (timestampMs <= 0L || nowMs <= 0L || timestampMs > nowMs) return "时间未知"
    val elapsed = nowMs - timestampMs
    return when {
        elapsed < MINUTE_MS -> "刚刚"
        elapsed < HOUR_MS -> "${elapsed / MINUTE_MS} 分钟前"
        elapsed < DAY_MS -> "${elapsed / HOUR_MS} 小时前"
        else -> localDateLabel(timestampMs).let { label ->
            if (label.any(Char::isISOControl)) "时间未知" else boundedCommunicationText(label, "时间未知", 20)
        }
    }
}

internal fun privateUiPhoneDisplay(raw: String?): String {
    val value = raw ?: return UNKNOWN_PHONE
    if (value.equals("anonymous", ignoreCase = true)) return UNKNOWN_PHONE
    if (value.any(Char::isISOControl)) return UNKNOWN_PHONE
    val lower = value.lowercase()
    if (PHONE_PREFIXES.any(lower::startsWith)) return UNKNOWN_PHONE
    val digits = value.removePrefix("+")
    if (digits.isEmpty() || digits.length > MAX_PHONE_DIGITS || digits.any { it !in '0'..'9' }) return UNKNOWN_PHONE
    return value
}

internal fun boundedCommunicationText(raw: String?, fallback: String, limit: Int): String {
    val normalized = raw
        ?.map { if (it.isISOControl()) ' ' else it }
        ?.joinToString("")
        ?.trim()
        ?.replace(WHITESPACE, " ")
        ?.takeIf(String::isNotEmpty)
        ?: return fallback
    val lower = normalized.lowercase()
    if (SENSITIVE_MARKERS.any(lower::contains) || LONG_DIGITS.containsMatchIn(normalized)) return fallback
    return if (normalized.length <= limit) normalized else normalized.take(limit - 1) + "…"
}

internal fun formatCallDuration(durationSec: Long?): String {
    val seconds = durationSec?.takeIf { it in 0..MAX_DURATION_SECONDS } ?: return "时长未知"
    val hours = seconds / 3_600
    val minutes = seconds % 3_600 / 60
    val remainder = seconds % 60
    return when {
        hours > 0 -> "${hours} 小时 ${minutes} 分"
        minutes > 0 -> "${minutes} 分 ${remainder} 秒"
        else -> "${remainder} 秒"
    }
}

private const val UNKNOWN_PHONE = "未知号码"
private val WHITESPACE = Regex("\\s+")
private val LONG_DIGITS = Regex("\\d{13,}")
private val PHONE_PREFIXES = listOf("raw:", "sip:", "sips:", "tel:")
private val SENSITIVE_MARKERS = listOf(
    "owner=",
    "owner:",
    "correlation=",
    "correlation:",
    "token=",
    "token:",
    "call token",
    "call_token",
    "calltoken",
    "call-id",
    "sip:",
    "sips:",
    "raw:",
    "/data/",
    "/proc/",
)
