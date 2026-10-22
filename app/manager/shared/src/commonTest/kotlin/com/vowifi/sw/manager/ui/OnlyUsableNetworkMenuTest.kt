package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.ConfigurationNetworkInterface
import com.vowifi.sw.manager.model.ConfigurationNetworkOutlets
import com.vowifi.sw.manager.model.ConfigurationTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnlyUsableNetworkMenuTest {
    @Test fun inactiveUnaddressedAndLoopbackInterfacesAreNotSelectable() {
        val outlets = ConfigurationNetworkOutlets(listOf(
            ConfigurationNetworkInterface("wlan0", true, true, "interface"),
            ConfigurationNetworkInterface("Meta", true, true, "tun"),
            ConfigurationNetworkInterface("tun0", false, true, "tun"),
            ConfigurationNetworkInterface("dummy0", true, false, "interface"),
            ConfigurationNetworkInterface("lo", true, true, "loopback"),
        ), supported = true)
        assertEquals(listOf("wifi", "sim1", "sim2", "iface:Meta", "iface:wlan0", "route:system", "auto"),
            configurationNetworkChoices(outlets).map { it.transport.wireValue })
    }

    @Test fun failedDirectoryDoesNotAdvertiseCachedInterfacesAsCurrentlyAvailable() {
        val outlets = ConfigurationNetworkOutlets(listOf(ConfigurationNetworkInterface("Meta", true, true, "tun")),
            supported = true, issue = "读取失败")
        assertEquals(listOf("wifi", "sim1", "sim2", "auto"),
            configurationNetworkChoices(outlets).map { it.transport.wireValue })
    }

    @Test fun unavailableSavedInterfaceIsPreservedButNeverInsertedIntoChoices() {
        val saved = ConfigurationTransport.Interface("tun0")
        val outlets = ConfigurationNetworkOutlets(listOf(ConfigurationNetworkInterface("tun0", false, true, "tun")), true)
        assertFalse(configurationNetworkChoices(outlets).any { it.transport == saved })
        assertTrue(configurationNetworkSummary(saved, outlets).contains("不会自动改选"))
        assertEquals("iface:tun0", saved.wireValue)
    }
}
