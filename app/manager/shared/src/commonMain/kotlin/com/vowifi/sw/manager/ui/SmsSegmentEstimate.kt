package com.vowifi.sw.manager.ui

internal enum class SmsEncoding { GSM7, UTF16 }

internal data class SmsSegmentEstimate(val encoding: SmsEncoding, val units: Int, val segments: Int, val approximate: Boolean)

internal fun estimateSmsSegments(body: String): SmsSegmentEstimate? {
    if (body.isBlank()) return null
    val gsm = body.all { it in GSM_BASIC || it in GSM_EXTENSION }
    val widths = mutableListOf<Int>()
    var approximate = false
    var index = 0
    while (index < body.length) {
        val character = body[index]
        if (gsm) widths.add(if (character in GSM_EXTENSION) 2 else 1)
        else if (character.isHighSurrogate() && index + 1 < body.length && body[index + 1].isLowSurrogate()) {
            widths.add(2)
            index++
        } else {
            widths.add(1)
            if (character.isSurrogate()) approximate = true
        }
        index++
    }
    val units = widths.sum()
    val single = if (gsm) 160 else 70
    val capacity = if (gsm) 153 else 67
    var segments = 1
    if (units > single) {
        var used = 0
        for (width in widths) {
            if (used + width > capacity) { segments++; used = 0 }
            used += width
        }
    }
    return SmsSegmentEstimate(if (gsm) SmsEncoding.GSM7 else SmsEncoding.UTF16, units, segments, approximate)
}

private const val GSM_BASIC = "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
private const val GSM_EXTENSION = "\u000c^{}\\[~]|€"
