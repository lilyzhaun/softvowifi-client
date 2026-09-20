package com.vowifi.sw.manager.data

object IccidNormalizer {
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        var end = raw.length
        while (end > 0) {
            val ch = raw[end - 1]
            if (ch != 'F' && ch != 'f') break
            end -= 1
        }
        if (end !in MIN_ASCII_DIGITS..MAX_ASCII_DIGITS) return null
        for (index in 0 until end) {
            val ch = raw[index]
            if (ch !in '0'..'9') return null
        }
        return if (end == raw.length) raw else raw.substring(0, end)
    }

    override fun toString(): String = "IccidNormalizer"
}

private const val MIN_ASCII_DIGITS = 15
private const val MAX_ASCII_DIGITS = 22
