package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFact
import com.vowifi.sw.manager.model.ConfigurationCallFacts
import com.vowifi.sw.manager.model.ConfigurationFacts
import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationRuntimeFacts
import kotlinx.serialization.json.*

/** Validate the IPC boundary once; absent/unsupported optional facts stay unknown. */
internal object ConfigurationFactsCodec {
    fun local(element: JsonElement?, expectedRevision: String? = null): ConfigurationFacts? {
        if (element == null) return null
        val obj = element as? JsonObject ?: invalid()
        if (obj["schema"]?.let { it as? JsonPrimitive }?.takeUnless { it.isString }?.intOrNull != 1) return null
        if (obj.keys.any { it !in setOf("schema", "configRevision", "resolutionRevision", "fields", "callMedia", "tunnelSecurity", "tunnelTiming", "imsProtection", "epdgSelection", "tunnelIdentity", "imsTiming", "swuAccess") }) invalid()
        val revision = obj.string("configRevision")
        if (!validConfigRevision(revision) || (expectedRevision != null && expectedRevision != revision)) invalid()
        val resolution = obj.string("resolutionRevision") ?: invalid()
        if (resolution.length != 68 || !resolution.startsWith("rf1_") || !resolution.drop(4).all { it in 'a'..'p' }) invalid()
        val fields = (obj["fields"] as? JsonArray)?.takeIf { it.size <= FIELD_NAMES.size } ?: invalid()
        val parsed = fields.map { element ->
            val field = element as? JsonObject ?: invalid()
            if (field.keys.any { it !in setOf("field", "state", "source", "value", "override") }) invalid()
            val name = field.string("field")?.takeIf { it in FIELD_NAMES } ?: invalid()
            val source = field.string("source")?.takeIf { it in SOURCES } ?: invalid()
            val state = field.string("state")?.takeIf { it == "known" || it == "unknown" } ?: invalid()
            val raw = field.string("value")
            val override = field.string("override")
            if (("override" in field && override == null) || ("value" in field && raw == null)) invalid()
            if (state == "unknown" && (!raw.isNullOrEmpty() || override != null)) invalid()
            if (state == "known" && raw == null) invalid()
            listOfNotNull(raw, override).forEach { value ->
                if (value.length > 253 || value.any { it.isISOControl() } || (name != "smsc" && containsIdentityDigits(value))) invalid()
                if (name == "smsc" && value.isNotEmpty() && (value.length > 22 || !value.startsWith('+') || !value.drop(1).all { it in '0'..'9' })) invalid()
                if (value.isNotEmpty() || state == "known" && name.startsWith("callSignaling.")) validateScalar(name, value)
            }
            if (name == "callSignaling.sessionTimer.refreshMethod" && (override == "update" || raw == "update" && (source != "default" || override != null))) invalid()
            ConfigurationFact(name, raw.takeIf { state == "known" }, source, override).also {
                if (name == ConfigurationAddressFamilyCodec.FIELD) ConfigurationAddressFamilyCodec.validateFact(it)
            }
        }
        if (parsed.map { it.field }.distinct().size != parsed.size) invalid()
        return ConfigurationFacts(revision, resolution, parsed, ConfigurationCallMediaCodec.facts(obj["callMedia"]),
            ConfigurationTunnelSecurityCodec.facts(obj["tunnelSecurity"]), ConfigurationTunnelTimingCodec.facts(obj["tunnelTiming"]),
            ConfigurationImsProtectionCodec.facts(obj["imsProtection"]), ConfigurationEpdgSelectionCodec.facts(obj["epdgSelection"]),
            ConfigurationTunnelIdentityCodec.facts(obj["tunnelIdentity"]), ConfigurationImsTimingCodec.facts(obj["imsTiming"]),
            ConfigurationSwuAccessCodec.facts(obj["swuAccess"]))
    }

