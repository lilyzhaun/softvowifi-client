package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*

internal object ConfigurationEpdgSelectionCodec {
    fun read(facts: ConfigurationFacts?): ConfigurationEpdgSelection? = facts?.epdgSelection?.takeIf { it.known }?.let {
        it.overrideValue ?: ConfigurationEpdgSelection()
    }

    fun decode(element: JsonElement): ConfigurationEpdgSelection {
        val obj = objectWith(element, "candidates")
        val candidates = (obj["candidates"] as? JsonArray)?.map { item ->
            val row = objectWith(item, "kind", "value", "access", "registeredPlmns")
            ConfigurationEpdgCandidate(
                ConfigurationEpdgKind.entries.firstOrNull { it.wire == row.string("kind") } ?: invalid(),
                row.string("value") ?: invalid(),
                ConfigurationEpdgAccess.entries.firstOrNull { it.wire == row.string("access") } ?: invalid(),
                (row["registeredPlmns"] as? JsonArray)?.map { value ->
                    (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
                } ?: invalid(),
            )
        } ?: invalid()
        val value = ConfigurationEpdgSelection(candidates)
        validate(value)
        return value.copy(candidates = candidates.map { it.copy(registeredPlmns = it.registeredPlmns.sorted()) })
    }

    fun validate(value: ConfigurationEpdgSelection, singleHost: String = "") {
        val candidates = value.candidates ?: return
        if (singleHost.isNotEmpty() || candidates.size !in 1..8) invalid()
        candidates.forEach { row ->
            if (row.registeredPlmns.size > 8 || row.registeredPlmns.distinct().size != row.registeredPlmns.size || row.registeredPlmns.any { !plmn(it) }) invalid()
            when (row.kind) {
                ConfigurationEpdgKind.ADDRESS -> {
                    if (row.value.isEmpty() || containsIdentityDigits(row.value)) invalid()
                    if (':' in row.value) { if (!ipv6(row.value)) invalid() }
                    else try { digestRealmValue(row.value) } catch (_: ConfigurationFormatException) { invalid() }
                }
                ConfigurationEpdgKind.PLMN -> if (!plmn(row.value)) invalid()
                ConfigurationEpdgKind.HOME_PLMN, ConfigurationEpdgKind.REGISTERED_PLMN, ConfigurationEpdgKind.FIRST_EHPLMN -> if (row.value.isNotEmpty()) invalid()
            }
        }
        if (candidates.map { it.copy(registeredPlmns = it.registeredPlmns.sorted()) }.distinct().size != candidates.size) invalid()
    }

    fun encode(value: ConfigurationEpdgSelection): JsonElement {
        val candidates = value.candidates ?: return JsonNull
        validate(value)
        return buildJsonObject { put("candidates", JsonArray(candidates.map { row -> buildJsonObject {
            put("kind", row.kind.wire); put("value", row.value); put("access", row.access.wire)
            put("registeredPlmns", JsonArray(row.registeredPlmns.sorted().map(::JsonPrimitive)))
        } })) }
    }

    fun facts(element: JsonElement?): ConfigurationEpdgSelectionFacts? {
        if (element == null) return null
        val obj = element as? JsonObject ?: invalid()
        val source = obj.string("source")?.takeIf { it in setOf("user", "catalog", "default") } ?: invalid()
        if (obj.string("state") == "unknown") {
            if (obj.keys != setOf("state", "source")) invalid()
            return ConfigurationEpdgSelectionFacts(false, source)
        }
        if (obj.string("state") != "known") invalid()
        if (source == "default") {
            if (obj.keys != setOf("state", "source", "baseline") || obj.string("baseline") != "single-epdg-compatible-v1") invalid()
            return ConfigurationEpdgSelectionFacts(true, source, compatibilityBaseline = true)
        }
        if (source == "catalog") {
            if (obj.keys != setOf("state", "source", "value")) invalid()
            return ConfigurationEpdgSelectionFacts(true, source, decode(obj.getValue("value")))
        }
        if (obj.keys != setOf("state", "source", "value", "override")) invalid()
        val value = decode(obj.getValue("value"))
        val override = decode(obj.getValue("override"))
        if (value != override) invalid()
        return ConfigurationEpdgSelectionFacts(true, source, value, override)
    }

    fun connection(element: JsonElement?, loaded: ConfigurationEpdgSelectionFacts?): ConfigurationEpdgConnection? {
        if (element == null) return null
        val obj = objectWith(element, "candidate", "registered")
        val candidate = (obj["candidate"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 1..8 } ?: invalid()
        val row = loaded?.takeIf { it.known }?.value?.candidates?.getOrNull(candidate - 1) ?: invalid()
        val registered = obj["registered"] as? JsonObject ?: invalid()
        val result = when (registered.string("state")) {
            "unknown" -> {
                if (registered.keys != setOf("state")) invalid()
                ConfigurationEpdgConnection(candidate)
            }
            "known" -> {
                if (registered.keys != setOf("state", "plmn", "roaming")) invalid()
                val plmn = registered.string("plmn")?.takeIf(::plmn) ?: invalid()
                val roaming = (registered["roaming"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid()
                ConfigurationEpdgConnection(candidate, plmn, roaming)
            }
            else -> invalid()
        }
        if (row.access != ConfigurationEpdgAccess.ANY && result.roaming != (row.access == ConfigurationEpdgAccess.ROAMING) ||
            row.registeredPlmns.isNotEmpty() && result.registeredPlmn !in row.registeredPlmns ||
            row.kind == ConfigurationEpdgKind.REGISTERED_PLMN && result.registeredPlmn == null) invalid()
        return result
    }

    private fun plmn(text: String) = text.length in 5..6 && text.all { it in '0'..'9' }
    fun ipv6(text: String): Boolean {
        if (":::" in text || text.windowed(2).count { it == "::" } > 1) return false
        val compressed = "::" in text
        val halves = if (compressed) text.split("::") else listOf(text)
        if (halves.any { it.isNotEmpty() && (it.startsWith(':') || it.endsWith(':')) }) return false
        val tokens = halves.filter(String::isNotEmpty).flatMap { it.split(':') }
        var groups = 0
        for ((index, token) in tokens.withIndex()) {
            if ('.' in token) {
                val parts = token.split('.')
                if (index != tokens.lastIndex || !text.endsWith(token) || parts.size != 4 || parts.any { it.isEmpty() || !it.all { ch -> ch in '0'..'9' } || it.length > 1 && it.startsWith('0') || it.toIntOrNull() !in 0..255 }) return false
                groups += 2
            } else {
                if (token.length !in 1..4 || token.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return false
                groups++
            }
        }
        return if (compressed) groups < 8 else groups == 8
    }
    private fun objectWith(value: JsonElement?, vararg keys: String): JsonObject = (value as? JsonObject)?.takeIf { it.keys == keys.toSet() } ?: invalid()
    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun invalid(): Nothing = throw ConfigurationFormatException.InvalidEpdgSelection()
}
