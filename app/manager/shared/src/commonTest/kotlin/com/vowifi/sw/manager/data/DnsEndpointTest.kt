package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DnsEndpointTest {
    @Test
    fun rendersIpv4AsHostPort53() {
        val endpoint = DnsEndpoint.v4("192.0.2.53")

        assertEquals("192.0.2.53:53", endpoint.render())
    }

    @Test
    fun rendersIpv6AsBracketedHostPort53() {
        val endpoint = DnsEndpoint.v6("2001:db8::53")

        assertEquals("[2001:db8::53]:53", endpoint.render())
    }

    @Test
    fun toStringOmitsRawEndpoint() {
        val dumped = DnsEndpoint.v4("192.0.2.53").toString() + DnsEndpoint.v6("2001:db8::53").toString()

        assertFalse(dumped.contains("192.0.2.53"))
        assertFalse(dumped.contains("2001:db8::53"))
        assertPrivate(dumped)
    }
}
