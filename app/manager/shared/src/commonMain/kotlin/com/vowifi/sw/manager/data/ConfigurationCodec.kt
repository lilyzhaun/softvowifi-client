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
        val digestSource = policySource(wire.imsDigestRealmSource.takeIf { wire.imsDigestRealm != null }, setOf("user", "carrier", "identity"), "identity")
        val digestRealm = wire.imsDigestRealm ?: wire.resolvedImsRealm
        return ConfigurationSnapshot(
            displayLabel = displayLabelOf(wire.displayLabel),
            lifecycle = lifecycleOf(wire.lifecycle),
            transport = transportOf(wire.transport),
            smsc = wire.smsc,
            epdgFqdn = wire.epdgFqdn,
            imsRealm = wire.imsRealm,
            dns = wire.dns,
            resolvedEpdgFqdn = wire.resolvedEpdgFqdn,
            resolvedImsRealm = wire.resolvedImsRealm,
            mcc = wire.mcc,
            mnc = wire.mnc,
            identitySource = ConfigurationIdentitySource.entries.firstOrNull { it.wireValue == wire.identitySource }
                ?: ConfigurationIdentitySource.UNKNOWN,
            imsPolicy = ConfigurationImsPolicy(
                security = sipSecurityValue(wire.imsSipSecurity).ifEmpty { "tcp_ipsec_3gpp" },
                securitySource = policySource(wire.imsSipSecuritySource, setOf("user", "carrier", "default"), "default"),
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
                dns = overrides.dns,
            ),
        )).toMutableMap()
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
    else -> throw ConfigurationFormatException.InvalidTransport()
}

internal fun transportWire(transport: ConfigurationTransport): String = when (transport) {
    ConfigurationTransport.WIFI -> "wifi"
    ConfigurationTransport.SIM1 -> "sim1"
    ConfigurationTransport.SIM2 -> "sim2"
    ConfigurationTransport.AUTO -> "auto"
}

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
)

@Serializable
private data class WireOverrides(
    val transport: String,
    val smsc: String,
    val epdgFqdn: String,
    val imsRealm: String,
    val dns: String,
)

private const val SMSC_KEY = "smsc"
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

private fun policySource(value: String?, allowed: Set<String>, fallback: String): String =
    value?.takeIf { it in allowed } ?: fallback