    fun runtime(status: JsonObject): ConfigurationRuntimeFacts? {
        val obj = status["configuration"] as? JsonObject ?: return null
        if ((obj["schema"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull != 1) return null
        if (obj.keys.any { it !in setOf("schema", "loaded", "negotiated", "call", "tunnel", "epdgSelection", "identity") }) invalid()
        val loaded = local(obj["loaded"]) ?: return null
        val negotiated = obj["negotiated"] as? JsonObject ?: invalid()
        if (negotiated.keys.any { it !in setOf("registrationSeconds", "integrity", "encryption") }) invalid()
        val seconds = negotiated["registrationSeconds"]?.let {
            // RFC 3261 section 20.19 delta-seconds is unsigned 32-bit (1..4294967295).
            (it as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { value -> value in 1L..0xFFFF_FFFFL } ?: invalid()
        }
        val integrity = negotiated.string("integrity")?.takeIf { it in setOf("hmac-sha-1-96", "hmac-md5-96") }
        val encryption = negotiated.string("encryption")?.takeIf { it in setOf("null", "aes-cbc", "des-ede3-cbc") }
        if (("integrity" in negotiated && integrity == null) || ("encryption" in negotiated && encryption == null)) invalid()
        if (seconds == null && (integrity != null || encryption != null)) invalid()
        val tunnel = ConfigurationNegotiatedTunnelCodec.decode(obj["tunnel"])
            .takeIf { status.string("tunnel") == "CONNECTED" }
        val selection = ConfigurationEpdgSelectionCodec.connection(obj["epdgSelection"], loaded.epdgSelection)
            .takeIf { status.string("tunnel") == "CONNECTED" }
        val identity = ConfigurationTunnelIdentityCodec.connection(obj["identity"], loaded.tunnelIdentity)
            .takeIf { status.string("tunnel") == "CONNECTED" }
        return ConfigurationRuntimeFacts(loaded, seconds, integrity, encryption, obj["call"]?.let(::call), tunnel, selection, identity)
    }

    private fun call(element: JsonElement): ConfigurationCallFacts {
        val obj = element as? JsonObject ?: invalid()
        if (obj.keys.any { it !in setOf("configRevision", "sessionSeconds", "sessionRefresher", "media") }) invalid()
        val revision = obj.string("configRevision")?.takeIf(::validConfigRevision) ?: invalid()
        val seconds = obj["sessionSeconds"]?.let {
            (it as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { value -> value in 90..Int.MAX_VALUE.toLong() } ?: invalid()
        }
        val refresher = obj.string("sessionRefresher")?.takeIf { it == "local" || it == "remote" }
        if (("sessionRefresher" in obj && refresher == null) || (seconds == null) != (refresher == null)) invalid()
        return ConfigurationCallFacts(revision, seconds, refresher, obj["media"]?.let(ConfigurationRuntimeMediaCodec::decode))
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun invalid(): Nothing = throw ConfigurationFormatException.Unparseable()

    internal fun validateSignalingOverride(name: String, value: String) {
        if (value.length > 253 || value.any { it.isISOControl() } || containsIdentityDigits(value) ||
            name == "sessionTimer.refreshMethod" && value == "update"
        ) throw ConfigurationFormatException.InvalidCallSignaling()
        validateScalar("callSignaling.$name", value)
    }

    private fun validateScalar(name: String, value: String) {
        val valid = when (name) {
            "transport" -> transportOf(value).wireValue == value
            "imsSipSecurity" -> sipSecurityValue(value) == value
            "paniInitial", "paniAuthenticated" -> paniInitialValue(value) == value
            "paniCountry" -> paniCountryValue(value) == value
            "ikeProposalLayout" -> value in setOf("multiple-complete", "single-combined")
            "ikeDHGroup" -> value in setOf("2", "14")
            "protectedTCPMSS" -> value.toIntOrNull()?.let { it == 0 || it in 1..65535 } == true
            "dtmfNarrowband", "dtmfWideband" -> value.toIntOrNull()?.let { it in 0..127 } == true
            "dns" -> dnsServerValue(value) == value
            "callSignaling.sessionTimer.enabled", "callSignaling.sessionTimer.requirePeer",
            "callSignaling.reliableProvisional.uacEnabled", "callSignaling.reliableProvisional.requirePeer",
            "callSignaling.preconditions.uacEnabled", "callSignaling.preconditions.uasEnabled",
            "callSignaling.preconditions.requirePeer", "callSignaling.preconditions.wifiAllowed",
            "callSignaling.includePANI" -> value == "true" || value == "false"
            "callSignaling.sessionTimer.expiresSeconds", "callSignaling.sessionTimer.minimumSeconds" ->
                value.toLongOrNull()?.let { it in 90..Int.MAX_VALUE.toLong() } == true
            "callSignaling.sessionTimer.refresher" -> value in setOf("omit", "uac", "uas")
            "callSignaling.sessionTimer.refreshMethod" -> value in setOf("update", "invite", "prefer-update")
            "callSignaling.reliableProvisional.uasMode" -> value in setOf("disabled", "sdp-only", "all-18x")
            "callSignaling.addressing.requestURI" -> value in setOf("preserve", "sip", "tel")
            "callSignaling.addressing.contactTransport" -> value in setOf("omit", "selected")
            "callSignaling.addressing.phoneContext" -> if (value.startsWith('+')) {
                value.length in 2..16 && value.drop(1).all { it in '0'..'9' }
            } else {
                digestRealmValue(value) == value && value.removeSuffix(".").substringAfterLast('.').firstOrNull()?.let { it in 'a'..'z' || it in 'A'..'Z' } == true
            }
            else -> true
        }
        if (!valid) invalid()
    }

    private val SOURCES = setOf("user", "saved", "catalog", "carrier", "default", "formula", "home_mcc", "identity", "sim", "network", "diagnostic")
    internal val CALL_SIGNALING_FIELDS = setOf(
        "sessionTimer.enabled", "sessionTimer.expiresSeconds", "sessionTimer.minimumSeconds", "sessionTimer.refresher", "sessionTimer.refreshMethod", "sessionTimer.requirePeer",
        "reliableProvisional.uacEnabled", "reliableProvisional.requirePeer", "reliableProvisional.uasMode",
        "preconditions.uacEnabled", "preconditions.uasEnabled", "preconditions.requirePeer", "preconditions.wifiAllowed",
        "addressing.requestURI", "addressing.phoneContext", "addressing.contactTransport", "includePANI",
    )
    private val FIELD_NAMES = setOf("transport", "epdgFqdn", "epdgAddressFamily", "imsRealm", "ikeProposalLayout", "ikeDHGroup", "protectedTCPMSS",
        "imsSipSecurity", "paniInitial", "paniAuthenticated", "paniCountry", "dtmfNarrowband", "dtmfWideband", "dns", "smsc", "imsDigestRealm") + CALL_SIGNALING_FIELDS.map { "callSignaling.$it" }
}
