package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationNetworkInterface
import com.vowifi.sw.manager.model.ConfigurationNetworkOutlets
import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.validNetworkInterfaceName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal object NetworkOutletsCodec {
    fun decode(output: String): ConfigurationNetworkOutlets {
        if (output.encodeToByteArray().size > 32768) return failure()
        val wire = try {
            Json.decodeFromString<Wire>(output)
        } catch (_: SerializationException) {
            return failure()
        } catch (_: IllegalArgumentException) {
            return failure()
        }
        // v2 engines predate addresses; v3 must carry them for every entry.
        if (!wire.ok || wire.version !in 2..3 || wire.interfaces.size > 256 ||
            wire.interfaces.map { it.name }.toSet().size != wire.interfaces.size ||
            wire.interfaces.any { !validNetworkInterfaceName(it.name) || !it.up || !it.hasAddress || it.kind !in setOf("interface", "tun") } ||
            wire.interfaces.any { if (wire.version == 3) it.addresses.isNullOrEmpty() else it.addresses != null } ||
            wire.interfaces.any { entry -> entry.addresses.orEmpty().let { it.size > 16 || it.any { address -> !validIpv4(address) && !validIpv6(address) } } }
        ) return failure()
        val automaticDns = wire.automaticDns.orEmpty()
        if (wire.version == 2 && wire.automaticDns != null || automaticDns.size > 261) return failure()
        try {
            for ((selector, server) in automaticDns) {
                transportOf(selector)
                if (server.isEmpty() || dnsServerValue(server) != server) return failure()
            }
        } catch (_: ConfigurationFormatException) {
            return failure()
        }
        return ConfigurationNetworkOutlets(
            interfaces = wire.interfaces.map {
                ConfigurationNetworkInterface(it.name, it.up, it.hasAddress, it.kind, displayAddresses(it.addresses.orEmpty()))
            },
            supported = true,
            automaticDns = automaticDns,
        )
    }

    private fun validIpv4(address: String): Boolean =
        address.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 && it.length in 1..3 } }

    private fun validIpv6(address: String): Boolean =
        ':' in address && address.length <= 45 && address.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }

    // At most one address per family keeps the menu row short enough to identify the interface.
    private fun displayAddresses(addresses: List<String>): List<String> =
        listOfNotNull(addresses.firstOrNull(::validIpv4), addresses.firstOrNull { !validIpv4(it) })

    fun failure(): ConfigurationNetworkOutlets = ConfigurationNetworkOutlets(issue = "网络出口读取失败或引擎不支持，请刷新配置重试")

    @Serializable
    private data class Wire(val ok: Boolean, val version: Int, val interfaces: List<WireInterface>,
        val automaticDns: Map<String, String>? = null)

    @Serializable
    private data class WireInterface(
        val name: String,
        val up: Boolean,
        val hasAddress: Boolean,
        val kind: String,
        val addresses: List<String>? = null,
    )
}
