package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationIdentitySource
import com.vowifi.sw.manager.model.ConfigurationImsPolicy
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationSnapshot
import com.vowifi.sw.manager.model.ConfigurationTransport
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object ConfigurationCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun show(output: String): ConfigurationSnapshot {
        val obj = parseObject(output)
        rejectForbiddenKeys(obj)
        rejectIdentityLeaks(obj)
        val wire = try {
            json.decodeFromJsonElement(WireShow.serializer(), obj)
        } catch (_: SerializationException) {
            throw ConfigurationFormatException.Unparseable()
        } catch (_: IllegalArgumentException) {
            throw ConfigurationFormatException.Unparseable()
        }
        if (!wire.ok) {
            throw ConfigurationFormatException.Unsuccessful()
        }
        requireValidSmsc(wire.smsc)
        val transport = transportOf(wire.transport)
        if (wire.dnsOverrides.size > MAX_DNS_OVERRIDES || wire.dnsOverrides.any { (key, value) ->
                runCatching { transportOf(key) }.isFailure || dnsServerValue(value) != value
            }
        ) throw ConfigurationFormatException.Unparseable()
        val digestSource = policySource(wire.imsDigestRealmSource.takeIf { wire.imsDigestRealm != null }, setOf("user", "carrier", "identity"), "identity")
        val digestRealm = wire.imsDigestRealm ?: wire.resolvedImsRealm
        return ConfigurationSnapshot(
            displayLabel = displayLabelOf(wire.displayLabel),
            lifecycle = lifecycleOf(wire.lifecycle),
            transport = transport,
            smsc = wire.smsc,
            epdgFqdn = wire.epdgFqdn,
            imsRealm = wire.imsRealm,
            dns = wire.dnsOverrides[wire.transport].orEmpty(),
            resolvedEpdgFqdn = wire.resolvedEpdgFqdn,
            resolvedImsRealm = wire.resolvedImsRealm,
            mcc = wire.mcc,
            mnc = wire.mnc,
            identitySource = ConfigurationIdentitySource.entries.firstOrNull { it.wireValue == wire.identitySource }
                ?: ConfigurationIdentitySource.UNKNOWN,
            dnsOverrides = wire.dnsOverrides,
            imsPolicy = ConfigurationImsPolicy(
                security = sipSecurityValue(wire.imsSipSecurity).ifEmpty { "tcp_ipsec_3gpp" },
                securitySource = policySource(wire.imsSipSecuritySource, setOf("user", "carrier", "catalog", "default"), "default"),
                initial = paniInitialValue(wire.paniInitial),
                initialSource = policySource(wire.paniInitialSource, setOf("user", "carrier", "catalog", "default"), "default"),
                country = paniCountryValue(wire.paniCountry),
                countrySource = policySource(wire.paniCountrySource, setOf("user", "carrier", "home_mcc"), "home_mcc"),
                digestRealm = if (digestSource == "identity") digestRealm else digestRealmValue(digestRealm),
                digestRealmSource = digestSource,
            ),
        )
    }

    fun encode(overrides: ConfigurationOverrides): String {
        requireValidSmsc(overrides.smsc)
        listOf(overrides.epdgFqdn, overrides.imsRealm, overrides.dns).forEach { field ->
            if (containsIdentityDigits(field)) {
                throw ConfigurationFormatException.IdentityLeak()
            }
        }
        val fields = parseObject(json.encodeToString(
            WireOverrides.serializer(),
            WireOverrides(
                transport = transportWire(overrides.transport),
                smsc = overrides.smsc,
                epdgFqdn = overrides.epdgFqdn,
                imsRealm = overrides.imsRealm,
            ),
        )).toMutableMap()
        fields["dnsOverrides"] = JsonObject(dnsOverridesFor(overrides).mapValues { JsonPrimitive(it.value) })
        overrides.imsSipSecurity?.let { fields["imsSipSecurity"] = JsonPrimitive(sipSecurityValue(it)) }
        overrides.paniInitial?.let { fields["paniInitial"] = JsonPrimitive(paniInitialValue(it)) }
        overrides.paniCountry?.let { fields["paniCountry"] = JsonPrimitive(paniCountryValue(it)) }
        overrides.imsDigestRealm?.let { fields["imsDigestRealm"] = JsonPrimitive(digestRealmValue(it)) }
        return JsonObject(fields).toString()
    }

    fun encodeChanges(previous: ConfigurationOverrides, next: ConfigurationOverrides): String {
        val before = parseObject(encode(previous))
        val after = parseObject(encode(next))
        // Unchanged empty UI overrides may represent non-empty derived values on disk.
        val payload = JsonObject(buildMap {
            (before.keys + after.keys).forEach { key ->
                val old = before[key] ?: JsonPrimitive("")
                val nextValue = after[key] ?: JsonPrimitive("")
                if (old != nextValue) put(key, nextValue)
            }
        }).toString()
        if (payload.encodeToByteArray().size > MAX_PATCH_BYTES) throw ConfigurationFormatException.PayloadTooLarge()
        return payload
    }

    fun result(output: String): ControlScriptFailure? =
        runCatching { json.decodeFromString<ControlScriptFailure>(output) }.getOrNull()

    fun failure(output: String): ControlScriptFailure? =
        result(output)?.takeUnless(ControlScriptFailure::ok)

    private fun parseObject(output: String): JsonObject {
        val element = try {
            json.parseToJsonElement(output)
        } catch (_: SerializationException) {
            throw ConfigurationFormatException.Unparseable()
        }
        return element as? JsonObject ?: throw ConfigurationFormatException.Unparseable()
    }
}

