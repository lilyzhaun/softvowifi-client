package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*

/** Complete public tuples only. The engine revalidates against its pinned SDK before saving. */
internal object ConfigurationTunnelSecurityCodec {
    fun read(facts: ConfigurationFacts?): ConfigurationTunnelSecurity? = facts?.tunnelSecurity?.takeIf { it.known }?.let {
        it.overrideValue ?: ConfigurationTunnelSecurity()
    }

    fun decode(element: JsonElement): ConfigurationTunnelSecurity {
        val obj = element as? JsonObject ?: invalid()
        val required = setOf("ikeSuites", "childSuites", "layout")
        if (obj.keys != required && obj.keys != required + "childPFSGroup") invalid()
        val pfs = if ("childPFSGroup" in obj) {
            (obj["childPFSGroup"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it == 0 || it == 14 } ?: invalid()
        } else 0
        val ike = array(obj["ikeSuites"], 4).map { name -> ConfigurationIkeSuite.entries.firstOrNull { it.wire == name } ?: invalid() }
        val child = array(obj["childSuites"], ConfigurationChildSuite.entries.size).map { name -> ConfigurationChildSuite.entries.firstOrNull { it.wire == name } ?: invalid() }
        val layout = ConfigurationTunnelLayout.entries.firstOrNull { it.wire == obj.string("layout") } ?: invalid()
        if (ike.map { it.dhGroup }.distinct().size != 1) invalid()
        // Alternatives in a single IKE proposal form a product, not a list of tuples.
        // Its unique tuples must cover that product exactly, matching the SDK boundary.
        if (layout == ConfigurationTunnelLayout.SINGLE_COMBINED &&
            ike.map { it.keyBits }.distinct().size * ike.map { it.integrity }.distinct().size *
            ike.map { it.prf }.distinct().size != ike.size) invalid()
        return ConfigurationTunnelSecurity(ConfigurationTunnelPolicy(ike, child, layout, pfs))
    }

    fun encode(value: ConfigurationTunnelSecurity): JsonElement {
        val policy = value.policy ?: return JsonNull
        val obj = buildJsonObject {
            put("ikeSuites", JsonArray(policy.ikeSuites.map { JsonPrimitive(it.wire) }))
            put("childSuites", JsonArray(policy.childSuites.map { JsonPrimitive(it.wire) }))
            put("layout", policy.layout.wire)
            if (policy.childPFSGroup != 0) put("childPFSGroup", policy.childPFSGroup)
        }
        decode(obj)
        return obj
    }

    fun facts(element: JsonElement?): ConfigurationTunnelSecurityFacts? {
        if (element == null) return null
        val obj = element as? JsonObject ?: invalid()
        val source = obj.string("source")?.takeIf { it in setOf("user", "catalog", "default", "diagnostic") } ?: invalid()
        if (obj.string("state") == "unknown") {
            if (obj.keys != setOf("state", "source")) invalid()
            return ConfigurationTunnelSecurityFacts(false, source)
        }
        if (obj.string("state") != "known") invalid()
        if (source == "default") {
            if (obj.keys != setOf("state", "source", "baseline") || obj.string("baseline") != "swu-compatible-v1") invalid()
            return ConfigurationTunnelSecurityFacts(true, source, compatibilityBaseline = true)
        }
        val hasOverride = source == "user" || (source == "diagnostic" && "override" in obj)
        val keys = if (hasOverride) setOf("state", "source", "value", "override") else setOf("state", "source", "value")
        if (obj.keys != keys) invalid()
        val value = decode(obj.getValue("value"))
        val override = if (hasOverride) decode(obj.getValue("override")) else null
        if (source == "user" && value != override) invalid()
        if (source == "diagnostic" && override != null && value.policy!!.copy(layout = override.policy!!.layout) != override.policy) invalid()
        return ConfigurationTunnelSecurityFacts(true, source, value, override)
    }

    private fun array(element: JsonElement?, maximum: Int): List<String> {
        val list = (element as? JsonArray)?.takeIf { it.size in 1..maximum }?.map {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: invalid()
        } ?: invalid()
        if (list.distinct().size != list.size) invalid()
        return list
    }
    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun invalid(): Nothing = throw ConfigurationFormatException.InvalidTunnelSecurity()
}
