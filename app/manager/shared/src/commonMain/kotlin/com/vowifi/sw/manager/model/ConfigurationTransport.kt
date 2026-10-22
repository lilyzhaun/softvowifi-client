package com.vowifi.sw.manager.model

sealed class ConfigurationTransport(val wireValue: String) {
    data object WIFI : ConfigurationTransport("wifi")
    data object SIM1 : ConfigurationTransport("sim1")
    data object SIM2 : ConfigurationTransport("sim2")
    data object AUTO : ConfigurationTransport("auto")
    data object SYSTEM_ROUTE : ConfigurationTransport("route:system")

    data class Interface(val interfaceName: String) : ConfigurationTransport("iface:$interfaceName") {
        init {
            if (!validNetworkInterfaceName(interfaceName)) throw ConfigurationFormatException.InvalidTransport()
        }
    }

    val name: String get() = wireValue.uppercase()

    companion object {
        /** The four legacy modes remain enumerable; interface values are dynamic. */
        val entries: List<ConfigurationTransport> get() = listOf(WIFI, SIM1, SIM2, AUTO)
    }
}

internal fun validNetworkInterfaceName(name: String): Boolean =
    name.length in 1..15 && name != "." && name != ".." && name.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == '.'
    }

data class ConfigurationNetworkInterface(
    val name: String,
    val up: Boolean,
    val hasAddress: Boolean,
    val kind: String,
    // Display-only global addresses from network-list v3; admission stays with the engine.
    val addresses: List<String> = emptyList(),
)

data class ConfigurationNetworkOutlets(
    val interfaces: List<ConfigurationNetworkInterface> = emptyList(),
    val supported: Boolean = false,
    val issue: String? = null,
    // Read-only resolver selection from the same network-list snapshot; never a saved override.
    val automaticDns: Map<String, String> = emptyMap(),
)
