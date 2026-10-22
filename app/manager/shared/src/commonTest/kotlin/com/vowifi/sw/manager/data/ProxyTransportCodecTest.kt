package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ProxyTransportCodecTest {
    @Test
    fun explicitInterfaceAndSystemRouteRoundTripWithoutRenamingLegacyModes() {
        for (wire in listOf("wifi", "sim1", "sim2", "auto", "iface:Meta", "iface:sim1", "route:system")) {
            assertEquals(wire, transportWire(transportOf(wire)))
        }
    }
}
