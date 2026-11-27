package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
internal object ConfigurationTunnelIdentityCodec {
    fun read(editor: JsonElement?, facts: ConfigurationFacts?): ConfigurationTunnelIdentity? {
        val types = facts?.tunnelIdentity
        if (types == null || !types.known) {
            if (editor != null) invalid()
            return null
        }
        if (types.compatibilityBaseline) {
            if (editor != null) invalid()
            return ConfigurationTunnelIdentity()
        }
        if (types.source == "catalog") {
            if (editor != null) invalid()
            return ConfigurationTunnelIdentity()
        }
        if (editor == null) return null
        val value = decode(editor)
        if (value.policy?.types() != types.value) invalid()
        return value
    }

    fun decode(element: JsonElement): ConfigurationTunnelIdentity {
        val obj = objectWith(element, "localType", "requestType", "peer")
        val peer = obj.getValue("peer").takeUnless { it == JsonNull }?.let {
            val value = objectWith(it, "type", "value")
            ConfigurationPeerIdentity(type(value.string("type")), value.string("value") ?: invalid())
        }
        val policy = ConfigurationTunnelIdentityPolicy(type(obj.string("localType")), type(obj.string("requestType")), peer)
        validate(policy)
        return ConfigurationTunnelIdentity(policy)
    }

    fun validate(policy: ConfigurationTunnelIdentityPolicy) {
        validateTypes(policy.localType, policy.requestType)
        policy.peer?.let { peer ->
            val value = peer.value
            if (value.length !in 1..253 || value.any(Char::isISOControl) || containsIdentityDigits(value)) invalid()
            when (peer.type) {
                ConfigurationIkeIdType.FQDN -> domain(value)
                ConfigurationIkeIdType.RFC822 -> {
                    if (value.count { it == '@' } != 1 || value.startsWith('@') || value.any { it.code !in 0x21..0x7e }) invalid()
                    domain(value.substringAfter('@'))
                }
                ConfigurationIkeIdType.KEY_ID -> {
                    val bytes = try { Base64.decode(value) } catch (_: IllegalArgumentException) { invalid() }
                    val text = bytes.decodeToString()
                    if (bytes.isEmpty() || Base64.encode(bytes) != value || text.any(Char::isISOControl) || containsIdentityDigits(text)) invalid()
                }
                ConfigurationIkeIdType.IPV4 -> {
                    val parts = value.split('.')
                    if (parts.size != 4 || parts.any { it.isEmpty() || it.any { ch -> ch !in '0'..'9' } || it.length > 1 && it.startsWith('0') || it.toIntOrNull() !in 0..255 }) invalid()
                }
                ConfigurationIkeIdType.IPV6 -> if (!ConfigurationEpdgSelectionCodec.ipv6(value)) invalid()
            }
        }
    }

    fun encode(value: ConfigurationTunnelIdentity): JsonElement {
        val policy = value.policy ?: return JsonNull
        validate(policy)
        return buildJsonObject {
            put("localType", policy.localType.wire); put("requestType", policy.requestType.wire)
            put("peer", policy.peer?.let { buildJsonObject { put("type", it.type.wire); put("value", it.value) } } ?: JsonNull)
        }
    }

    fun facts(element: JsonElement?): ConfigurationTunnelIdentityFacts? {
        if (element == null) return null
        val obj = element as? JsonObject ?: invalid()
        val source = obj.string("source")?.takeIf { it in setOf("user", "catalog", "default") } ?: invalid()
        if (obj.string("state") == "unknown") {
            if (obj.keys != setOf("state", "source")) invalid()
            return ConfigurationTunnelIdentityFacts(false, source)
        }
        if (obj.string("state") != "known") invalid()
        if (source == "default") {
            if (obj.keys != setOf("state", "source", "baseline") || obj.string("baseline") != "swu-identity-compatible-v1") invalid()
            return ConfigurationTunnelIdentityFacts(true, source, compatibilityBaseline = true)
        }
        if (source == "catalog") {
            if (obj.keys != setOf("state", "source", "value")) invalid()
            return ConfigurationTunnelIdentityFacts(true, source, types(obj.getValue("value")))
        }
        if (obj.keys != setOf("state", "source", "value", "override")) invalid()
        val value = types(obj.getValue("value"))
        if (value != types(obj.getValue("override"))) invalid()
        return ConfigurationTunnelIdentityFacts(true, source, value)
    }

    fun connection(element: JsonElement?, loaded: ConfigurationTunnelIdentityFacts?): ConfigurationAuthenticatedIdentity? {
        if (element == null) return null
        val obj = objectWith(element, "localType", "requestType", "peerType", "peerConstrained")
        fun code(key: String) = ConfigurationIkeIdType.entries.firstOrNull {
            it.code == (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        } ?: invalid()
        val peerType = (obj["peerType"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 1..255 } ?: invalid()
        val value = ConfigurationAuthenticatedIdentity(code("localType"), code("requestType"), peerType,
            (obj["peerConstrained"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid())
        val expected = loaded?.takeIf { it.known }?.value ?: invalid()
        if (value.localType != expected.localType || value.requestType != expected.requestType ||
            value.peerConstrained != (expected.peerType != null) || expected.peerType != null && value.peerType != expected.peerType.code) invalid()
        return value
    }

    private fun types(element: JsonElement): ConfigurationTunnelIdentityTypes {
        val obj = objectWith(element, "localType", "requestType", "peerType")
        val local = type(obj.string("localType")); val request = type(obj.string("requestType"))
        validateTypes(local, request)
        val peer = obj.string("peerType") ?: invalid()
        return ConfigurationTunnelIdentityTypes(local, request, peer.takeIf(String::isNotEmpty)?.let(::type))
    }
    private fun ConfigurationTunnelIdentityPolicy.types() = ConfigurationTunnelIdentityTypes(localType, requestType, peer?.type)
    private fun validateTypes(local: ConfigurationIkeIdType, request: ConfigurationIkeIdType) {
        if (local !in setOf(ConfigurationIkeIdType.RFC822, ConfigurationIkeIdType.KEY_ID) || request !in setOf(ConfigurationIkeIdType.FQDN, ConfigurationIkeIdType.KEY_ID)) invalid()
    }
    private fun domain(value: String) {
        if (value.isEmpty()) invalid()
        try { digestRealmValue(value) } catch (_: ConfigurationFormatException) { invalid() }
    }
    private fun type(value: String?) = ConfigurationIkeIdType.entries.firstOrNull { it.wire == value } ?: invalid()
    private fun objectWith(value: JsonElement, vararg keys: String): JsonObject = (value as? JsonObject)?.takeIf { it.keys == keys.toSet() } ?: invalid()
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun invalid(): Nothing = throw ConfigurationFormatException.InvalidTunnelIdentity()
}
