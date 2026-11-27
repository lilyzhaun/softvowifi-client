package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationSignalingOverridesTest {
    @Test fun allSeventeenExplicitLeavesProduceOneTypedAtomicGroup() {
        val before = overrides(ConfigurationCallSignaling())
        val next = overrides(complete)
        val encoded = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(before, next)).jsonObject
        assertEquals(JsonPrimitive(revision), encoded["expectedRevision"])
        assertEquals(setOf("callSignaling"), encoded.getValue("overrides").jsonObject.keys)
        assertEquals(expected, encoded.getValue("overrides").jsonObject["callSignaling"])
        assertFalse(ConfigurationCodec.encode(next).contains("ConfigurationCallSignaling"))
    }

    @Test fun readingFactsRecoversOnlyExplicitLeavesNotResolvedDefaults() {
        val snapshot = ConfigurationCodec.show(show(facts(mapOf(
            "includePANI" to "false", "addressing.phoneContext" to "context.example",
        ))))
        assertEquals(ConfigurationCallSignaling(
            includePANI = false, addressing = ConfigurationCallAddressing(phoneContext = "context.example"),
        ), snapshot.callSignaling)
        val restored = Json.parseToJsonElement(ConfigurationCodec.encode(overrides(snapshot.callSignaling))).jsonObject
        assertEquals(Json.parseToJsonElement("""{"includePANI":false,"addressing":{"phoneContext":"context.example"}}"""), restored["callSignaling"])
        assertFalse(restored.getValue("callSignaling").toString().contains("update"))
        assertEquals(ConfigurationCallSignaling(), ConfigurationCodec.show(show(facts())).callSignaling)
        // Both are valid booleans, but only overrideValue may be persisted.
        val different = facts(mapOf("includePANI" to "false"))
        val differentFields = different.getValue("fields").jsonArray.map { element ->
            val field = element.jsonObject
            if (field["field"] == JsonPrimitive("callSignaling.includePANI")) JsonObject(field + ("value" to JsonPrimitive("true"))) else field
        }
        assertEquals(ConfigurationCallSignaling(includePANI = false),
            ConfigurationCodec.show(show(JsonObject(different + ("fields" to JsonArray(differentFields))))).callSignaling)
    }

    @Test fun missingOrHiddenFactsDoNotBecomeAnEmptyAuthoritativeGroup() {
        assertNull(ConfigurationCodec.show(show(null)).callSignaling)
        for (mode in listOf("missing", "hidden", "source", "missing_override")) {
            val original = facts(mapOf("addressing.phoneContext" to "context.example"))
            val fields = original.getValue("fields").jsonArray.mapNotNull { element ->
                val field = element.jsonObject
                if (field["field"] != JsonPrimitive("callSignaling.addressing.phoneContext")) field else when (mode) {
                    "missing" -> null
                    "hidden" -> JsonObject((field - "override") + mapOf("state" to JsonPrimitive("unknown"), "value" to JsonPrimitive("")))
                    "source" -> JsonObject(field + ("source" to JsonPrimitive("diagnostic")))
                    else -> JsonObject(field - "override")
                }
            }
            val input = JsonObject(original + ("fields" to JsonArray(fields)))
            assertNull(ConfigurationCodec.show(show(input)).callSignaling, mode)
        }
    }

    @Test fun unknownPreservationAndExplicitWholeGroupClearHaveDifferentWireMeaning() {
        val saved = overrides(complete)
        assertEquals(JsonObject(emptyMap()), changes(saved, saved.copy(smsc = saved.smsc, callSignaling = null)))
        assertEquals(JsonNull, changes(saved, overrides(ConfigurationCallSignaling()))["callSignaling"])
        assertEquals(JsonNull, changes(overrides(null), overrides(ConfigurationCallSignaling()))["callSignaling"])
        assertFailsWith<ConfigurationFormatException> {
            ConfigurationCodec.encodeRevisionChanges(overrides(null), overrides(ConfigurationCallSignaling(includePANI = false)))
        }
        val unrelated = changes(overrides(null), overrides(null).copy(epdgFqdn = "new.epdg.example"))
        assertEquals(setOf("epdgFqdn"), unrelated.keys)
    }

    @Test fun fullGroupReplacementRetainsUneditedExplicitChoicesAndOmitsInheritedLeaves() {
        val before = overrides(complete)
        val after = before.copy(callSignaling = complete.copy(includePANI = true))
        assertEquals(JsonObject(expected + ("includePANI" to JsonPrimitive(true))), changes(before, after)["callSignaling"])
        assertEquals(JsonObject(emptyMap()), changes(before, before))
        val sparse = overrides(ConfigurationCallSignaling(includePANI = false))
        assertEquals(Json.parseToJsonElement("""{"callSignaling":{"includePANI":false}}"""), changes(overrides(ConfigurationCallSignaling()), sparse))
    }

    @Test fun invalidExplicitScalarsAndConflictingGroupsAreRejectedBeforeSubmission() {
        for (invalid in listOf(
            ConfigurationCallSignaling(sessionTimer = ConfigurationSessionTimer(expiresSeconds = 89)),
            ConfigurationCallSignaling(sessionTimer = ConfigurationSessionTimer(expiresSeconds = 120, minimumSeconds = 180)),
            ConfigurationCallSignaling(sessionTimer = ConfigurationSessionTimer(enabled = false, requirePeer = true)),
            ConfigurationCallSignaling(sessionTimer = ConfigurationSessionTimer(refreshMethod = "update")),
            ConfigurationCallSignaling(sessionTimer = ConfigurationSessionTimer(refresher = "local")),
            ConfigurationCallSignaling(reliableProvisional = ConfigurationReliableProvisional(uacEnabled = false, requirePeer = true)),
            ConfigurationCallSignaling(reliableProvisional = ConfigurationReliableProvisional(uasMode = "183")),
            ConfigurationCallSignaling(reliableProvisional = ConfigurationReliableProvisional(uasMode = "disabled"), preconditions = ConfigurationPreconditions(uasEnabled = true)),
            ConfigurationCallSignaling(preconditions = ConfigurationPreconditions(uacEnabled = false, requirePeer = true)),
            ConfigurationCallSignaling(reliableProvisional = ConfigurationReliableProvisional(uacEnabled = false), preconditions = ConfigurationPreconditions(uacEnabled = true)),
            ConfigurationCallSignaling(addressing = ConfigurationCallAddressing(requestURI = "sips")),
            ConfigurationCallSignaling(addressing = ConfigurationCallAddressing(contactTransport = "tcp")),
            ConfigurationCallSignaling(addressing = ConfigurationCallAddressing(phoneContext = "bad.example;injected=1")),
            ConfigurationCallSignaling(addressing = ConfigurationCallAddressing(phoneContext = "001010000000001.private.invalid")),
        )) assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.encode(overrides(invalid)) }
    }

    @Test fun projectedInstancesKeepTheSameExplicitPolicyWithoutLoggingIt() {
        val snapshot = ConfigurationCodec.show(show(facts(explicit)))
        assertEquals(complete, snapshot.callSignaling, "show must recover the complete input before evaluator mapping")
        val instance = ConfigurationEvaluator.instance(ConfigurationInstanceId("opaque-owner"), snapshot)
        assertEquals(complete, instance.callSignaling)
        for (value in listOf(complete, complete.addressing, overrides(complete), snapshot, instance)) {
            assertFalse(value.toString().contains("context.example"))
        }
    }

    @Test fun knownCatalogPolicyRemainsInheritedAndDoesNotBlockAnExplicitUserLeaf() {
        fun catalogFacts(userPani: Boolean = false) = buildJsonObject {
            val source = facts(if (userPani) mapOf("includePANI" to "false") else emptyMap())
            source.forEach { (key, value) -> if (key != "fields") put(key, value) }
            put("fields", JsonArray(source.getValue("fields").jsonArray.map { element ->
                val field = element.jsonObject
                when (field.getValue("field").jsonPrimitive.content) {
                    "callSignaling.includePANI" -> if (userPani) field else JsonObject(field + ("source" to JsonPrimitive("catalog")))
                    "callSignaling.sessionTimer.refreshMethod" -> JsonObject(field + mapOf("source" to JsonPrimitive("catalog"), "value" to JsonPrimitive("prefer-update")))
                    "callSignaling.reliableProvisional.uasMode" -> JsonObject(field + mapOf("source" to JsonPrimitive("catalog"), "value" to JsonPrimitive("all-18x")))
                    else -> field
                }
            }))
        }
        val inherited = ConfigurationCodec.show(show(catalogFacts())).callSignaling
        assertEquals(ConfigurationCallSignaling(), inherited)
        assertEquals(JsonNull, Json.parseToJsonElement(ConfigurationCodec.encode(overrides(inherited))).jsonObject["callSignaling"])
        val explicit = ConfigurationCodec.show(show(catalogFacts(userPani = true))).callSignaling
        assertEquals(ConfigurationCallSignaling(includePANI = false), explicit)
        assertEquals(Json.parseToJsonElement("""{"includePANI":false}"""),
            Json.parseToJsonElement(ConfigurationCodec.encode(overrides(explicit))).jsonObject["callSignaling"])
        val uas = overrides(inherited!!.copy(preconditions = ConfigurationPreconditions(uasEnabled = true)))
        assertEquals(Json.parseToJsonElement("""{"callSignaling":{"preconditions":{"uasEnabled":true}}}"""), changes(overrides(inherited), uas))
        // A catalog leaf carrying a fake user override is not authoritative.
        val input = catalogFacts()
        val badFields = input.getValue("fields").jsonArray.map { element ->
            val field = element.jsonObject
            if (field["field"] == JsonPrimitive("callSignaling.includePANI")) JsonObject(field + ("override" to JsonPrimitive("false"))) else field
        }
        assertNull(ConfigurationCodec.show(show(JsonObject(input + ("fields" to JsonArray(badFields))))).callSignaling)
    }

    private fun changes(before: ConfigurationOverrides, after: ConfigurationOverrides) =
        Json.parseToJsonElement(ConfigurationCodec.encodeChanges(before, after)).jsonObject

    private fun overrides(signaling: ConfigurationCallSignaling?) = ConfigurationOverrides(
        ConfigurationTransport.WIFI, "", "", "", "", expectedRevision = revision, callSignaling = signaling,
    )

    private fun show(configuration: JsonObject?) = buildJsonObject {
        put("ok", true); put("formatState", "current"); put("configVersion", 1); put("configRevision", revision)
        put("displayLabel", "0001"); put("lifecycle", "stopped"); put("transport", "wifi")
        for (key in listOf("smsc", "epdgFqdn", "imsRealm", "dns")) put(key, "")
        put("resolvedEpdgFqdn", "epdg.example"); put("resolvedImsRealm", "ims.example"); put("mcc", "001"); put("mnc", "01")
        configuration?.let { put("configuration", it) }
    }.toString()

    private fun facts(overrides: Map<String, String> = emptyMap()) = buildJsonObject {
        put("schema", 1); put("configRevision", revision); put("resolutionRevision", "rf1_" + "a".repeat(64))
        put("fields", buildJsonArray {
            defaults.forEach { (leaf, value) -> add(buildJsonObject {
                put("field", "callSignaling.$leaf"); put("state", "known")
                put("source", if (leaf in overrides) "user" else "default")
                put("value", overrides[leaf] ?: value)
                overrides[leaf]?.let { put("override", it) }
            }) }
        })
    }

    private val revision = "r1_abcdefghijklmnopabcdefghijklmnop"
    private val complete = ConfigurationCallSignaling(
        ConfigurationSessionTimer(true, 1200, 120, "uas", "invite", true),
        ConfigurationReliableProvisional(true, true, "all-18x"),
        ConfigurationPreconditions(true, true, true, false),
        ConfigurationCallAddressing("sip", "context.example", "selected"), false,
    )
    private val expected = Json.parseToJsonElement("""{"sessionTimer":{"enabled":true,"expiresSeconds":1200,"minimumSeconds":120,"refresher":"uas","refreshMethod":"invite","requirePeer":true},"reliableProvisional":{"uacEnabled":true,"requirePeer":true,"uasMode":"all-18x"},"preconditions":{"uacEnabled":true,"uasEnabled":true,"requirePeer":true,"wifiAllowed":false},"addressing":{"requestURI":"sip","phoneContext":"context.example","contactTransport":"selected"},"includePANI":false}""").jsonObject
    private val explicit = mapOf(
        "sessionTimer.enabled" to "true", "sessionTimer.expiresSeconds" to "1200", "sessionTimer.minimumSeconds" to "120",
        "sessionTimer.refresher" to "uas", "sessionTimer.refreshMethod" to "invite", "sessionTimer.requirePeer" to "true",
        "reliableProvisional.uacEnabled" to "true", "reliableProvisional.requirePeer" to "true", "reliableProvisional.uasMode" to "all-18x",
        "preconditions.uacEnabled" to "true", "preconditions.uasEnabled" to "true", "preconditions.requirePeer" to "true", "preconditions.wifiAllowed" to "false",
        "addressing.requestURI" to "sip", "addressing.phoneContext" to "context.example", "addressing.contactTransport" to "selected", "includePANI" to "false",
    )
    private val defaults = explicit + mapOf(
        "sessionTimer.expiresSeconds" to "1800", "sessionTimer.minimumSeconds" to "90", "sessionTimer.refresher" to "uac",
        "sessionTimer.refreshMethod" to "update", "sessionTimer.requirePeer" to "false", "reliableProvisional.requirePeer" to "false",
        "reliableProvisional.uasMode" to "disabled", "preconditions.uasEnabled" to "false", "preconditions.requirePeer" to "false", "preconditions.wifiAllowed" to "true",
        "addressing.requestURI" to "preserve", "addressing.phoneContext" to "home-domain", "addressing.contactTransport" to "omit", "includePANI" to "true",
    )
}
