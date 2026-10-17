package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*

object EngineClientCapabilitiesCodec {
    private val approved = setOf("config.metadata.v1", "rpc.getConfig", "rpc.getStatus", "rpc.ping", "rpc.stop")
    private val requiredMetadata = setOf("config.metadata.v1")
    private val capabilityId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val instanceKey = Regex("[0-9a-f]{32}")
    private val version = Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}(?:[-+][A-Za-z0-9.-]{1,32})?")

    fun params(): JsonObject = buildJsonObject {
        put("schema", 1)
        put("required", JsonArray(requiredMetadata.map(::JsonPrimitive)))
    }

    fun decode(
        response: EngineProtocolFrame.Response,
        required: Set<String> = requiredMetadata,
    ): ClientCapabilitiesResult {
        if (!approved.containsAll(required)) return unavailable(ClientCapabilitiesFailure.Unsupported)
        response.error?.let {
            return unavailable(when (it.code) {
                1000, 1001, 1002 -> ClientCapabilitiesFailure.Unsupported
                else -> ClientCapabilitiesFailure.Rpc
            })
        }
        val root = response.result ?: return invalid()
        if (root.keys != setOf("schema", "protocol", "instance", "capabilities", "configMetadata")) return invalid()
        if (!root.hasVersionOne("schema")) return unavailable(ClientCapabilitiesFailure.Unsupported)
        val protocol = root["protocol"] as? JsonObject ?: return invalid()
        if (protocol.keys != setOf("version", "engineVersion")) return invalid()
        if (!protocol.hasVersionOne("version")) return unavailable(ClientCapabilitiesFailure.Unsupported)
        val engineVersion = protocol.text("engineVersion") ?: return invalid()
        if (!version.matches(engineVersion) || containsIdentityDigits(engineVersion)) return invalid()
        val instance = root["instance"] as? JsonObject ?: return invalid()
        if (instance.keys != setOf("key")) return invalid()
        val key = instance.text("key") ?: return invalid()
        if (!instanceKey.matches(key)) return invalid()
        val ids = root["capabilities"] as? JsonArray ?: return invalid()
        if (ids.size !in 1..64) return invalid()
        val capabilities = ids.map { element ->
            val id = element.stringValue() ?: return invalid()
            if (!capabilityId.matches(id) || containsIdentityDigits(id)) return invalid()
            id
        }
        if (capabilities != capabilities.distinct().sorted()) return invalid()
        if (!capabilities.containsAll(required)) return unavailable(ClientCapabilitiesFailure.Unsupported)
        val metadata = root["configMetadata"] as? JsonArray ?: return invalid()
        if (metadata.size != MetadataField.entries.size) return invalid()
        val items = metadata.map { parseMetadata(it) ?: return invalid() }
        if (items.map { it.field }.toSet() != MetadataField.entries.toSet()) return invalid()
        return ClientCapabilitiesResult.Ready(EngineClientCapabilities(key, engineVersion, capabilities, items))
    }

    private fun parseMetadata(element: JsonElement): ClientConfigMetadata? {
        val item = element as? JsonObject ?: return null
        if (item.keys != setOf("field", "value", "source", "decision")) return null
        val field = MetadataField.entries.find { it.wireValue == item.text("field") } ?: return null
        val source = MetadataSource.entries.find { it.wireValue == item.text("source") } ?: return null
        val decision = MetadataDecision.entries.find { it.wireValue == item.text("decision") } ?: return null
        if ((source == MetadataSource.User) != (decision == MetadataDecision.Override)) return null
        val value = item.text("value") ?: return null
        if (containsIdentityDigits(value)) return null
        val valid = when (field) {
            MetadataField.SipSecurity -> source in setOf(MetadataSource.User, MetadataSource.Carrier, MetadataSource.Catalog, MetadataSource.Default) &&
                value in setOf("tcp_ipsec_3gpp", "swu_udp_aka")
            MetadataField.PaniInitial -> source in setOf(MetadataSource.User, MetadataSource.Carrier, MetadataSource.Catalog, MetadataSource.Default) &&
                value in setOf("absent", "wlan", "wlan-node", "wlan-country", "wlan-node-country")
            MetadataField.PaniCountry -> source in setOf(MetadataSource.User, MetadataSource.Carrier, MetadataSource.HomeMcc) &&
                (value.isEmpty() || value.length == 2 && value.all { it in 'A'..'Z' })
            MetadataField.DigestRealm -> when (source) {
                MetadataSource.Identity -> value.isEmpty()
                MetadataSource.User, MetadataSource.Carrier -> validDomain(value)
                MetadataSource.Default, MetadataSource.Catalog, MetadataSource.HomeMcc -> false
            }
        }
        return if (valid) ClientConfigMetadata(field, value, source, decision) else null
    }

    private fun validDomain(value: String): Boolean {
        if (value.isEmpty()) return false
        val labels = value.removeSuffix(".").split('.')
        val ipv4 = labels.size == 4 && labels.all { label ->
            label.isNotEmpty() && label.all { it in '0'..'9' } &&
                (label.length == 1 || label.first() != '0') && label.toIntOrNull() in 0..255
        }
        if (ipv4) return false
        return try {
            digestRealmValue(value)
            true
        } catch (_: ConfigurationFormatException) {
            false
        }
    }

    private fun JsonObject.hasVersionOne(key: String): Boolean {
        val value = this[key] as? JsonPrimitive ?: return false
        return !value.isString && value.content == "1"
    }

    private fun JsonObject.text(key: String): String? = this[key]?.stringValue()
    private fun JsonElement.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun invalid() = unavailable(ClientCapabilitiesFailure.Invalid)
    private fun unavailable(reason: ClientCapabilitiesFailure) = ClientCapabilitiesResult.Unavailable(reason)
}
