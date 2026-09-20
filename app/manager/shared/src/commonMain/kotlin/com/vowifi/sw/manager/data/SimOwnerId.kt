package com.vowifi.sw.manager.data

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class SimOwnerId private constructor(private val wireValue: String) {
    internal fun wire(): String = wireValue

    override fun equals(other: Any?): Boolean = other is SimOwnerId && wireValue == other.wireValue

    override fun hashCode(): Int = wireValue.hashCode()

    override fun toString(): String = "SimOwnerId"

    companion object {
        const val PREFIX = "sim-owner-v1:"
        private val BODY = Regex("^[A-Za-z0-9_-]{43}$")

        fun parse(wire: String): SimOwnerId? {
            if (!wire.startsWith(PREFIX)) return null
            val body = wire.substring(PREFIX.length)
            if (!BODY.matches(body)) return null
            return SimOwnerId(wire)
        }

        @OptIn(ExperimentalEncodingApi::class)
        fun fromHmacSha256(mac: ByteArray): SimOwnerId? {
            if (mac.size != 32) return null
            val body = Base64.UrlSafe.encode(mac).trimEnd('=')
            return parse("$PREFIX$body")
        }
    }
}
