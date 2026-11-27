package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationCallAddressing
import com.vowifi.sw.manager.model.ConfigurationCallSignaling
import com.vowifi.sw.manager.model.ConfigurationFacts
import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationPreconditions
import com.vowifi.sw.manager.model.ConfigurationReliableProvisional
import com.vowifi.sw.manager.model.ConfigurationSessionTimer
import kotlinx.serialization.json.*

internal object ConfigurationCallSignalingCodec {
    /** The facts decoder already checked scalars. Never rebuild a partial or redacted atomic group. */
    fun read(facts: ConfigurationFacts?): ConfigurationCallSignaling? {
        val fields = facts?.fields?.filter { it.field.startsWith("callSignaling.") } ?: return null
        if (fields.map { it.field.removePrefix("callSignaling.") }.toSet() != ConfigurationFactsCodec.CALL_SIGNALING_FIELDS) return null
        if (fields.any { it.value == null || when (it.source) {
                "default", "catalog" -> it.overrideValue != null
                "user" -> it.overrideValue == null
                else -> true
            } }
        ) return null
        val choices = fields.associate { it.field.removePrefix("callSignaling.") to it.overrideValue }
        fun bool(name: String) = choices[name]?.toBooleanStrict()
        fun seconds(name: String) = choices[name]?.toInt()
        return ConfigurationCallSignaling(
            sessionTimer = ConfigurationSessionTimer(
                bool("sessionTimer.enabled"), seconds("sessionTimer.expiresSeconds"), seconds("sessionTimer.minimumSeconds"),
                choices["sessionTimer.refresher"], choices["sessionTimer.refreshMethod"], bool("sessionTimer.requirePeer"),
            ),
            reliableProvisional = ConfigurationReliableProvisional(
                bool("reliableProvisional.uacEnabled"), bool("reliableProvisional.requirePeer"), choices["reliableProvisional.uasMode"],
            ),
            preconditions = ConfigurationPreconditions(
                bool("preconditions.uacEnabled"), bool("preconditions.uasEnabled"), bool("preconditions.requirePeer"), bool("preconditions.wifiAllowed"),
            ),
            addressing = ConfigurationCallAddressing(
                choices["addressing.requestURI"], choices["addressing.phoneContext"], choices["addressing.contactTransport"],
            ),
            includePANI = bool("includePANI"),
        )
    }

    /** Encode the complete explicit group, never its resolved/default policy. */
    fun encode(value: ConfigurationCallSignaling): JsonElement {
        val group = buildJsonObject {
            group("sessionTimer", buildJsonObject {
                value.sessionTimer.run {
                    enabled?.let { put("enabled", it) }
                    expiresSeconds?.let { put("expiresSeconds", it) }
                    minimumSeconds?.let { put("minimumSeconds", it) }
                    refresher?.let { put("refresher", it) }
                    refreshMethod?.let { put("refreshMethod", it) }
                    requirePeer?.let { put("requirePeer", it) }
                }
            })
            group("reliableProvisional", buildJsonObject {
                value.reliableProvisional.run {
                    uacEnabled?.let { put("uacEnabled", it) }
                    requirePeer?.let { put("requirePeer", it) }
                    uasMode?.let { put("uasMode", it) }
                }
            })
            group("preconditions", buildJsonObject {
                value.preconditions.run {
                    uacEnabled?.let { put("uacEnabled", it) }
                    uasEnabled?.let { put("uasEnabled", it) }
                    requirePeer?.let { put("requirePeer", it) }
                    wifiAllowed?.let { put("wifiAllowed", it) }
                }
            })
            group("addressing", buildJsonObject {
                value.addressing.run {
                    requestURI?.let { put("requestURI", it) }
                    phoneContext?.let { put("phoneContext", it) }
                    contactTransport?.let { put("contactTransport", it) }
                }
            })
            value.includePANI?.let { put("includePANI", it) }
        }
        group.forEach { (name, entry) ->
            if (entry is JsonObject) entry.forEach { (leaf, scalar) ->
                ConfigurationFactsCodec.validateSignalingOverride("$name.$leaf", scalar.jsonPrimitive.content)
            } else ConfigurationFactsCodec.validateSignalingOverride(name, entry.jsonPrimitive.content)
        }
        // Only definite explicit conflicts are checked here. The engine remains
        // authoritative for inherited values, access gates and full policy resolution.
        val timer = value.sessionTimer
        val reliable = value.reliableProvisional
        val preconditions = value.preconditions
        if (timer.expiresSeconds != null && timer.minimumSeconds != null && timer.expiresSeconds < timer.minimumSeconds ||
            timer.enabled == false && timer.requirePeer == true || reliable.uacEnabled == false && reliable.requirePeer == true ||
            preconditions.uacEnabled == false && preconditions.requirePeer == true ||
            preconditions.uacEnabled == true && reliable.uacEnabled == false ||
            preconditions.uasEnabled == true && reliable.uasMode != null && reliable.uasMode !in setOf("sdp-only", "all-18x")
        ) throw ConfigurationFormatException.InvalidCallSignaling()
        return if (group.isEmpty()) JsonNull else group
    }

    private fun JsonObjectBuilder.group(name: String, fields: JsonObject) {
        if (fields.isNotEmpty()) put(name, fields)
    }
}
