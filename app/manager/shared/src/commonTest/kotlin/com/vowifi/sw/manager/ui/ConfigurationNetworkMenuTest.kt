package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.NetworkOutletsCodec
import com.vowifi.sw.manager.data.ConfigurationEvaluator
import com.vowifi.sw.manager.data.transportOf
import com.vowifi.sw.manager.model.ConfigurationNetworkInterface
import com.vowifi.sw.manager.model.ConfigurationNetworkOutlets
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigurationNetworkMenuTest {
    @Test fun saveAndFailureProjectionKeepTheReadOnlyOutletInventory() {
        val instance = ConfigurationInstance(ConfigurationInstanceId("sim-test"), "SIM 1", ConfigurationLifecycle.STOPPED,
            ConfigurationTransport.WIFI, "", "", "", "", "epdg.example.test", "ims.example.test", "001", "01")
        val outlets = ConfigurationNetworkOutlets(listOf(ConfigurationNetworkInterface("Meta", true, true, "tun")), true)
        val state = ConfigurationUiState.Ready(listOf(instance), networkOutlets = outlets)
        assertEquals(outlets, (ConfigurationEvaluator.readyAfterApply(state) as ConfigurationUiState.Ready).networkOutlets)
        assertEquals(outlets, (ConfigurationEvaluator.preserveAfterAutoFailure(state, ConfigurationUiState.Failure("读取失败")) as ConfigurationUiState.Ready).networkOutlets)
    }

    @Test fun wifiSim1Sim2StayPinnedAboveAllInterfacesAndLegacyAuto() {
        val outlets = ConfigurationNetworkOutlets(listOf(
            ConfigurationNetworkInterface("sim1", true, true, "tun"),
            ConfigurationNetworkInterface("Meta", true, true, "tun"),
            ConfigurationNetworkInterface("wlan0", true, true, "interface"),
        ), supported = true)
        val choices = configurationNetworkChoices(outlets)
        assertEquals(listOf(ConfigurationTransport.WIFI, ConfigurationTransport.SIM1, ConfigurationTransport.SIM2),
            choices.take(3).map { it.transport })
        assertEquals(listOf("iface:Meta", "iface:sim1", "iface:wlan0", "route:system", "auto"),
            choices.drop(3).map { it.transport.wireValue })
        assertTrue(choices.single { it.transport.wireValue == "iface:sim1" }.label.contains("UP"))
        assertEquals("sim1", ConfigurationTransport.SIM1.wireValue)
    }

    @Test fun dynamicInterfaceLabelsShowEngineAddressesForIdentification() {
        val outlets = NetworkOutletsCodec.decode("""{"ok":true,"version":3,"interfaces":[
            {"name":"Meta","up":true,"hasAddress":true,"kind":"tun","addresses":["198.18.0.1"]},
            {"name":"wlan0","up":true,"hasAddress":true,"kind":"interface","addresses":["2001:db8::5","192.0.2.5","192.0.2.6"]}]}""")
        assertTrue(outlets.supported)
        assertEquals(listOf("192.0.2.5", "2001:db8::5"), outlets.interfaces.single { it.name == "wlan0" }.addresses)
        val labels = configurationNetworkChoices(outlets).associate { it.transport.wireValue to it.label }
        assertEquals("wlan0 · UP · 192.0.2.5 / 2001:db8::5", labels["iface:wlan0"])
        assertEquals("Meta · UP · 198.18.0.1", labels["iface:Meta"])
        assertEquals(listOf("Wi-Fi", "SIM 1 数据", "SIM 2 数据"), configurationNetworkChoices(outlets).take(3).map { it.label })
        assertEquals("系统策略路由（透明代理）", labels["route:system"])
        assertEquals("自动", labels["auto"])
        val legacy = NetworkOutletsCodec.decode("""{"ok":true,"version":2,"interfaces":[{"name":"Meta","up":true,"hasAddress":true,"kind":"tun"}]}""")
        assertEquals("Meta · UP", configurationNetworkChoices(legacy).single { it.transport.wireValue == "iface:Meta" }.label)
    }

    @Test fun replacingInventoryRemovesLostInterfaceWithoutChangingSavedSelector() {
        val saved = transportOf("iface:Meta")
        val before = ConfigurationNetworkOutlets(listOf(ConfigurationNetworkInterface("Meta", true, true, "tun")), true)
        assertTrue(configurationNetworkChoices(before).any { it.transport == saved })
        val after = before.copy(interfaces = emptyList())
        assertFalse(configurationNetworkChoices(after).any { it.transport == saved })
        assertTrue(configurationNetworkSummary(saved, after).contains("不会自动改选"))
        assertEquals("iface:Meta", saved.wireValue)
        assertEquals(listOf("wifi", "sim1", "sim2", "auto"),
            configurationNetworkChoices(ConfigurationNetworkOutlets()).map { it.transport.wireValue })
    }

    @Test fun realInventoryCodecRejectsWrongSchemaDuplicatesAndMalformedNames() {
        val entry = """{"name":"Meta","up":true,"hasAddress":true,"kind":"tun"}"""
        val good = """{"ok":true,"version":2,"interfaces":[$entry]}"""
        assertTrue(NetworkOutletsCodec.decode(good).supported)
        for (bad in listOf(
            good.replace("\"version\":2", "\"version\":1"),
            good.replace("\"version\":2", "\"version\":4"),
            good.replace("\"version\":2", "\"version\":3"),
            good.replace("\"kind\":\"tun\"", "\"kind\":\"tun\",\"addresses\":[\"198.18.0.1\"]"),
            good.replace("\"version\":2", "\"version\":3").replace("\"kind\":\"tun\"", "\"kind\":\"tun\",\"addresses\":[]"),
            good.replace("\"version\":2", "\"version\":3").replace("\"kind\":\"tun\"", "\"kind\":\"tun\",\"addresses\":[\"999.1.1.1\"]"),
            good.replace("\"version\":2", "\"version\":3").replace("\"kind\":\"tun\"", "\"kind\":\"tun\",\"addresses\":[\"1.2.3.4;rm\"]"),
            good.replace("[$entry]", "[$entry,$entry]"),
            good.replace("Meta", "bad/name"),
            good.replace("\"kind\":\"tun\"", "\"kind\":\"unknown\""),
            good.replace("\"kind\":\"tun\"", "\"kind\":\"loopback\""),
            good.replace("\"up\":true", "\"up\":false"),
            good.replace("\"hasAddress\":true", "\"hasAddress\":false"),
            "not json",
        )) {
            assertFalse(NetworkOutletsCodec.decode(bad).supported)
            assertTrue(NetworkOutletsCodec.decode(bad).issue != null)
        }
    }
}
