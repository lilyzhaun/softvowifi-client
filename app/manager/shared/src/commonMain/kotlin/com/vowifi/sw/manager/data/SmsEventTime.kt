package com.vowifi.sw.manager.data

internal object SmsEventTime {
    fun millis(timestampSeconds: Long?, nowMs: Long): Long {
        if (timestampSeconds == null || timestampSeconds <= 0L) return nowMs
        if (timestampSeconds > Long.MAX_VALUE / 1_000L) return nowMs
        return timestampSeconds * 1_000L
    }
}
