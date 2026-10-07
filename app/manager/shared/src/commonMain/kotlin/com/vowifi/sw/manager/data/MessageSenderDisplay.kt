package com.vowifi.sw.manager.data

// SMS originators may be alphanumeric. Display does not authorize a reply address.
internal fun messageSenderDisplay(label: String?, fallback: String = "未知号码"): String {
    val clean = label?.filterNot {
        it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069'
    }?.trim().orEmpty()
    if (clean.isEmpty() || clean.equals("anonymous", ignoreCase = true) ||
        listOf("sip:", "sips:", "raw:", "tel:").any { clean.startsWith(it, ignoreCase = true) } ||
        clean.contains('@') || clean.contains("://")) return fallback
    var end = 0
    repeat(64) {
        if (end < clean.length) {
            end += if (clean[end].isHighSurrogate() && end + 1 < clean.length && clean[end + 1].isLowSurrogate()) 2 else 1
        }
    }
    return clean.substring(0, end)
}
