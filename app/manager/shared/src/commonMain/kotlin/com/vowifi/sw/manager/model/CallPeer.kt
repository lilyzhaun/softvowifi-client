package com.vowifi.sw.manager.model

class CallPeer private constructor(private val value: String) {
    fun text(): String = value

    override fun equals(other: Any?): Boolean = other is CallPeer && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "CallPeer"

    companion object {
        fun parse(raw: String): CallPeer? {
            val lower = raw.lowercase()
            if (lower.startsWith("raw:") ||
                lower.startsWith("sip:") ||
                lower.startsWith("sips:") ||
                lower.startsWith("tel:")
            ) {
                return null
            }
            if (raw.any(Char::isISOControl)) return null
            val start = if (raw.startsWith("+")) 1 else 0
            val digits = raw.substring(start)
            if (digits.isEmpty() || digits.length > 20) return null
            for (ch in digits) {
                if (ch !in '0'..'9') return null
            }
            return CallPeer(raw)
        }
    }
}
