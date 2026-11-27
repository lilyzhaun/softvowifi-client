package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*

internal object ConfigurationSwuAccessCodec {
    fun read(facts: ConfigurationFacts?): ConfigurationSwuAccess? = facts?.swuAccess?.takeIf { it.known }?.let {
        it.overrideValue ?: ConfigurationSwuAccess()
    }

    fun decode(element: JsonElement): ConfigurationSwuAccess {
        val obj = element as? JsonObject ?: invalid()
        val required = setOf("purpose", "requestedApn", "interfaceMtu")
        if (obj.keys != required && obj.keys != required + "innerProtocol") invalid()
        val purpose = obj.string("purpose") ?: invalid()
        val apn = obj.string("requestedApn") ?: invalid()
        val mtu = (obj["interfaceMtu"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: invalid()
        val inner = if ("innerProtocol" in obj) {
            val group = obj["innerProtocol"] as? JsonObject ?: invalid()
            if (group.keys != setOf("home", "roaming")) invalid()
            ConfigurationSwuInnerProtocol(group.string("home") ?: invalid(), group.string("roaming") ?: invalid())
        } else null
        val policy = ConfigurationSwuAccessPolicy(apn, mtu, purpose, inner)
        validate(policy)
        return ConfigurationSwuAccess(policy)
    }

    fun validate(policy: ConfigurationSwuAccessPolicy) {
        val apn = policy.requestedApn
        if (policy.purpose != "ims" || policy.interfaceMtu !in 1280..1500 || apn.isEmpty() || apn.length > 100) invalid()
        if (apn.split('.').any { label -> label.isEmpty() || label.length > 63 || label.first() == '-' || label.last() == '-' ||
                label.any { it != '-' && it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' } }) invalid()
        policy.innerProtocol?.let {
            val supported = setOf("ipv4", "ipv6", "dual-stack")
            if (it.home !in supported || it.roaming !in supported) invalid()
        }
    }

    fun encode(value: ConfigurationSwuAccess): JsonElement {
        val policy = value.policy ?: return JsonNull
        validate(policy)
        return buildJsonObject {
            put("purpose", policy.purpose); put("requestedApn", policy.requestedApn); put("interfaceMtu", policy.interfaceMtu)
            policy.innerProtocol?.let { inner ->
                put("innerProtocol", buildJsonObject { put("home", inner.home); put("roaming", inner.roaming) })
            }
        }
    }

    fun facts(element: JsonElement?): ConfigurationSwuAccessFacts? {
        if (element == null) return null
        val obj = element as? JsonObject ?: invalid()
        val source = obj.string("source")?.takeIf { it in setOf("user", "default") } ?: invalid()
        if (obj.string("state") == "unknown") {
            if (obj.keys != setOf("state", "source")) invalid()
            return ConfigurationSwuAccessFacts(false, source)
        }
        if (obj.string("state") != "known") invalid()
        if (source == "default") {
            if (obj.keys != setOf("state", "source", "baseline") || obj.string("baseline") != "swu-ims-access-compatible-v1") invalid()
            return ConfigurationSwuAccessFacts(true, source, compatibilityBaseline = true)
        }
        if (obj.keys != setOf("state", "source", "value", "override")) invalid()
        val value = decode(obj.getValue("value"))
        val override = decode(obj.getValue("override"))
        if (value != override) invalid()
        if (containsIdentityDigits(value.policy!!.requestedApn)) throw ConfigurationFormatException.IdentityLeak()
        return ConfigurationSwuAccessFacts(true, source, value, override)
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun invalid(): Nothing = throw ConfigurationFormatException.InvalidSwuAccess()
}