internal fun containsIdentityDigits(text: String): Boolean = IDENTITY_DIGITS.containsMatchIn(text)

internal fun transportOf(wire: String): ConfigurationTransport = when (wire) {
    "wifi" -> ConfigurationTransport.WIFI
    "sim1" -> ConfigurationTransport.SIM1
    "sim2" -> ConfigurationTransport.SIM2
    "auto" -> ConfigurationTransport.AUTO
    "route:system" -> ConfigurationTransport.SYSTEM_ROUTE
    else -> if (wire.startsWith("iface:")) ConfigurationTransport.Interface(wire.removePrefix("iface:"))
        else throw ConfigurationFormatException.InvalidTransport()
}

internal fun transportWire(transport: ConfigurationTransport): String = transport.wireValue

private fun requireValidSmsc(smsc: String) {
    if (smsc.isNotEmpty() && !smsc.startsWith("+")) {
        throw ConfigurationFormatException.InvalidSmsc()
    }
}

private fun rejectForbiddenKeys(obj: JsonObject) {
    if (obj.keys.any { it.lowercase() in FORBIDDEN_KEYS }) {
        throw ConfigurationFormatException.ForbiddenField()
    }
}

private fun rejectIdentityLeaks(element: JsonElement, key: String? = null) {
    when (element) {
        is JsonObject -> element.forEach { (childKey, child) ->
            rejectIdentityLeaks(child, childKey)
        }
        is JsonArray -> element.forEach { child ->
            rejectIdentityLeaks(child, key)
        }
        is JsonPrimitive -> {
            if (key?.lowercase() == SMSC_KEY || !element.isString) return
            if (containsIdentityDigits(element.content)) {
                throw ConfigurationFormatException.IdentityLeak()
            }
        }
    }
}

private fun displayLabelOf(wire: String): String = when {
    MASKED_LABEL.matches(wire) -> wire
    LAST_FOUR.matches(wire) -> "实例 •••• $wire"
    else -> throw ConfigurationFormatException.InvalidLabel()
}

private fun lifecycleOf(wire: String): ConfigurationLifecycle = when (wire) {
    "config_missing" -> ConfigurationLifecycle.CONFIG_MISSING
    "stopped" -> ConfigurationLifecycle.STOPPED
    "starting" -> ConfigurationLifecycle.STARTING
    "running" -> ConfigurationLifecycle.RUNNING
    "stopping" -> ConfigurationLifecycle.STOPPING
    else -> throw ConfigurationFormatException.InvalidLifecycle()
}

@Serializable
private data class WireShow(
    val ok: Boolean,
    val displayLabel: String,
    val lifecycle: String,
    val transport: String,
    val smsc: String,
    val epdgFqdn: String,
    val imsRealm: String,
    val dns: String,
    val resolvedEpdgFqdn: String,
    val resolvedImsRealm: String,
    val mcc: String,
    val mnc: String,
    val identitySource: String? = null,
    val identityReadStatus: String? = null,
    val epdgResolutionKind: String? = null,
    val imsSipSecurity: String? = null,
    val imsSipSecuritySource: String? = null,
    val paniInitial: String? = null,
    val paniInitialSource: String? = null,
    val paniCountry: String? = null,
    val paniCountrySource: String? = null,
    val imsDigestRealm: String? = null,
    val imsDigestRealmSource: String? = null,
    // Engines before per-transport DNS report no map; their single dns is retired.
    val dnsOverrides: Map<String, String> = emptyMap(),
)

