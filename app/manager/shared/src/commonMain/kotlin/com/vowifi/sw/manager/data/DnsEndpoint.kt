package com.vowifi.sw.manager.data

internal class DnsEndpoint private constructor(
    private val host: String,
    private val ipv6: Boolean,
) {
    fun render(): String = if (ipv6) "[$host]:$PORT" else "$host:$PORT"

    override fun toString(): String = "DnsEndpoint"

    companion object {
        fun v4(host: String): DnsEndpoint = DnsEndpoint(host, ipv6 = false)

        fun v6(host: String): DnsEndpoint = DnsEndpoint(host, ipv6 = true)
    }
}

private const val PORT = 53
