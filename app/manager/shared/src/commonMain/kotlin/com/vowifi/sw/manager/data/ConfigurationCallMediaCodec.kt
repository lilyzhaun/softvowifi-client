package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*

internal object ConfigurationCallMediaCodec {
    fun read(facts: ConfigurationFacts?): ConfigurationCallMedia? = facts?.callMedia?.takeIf { it.known }?.let {
        it.overrideValue ?: ConfigurationCallMedia()
    }

    fun decode(element: JsonElement): ConfigurationCallMedia {
        val obj = element as? JsonObject ?: invalid()
        if ("speechFormats" !in obj || obj.keys.any { it !in setOf("speechFormats", "packetization", "inactivity", "security") }) invalid()
        val formats = (obj["speechFormats"] as? JsonArray)?.takeIf { it.size in 1..30 } ?: invalid()
        val parsed = formats.map { item ->
            val format = item as? JsonObject ?: invalid()
            if (format.keys.any { it !in setOf("codec", "framing", "modeSet", "initialMode", "payloadType", "receiveModeChangePeriod", "receiveModeChangeNeighbor") }) invalid()
            val codec = ConfigurationSpeechCodec.entries.firstOrNull { it.wire == format.string("codec") } ?: invalid()
            val framing = ConfigurationSpeechFraming.entries.firstOrNull { it.wire == format.string("framing") } ?: invalid()
            val pcmu = codec == ConfigurationSpeechCodec.PCMU
            if (pcmu != (framing == ConfigurationSpeechFraming.SAMPLE)) invalid()
            if (pcmu && format.keys.any { it in setOf("modeSet", "initialMode", "receiveModeChangePeriod", "receiveModeChangeNeighbor") }) invalid()
            val modes = format["modeSet"]?.let { raw ->
                val values = (raw as? JsonArray)?.takeIf { it.size in 1..codec.maxMode + 1 }?.map { number(it, 0..codec.maxMode) } ?: invalid()
                if (values.distinct().size != values.size) invalid()
                values.sorted()
            }
            val initial = format["initialMode"]?.let { number(it, 0..codec.maxMode) }
            if (initial != null && modes != null && initial !in modes) invalid()
            val pt = format["payloadType"]?.let { number(it, 0..127).also { pt -> if (pt < 96 && !(pcmu && pt == 0)) invalid() } }
            val receivePeriod = format["receiveModeChangePeriod"]?.let { number(it, 1..2) }
            val receiveNeighbor = format["receiveModeChangeNeighbor"]?.let {
                (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull ?: invalid()
            }
            ConfigurationSpeechFormat(codec, framing, modes, initial, pt, receivePeriod, receiveNeighbor)
        }
        if (parsed.distinct().size != parsed.size) invalid()
        if (parsed.count { it.codec == ConfigurationSpeechCodec.PCMU } > 1) invalid()
        val packetization = obj["packetization"]?.let { element ->
            val pair = element as? JsonObject ?: invalid()
            if (pair.keys != setOf("receivePtimeMs", "receiveMaxPtimeMs")) invalid()
            val ptime = number(pair.getValue("receivePtimeMs"), 20..240)
            val maximum = number(pair.getValue("receiveMaxPtimeMs"), 20..240)
            if (ptime % 20 != 0 || maximum < ptime) invalid()
            ConfigurationCallPacketization(ptime, maximum)
        }
        val inactivity = obj["inactivity"]?.let { element ->
            val policy = element as? JsonObject ?: invalid()
            if (policy.keys != setOf("timeoutMs")) invalid()
            ConfigurationCallInactivity(number(policy.getValue("timeoutMs"), 0..86400000))
        }
        val security = obj["security"]?.let { element ->
            val policy = element as? JsonObject ?: invalid()
            if (policy.keys != setOf("keying", "suites") || policy.string("keying") != "sdes") invalid()
            val suites = (policy["suites"] as? JsonArray)?.takeIf { it.size in 1..3 }?.map { value ->
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
                ConfigurationSrtpSuite.entries.firstOrNull { it.wire == text } ?: invalid()
            } ?: invalid()
            if (suites.distinct().size != suites.size) invalid()
            ConfigurationCallMediaSecurity("sdes", suites)
        }
        return ConfigurationCallMedia(parsed, packetization, inactivity, security)
    }

    fun encode(policy: ConfigurationCallMedia): JsonElement {
        if (policy.speechFormats.isEmpty()) {
            if (policy.packetization != null || policy.inactivity != null || policy.security != null) invalid()
            return JsonNull
        }
        val obj = buildJsonObject {
            put("speechFormats", buildJsonArray {
                policy.speechFormats.forEach { format -> add(buildJsonObject {
                    put("codec", format.codec.wire); put("framing", format.framing.wire)
                    format.modeSet?.let { put("modeSet", JsonArray(it.sorted().map(::JsonPrimitive))) }
                    format.initialMode?.let { put("initialMode", it) }
                    format.payloadType?.let { put("payloadType", it) }
                    format.receiveModeChangePeriod?.let { put("receiveModeChangePeriod", it) }
                    format.receiveModeChangeNeighbor?.let { put("receiveModeChangeNeighbor", it) }
                }) }
            })
            policy.packetization?.let { pair -> put("packetization", buildJsonObject {
                put("receivePtimeMs", pair.receivePtimeMs)
                put("receiveMaxPtimeMs", pair.receiveMaxPtimeMs)
            }) }
            policy.inactivity?.let { put("inactivity", buildJsonObject { put("timeoutMs", it.timeoutMs) }) }
            policy.security?.let { security -> put("security", buildJsonObject {
                put("keying", security.keying)
                put("suites", JsonArray(security.suites.map { JsonPrimitive(it.wire) }))
            }) }
        }
        decode(obj) // One serialization boundary, also for typed editor input.
        return obj
    }

    fun facts(element: JsonElement?): ConfigurationCallMediaFacts? {
        if (element == null) return null
        val obj = element as? JsonObject ?: invalid()
        if (obj.keys.any { it !in setOf("state", "source", "baseline", "value", "override") }) invalid()
        val source = obj.string("source")?.takeIf { it in setOf("user", "default", "catalog") } ?: invalid()
        if (obj.string("state") == "unknown") {
            if (obj.keys != setOf("state", "source")) invalid()
            return ConfigurationCallMediaFacts(false, source)
        }
        if (obj.string("state") != "known") invalid()
        if (source == "default") {
            if (obj.keys != setOf("state", "source", "baseline") || obj.string("baseline") != "amr-compatible-v1") invalid()
            return ConfigurationCallMediaFacts(true, source, compatibilityBaseline = true)
        }
        if (source == "catalog") {
            if (obj.keys != setOf("state", "source", "value")) invalid()
            return ConfigurationCallMediaFacts(true, source, value = decode(obj.getValue("value")))
        }
        if (obj.keys != setOf("state", "source", "value", "override")) invalid()
        val value = decode(obj.getValue("value"))
        val override = decode(obj.getValue("override"))
        if (value != override) invalid()
        return ConfigurationCallMediaFacts(true, source, value = value, overrideValue = override)
    }

    private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun number(element: JsonElement, range: IntRange): Int =
        (element as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in range } ?: invalid()
    private fun invalid(): Nothing = throw ConfigurationFormatException.Unparseable()
}
