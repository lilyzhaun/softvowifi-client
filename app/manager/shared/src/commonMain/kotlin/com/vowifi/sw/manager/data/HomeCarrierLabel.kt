package com.vowifi.sw.manager.data

internal fun homeCarrierLabel(raw: String?): String {
    val fallback = "归属运营商未知"
    val cleaned = raw?.filterNot {
        it.code in 0..31 || it.code in 127..159 || it == '\u061C' ||
            it in '\u200E'..'\u200F' || it in '\u2028'..'\u202E' ||
            it in '\u2066'..'\u2069' || it == '\uFEFF'
    }?.trim().orEmpty()
    if (cleaned.isEmpty() || containsIdentityDigits(cleaned) ||
        !OperationalLogRedactor.isPrivateUiText(cleaned) ||
        HOME_DECIMAL_LABEL.matches(cleaned) ||
        listOf("iccid", "imsi", "msisdn", "subid", "token", "/data/", "/proc/", "sip:", "raw:").any(cleaned.lowercase()::contains)
    ) return fallback
    var end = 0
    var characters = 0
    while (end < cleaned.length && characters < 32) {
        val next = cleaned[end]
        if (next.isLowSurrogate()) return fallback
        if (next.isHighSurrogate()) {
            if (end + 1 == cleaned.length || !cleaned[end + 1].isLowSurrogate()) return fallback
            end += 2
        } else {
            end++
        }
        characters++
    }
    return cleaned.substring(0, end)
}

private val HOME_DECIMAL_LABEL = Regex("\\p{Nd}{7,}")
