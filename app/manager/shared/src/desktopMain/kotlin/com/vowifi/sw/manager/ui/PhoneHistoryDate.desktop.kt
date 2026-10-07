package com.vowifi.sw.manager.ui

import java.time.Instant
import java.time.ZoneId

internal actual fun phoneHistoryDateLabel(epochMs: Long, nowMs: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (day) { today -> "今天"; today.minusDays(1) -> "昨天"; else -> messageDateSpec(epochMs).label }
}
