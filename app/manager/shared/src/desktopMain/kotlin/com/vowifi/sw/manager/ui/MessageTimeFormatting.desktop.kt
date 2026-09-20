package com.vowifi.sw.manager.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal actual fun messageDateSpec(epochMs: Long): MessageDateSpec {
    val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    return MessageDateSpec(date.toString(), date.format(DATE_LABEL))
}

internal actual fun messageTimestampLabel(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(TIME_LABEL)

private val DATE_LABEL = DateTimeFormatter.ofPattern("yyyy年M月d日")
private val TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm")
