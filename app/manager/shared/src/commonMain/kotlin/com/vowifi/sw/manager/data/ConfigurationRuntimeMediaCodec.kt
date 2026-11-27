package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*

/** Strict read-only IPC facts. Nothing here becomes an editable callMedia leaf. */
internal object ConfigurationRuntimeMediaCodec {
    fun decode(element: JsonElement): ConfigurationRuntimeMediaFacts {
        val obj = element as? JsonObject ?: invalid()
        if (obj.keys - "security" != FIELDS) invalid()
        val epoch = obj.text("epoch").takeIf { value ->
            value.length in 1..128 && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
        } ?: invalid()
        val codec = ConfigurationSpeechCodec.entries.firstOrNull { it.wire == obj.text("codec") } ?: invalid()
        val framing = ConfigurationSpeechFraming.entries.firstOrNull { it.wire == obj.text("framing") } ?: invalid()
        val pcmu = codec == ConfigurationSpeechCodec.PCMU
        if (pcmu != (framing == ConfigurationSpeechFraming.SAMPLE)) invalid()
        val sendPT = obj.number("payloadType", 0..127)
        val receivePT = obj.number("receivePayloadType", 0..127)
        if (listOf(sendPT, receivePT).any { it < 96 && !(pcmu && it == 0) }) invalid()
        val clock = obj.number("clockRate", 8000..16000)
        if (clock != if (codec == ConfigurationSpeechCodec.AMR_WB) 16000 else 8000) invalid()
        val direction = obj.text("direction").takeIf { it in setOf("sendrecv", "sendonly", "recvonly", "inactive") } ?: invalid()
        val inactivity = obj.number("inactivityTimeoutMs", 0..86400000)
        if (direction != "sendrecv" && inactivity != 0) invalid()
        val ptime = obj.number("sendPtimeMs", 20..240).takeIf { it % 20 == 0 } ?: invalid()
        return ConfigurationRuntimeMediaFacts(epoch, obj.number("revision", 1L..Long.MAX_VALUE), codec, framing, clock,
            sendPT, receivePT, ptime,
            obj.number("receiveMaxPtimeMs", 20..240), direction, inactivity, quality(obj.getValue("quality"), clock), obj["security"]?.let(::security))
    }

    private fun security(element: JsonElement): ConfigurationRuntimeMediaSecurity {
        val obj = element as? JsonObject ?: invalid()
        return when (obj.text("keying")) {
            "none" -> {
                if (obj.keys != setOf("keying")) invalid()
                ConfigurationRuntimeMediaSecurity("none")
            }
            "sdes" -> {
                if (obj.keys != setOf("keying", "suite")) invalid()
                val suite = ConfigurationSrtpSuite.entries.firstOrNull { it.wire == obj.text("suite") } ?: invalid()
                ConfigurationRuntimeMediaSecurity("sdes", suite)
            }
            else -> invalid()
        }
    }

    private fun quality(element: JsonElement, clock: Int): ConfigurationTransportQuality {
        val obj = element as? JsonObject ?: invalid()
        if ("revision" !in obj || obj.keys.any { it !in setOf("revision", "receive", "send") }) invalid()
        val revision = obj.number("revision", 0L..Long.MAX_VALUE)
        val receive = obj["receive"]?.let { sample(it, clock) }
        val send = obj["send"]?.let { sample(it, clock) }
        if (revision == 0L && (receive != null || send != null)) invalid()
        return ConfigurationTransportQuality(revision, receive, send)
    }

    private fun sample(element: JsonElement, clock: Int): ConfigurationTransportQualitySample {
        val obj = element as? JsonObject ?: invalid()
        if (obj.keys != setOf("fractionLost", "jitterMs", "totalLost")) invalid()
        val jitterLimit = (4294967295L * 1000 / clock).toInt()
        return ConfigurationTransportQualitySample(obj.number("fractionLost", 0..255), obj.number("jitterMs", 0..jitterLimit),
            obj.number("totalLost", -8388608..8388607))
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    private fun JsonObject.number(key: String, range: IntRange) = number(key, range.first.toLong()..range.last.toLong()).toInt()
    private fun JsonObject.number(key: String, range: LongRange) =
        (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in range } ?: invalid()
    private fun invalid(): Nothing = throw ConfigurationFormatException.Unparseable()
    private val FIELDS = setOf("epoch", "revision", "codec", "framing", "clockRate", "payloadType", "receivePayloadType",
        "sendPtimeMs", "receiveMaxPtimeMs", "direction", "inactivityTimeoutMs", "quality")
}