@Serializable
private data class WireOverrides(
    val transport: String,
    val smsc: String,
    val epdgFqdn: String,
    val imsRealm: String,
)

private const val SMSC_KEY = "smsc"
private const val MAX_DNS_OVERRIDES = 16
private const val MAX_PATCH_BYTES = 64 * 1024
private val FORBIDDEN_KEYS = setOf("iccid", "imsi", "imei", "line1", "slot", "enabled")
private val IDENTITY_DIGITS = Regex("""\d{15,22}""")
private val MASKED_LABEL = Regex("""^实例 ••••(?: \d{4})?$""")
private val LAST_FOUR = Regex("""^\d{4}$""")

internal fun sipSecurityValue(value: String?): String = when (value.orEmpty()) {
    "", "tcp_ipsec_3gpp", "swu_udp_aka" -> value.orEmpty()
    else -> throw ConfigurationFormatException.InvalidSipSecurity()
}

internal fun paniInitialValue(value: String?): String = when (value.orEmpty()) {
    "", "absent", "wlan", "wlan-node", "wlan-country", "wlan-node-country" -> value.orEmpty()
    else -> throw ConfigurationFormatException.InvalidPaniInitial()
}

internal fun paniCountryValue(value: String?): String {
    val trimmed = value.orEmpty().trim()
    if (trimmed.isNotEmpty() && (trimmed.length != 2 || trimmed.any { it !in 'a'..'z' && it !in 'A'..'Z' })) {
        throw ConfigurationFormatException.InvalidPaniCountry()
    }
    return trimmed.uppercase()
}

/** The saved map with [ConfigurationOverrides.dns] as the selected transport's entry; blank removes it. */
internal fun dnsOverridesFor(overrides: ConfigurationOverrides): Map<String, String> {
    val key = transportWire(overrides.transport)
    val merged = overrides.dnsOverrides.mapValues { dnsServerValue(it.value) }.filterValues(String::isNotEmpty) - key
    val selected = dnsServerValue(overrides.dns)
    val result = if (selected.isEmpty()) merged else merged + (key to selected)
    if (result.size > MAX_DNS_OVERRIDES) throw ConfigurationFormatException.InvalidDns()
    return result
}

/** Normalizes a DNS override to host:port (port 53 when omitted); blank stays blank. */
internal fun dnsServerValue(value: String): String {
    val text = value.trim()
    if (text.isEmpty()) return ""
    if (isIpv4(text)) return "$text:53"
    if (isIpv6(text)) return "[$text]:53"
    val (host, port) = when {
        text.startsWith("[") -> text.substringAfter('[').substringBefore(']') to text.substringAfter("]:", "")
        else -> text.substringBeforeLast(':') to text.substringAfterLast(':', "")
    }
    val portValue = port.toIntOrNull()
    val bracketed = text.startsWith("[")
    if (portValue == null || portValue !in 1..65535 || port.length > 5 ||
        !(if (bracketed) isIpv6(host) && text == "[$host]:$port" else isIpv4(host))
    ) throw ConfigurationFormatException.InvalidDns()
    return text
}

private fun isIpv4(text: String): Boolean =
    text.split('.').let { parts -> parts.size == 4 && parts.all { it.length in 1..3 && it.toIntOrNull() in 0..255 } }

private fun isIpv6(text: String): Boolean {
    if (':' !in text || text.length > 39 || text.count { it == ':' } > 7) return false
    if (text.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' && it != ':' }) return false
    val doubles = text.windowed(2).count { it == "::" }
    if (doubles > 1 || ":::" in text) return false
    val groups = text.split(':')
    return groups.all { it.length <= 4 } && if (doubles == 1) groups.size <= 8 else groups.size == 8 && groups.none(String::isEmpty)
}

private fun policySource(value: String?, allowed: Set<String>, fallback: String): String =
    value?.takeIf { it in allowed } ?: fallback
