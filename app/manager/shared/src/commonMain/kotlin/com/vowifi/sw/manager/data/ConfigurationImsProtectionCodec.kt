package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*

internal object ConfigurationImsProtectionCodec {
    fun read(facts: ConfigurationFacts?): ConfigurationImsProtection? = facts?.imsProtection?.takeIf { it.known }?.let {
        it.overrideValue ?: ConfigurationImsProtection()
    }

    fun decode(element: JsonElement): ConfigurationImsProtection {
        val obj = element as? JsonObject ?: invalid()
        if (obj.keys != setOf("suites")) invalid()
        val suites = (obj["suites"] as? JsonArray)?.takeIf { it.size in 1..6 }?.map { value ->
            val wire = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
            ConfigurationImsSuite.entries.firstOrNull { it.wire == wire } ?: invalid()
        } ?: invalid()
        if (suites.distinct().size != suites.size) invalid()
        return ConfigurationImsProtection(suites)
    }

    fun encode(value: ConfigurationImsProtection): JsonElement {
        val suites = value.suites ?: return JsonNull
        val obj = buildJsonObject { put("suites", JsonArray(suites.map { JsonPrimitive(it.wire) })) }
        decode(obj)
        return obj
    }

    fun facts(element: JsonElement?): ConfigurationImsProtectionFacts? {
        if (element == null) return null
        val obj = element as? JsonObject ?: invalid()
        val source = obj.string("source")?.takeIf { it in setOf("user", "catalog", "default") } ?: invalid()
        if (obj.string("state") == "unknown") {
            if (obj.keys != setOf("state", "source")) invalid()
            return ConfigurationImsProtectionFacts(false, source)
        }
        if (obj.string("state") != "known") invalid()
        if (source == "default") {
            if (obj.keys != setOf("state", "source", "baseline")) invalid()
            val baseline = obj.string("baseline")?.takeIf { it in setOf("ims-esp-compatible-v1", "swu-only-v1") } ?: invalid()
            return ConfigurationImsProtectionFacts(true, source, baseline = baseline)
        }
        val keys = if (source == "catalog") setOf("state", "source", "value") else setOf("state", "source", "value", "override")
        if (obj.keys != keys) invalid()
        val value = decode(obj.getValue("value"))
        val override = if (source == "catalog") null else decode(obj.getValue("override"))
        if (source == "user" && value != override) invalid()
        return ConfigurationImsProtectionFacts(true, source, value, override)
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun invalid(): Nothing = throw ConfigurationFormatException.InvalidImsProtection()
}
