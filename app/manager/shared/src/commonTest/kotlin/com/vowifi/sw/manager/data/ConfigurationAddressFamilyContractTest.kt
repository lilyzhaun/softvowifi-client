package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationAddressFamilyContractTest {
    @Test fun parsedAndLoadedFactsKeepUnknownCompatibilityAndExplicitChoicesDistinct() {
        assertNull(ConfigurationCodec.show(show(null)).epdgAddressFamily)
        assertNull(ConfigurationCodec.show(show("""{"field":"epdgAddressFamily","state":"unknown","source":"user"}""")).epdgAddressFamily)
        assertEquals("", ConfigurationCodec.show(show(fact("compatible", "default"))).epdgAddressFamily)
        for (value in listOf("prefer-ipv4", "prefer-ipv6", "ipv4-only", "ipv6-only")) {
            val row = ConfigurationCodec.show(show(fact(value, "user", value)))
            assertEquals(value, row.epdgAddressFamily)
            assertEquals(value, ConfigurationEvaluator.instance(ConfigurationInstanceId("opaque"), row).epdgAddressFamily)
        }
        val runtime = ConfigurationFactsCodec.runtime(buildJsonObject {
            put("configuration", buildJsonObject {
                put("schema", 1); put("loaded", facts(fact("ipv4-only", "user", "ipv4-only")))
                put("negotiated", JsonObject(emptyMap()))
            })
        })
        assertEquals("ipv4-only", ConfigurationAddressFamilyCodec.read(runtime?.loaded))
        assertEquals("ipv6-only", ConfigurationCodec.show(show(fact("ipv6-only", "user", "ipv6-only"))).epdgAddressFamily)
    }

    @Test fun revisionDeltaPreservesUnknownAndOnlyExplicitClearWritesNull() {
        val baseline = overrides("")
        val selected = baseline.copy(epdgAddressFamily = "ipv4-only")
        val request = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(baseline, selected)).jsonObject
        assertEquals(REVISION, request.getValue("expectedRevision").jsonPrimitive.content)
        assertEquals(JsonObject(mapOf("epdgAddressFamily" to JsonPrimitive("ipv4-only"))), request["overrides"])
        assertEquals(JsonNull, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(selected, baseline)).jsonObject["epdgAddressFamily"])
        assertEquals(JsonObject(emptyMap()), Json.parseToJsonElement(ConfigurationCodec.encodeChanges(selected, selected.copy(epdgAddressFamily = null))))
        assertFailsWith<ConfigurationFormatException.UnknownAddressFamily> { ConfigurationCodec.encodeChanges(overrides(null), selected) }
        assertEquals(JsonNull, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(overrides(null), baseline)).jsonObject["epdgAddressFamily"])
        val call = Json.parseToJsonElement(ConfigurationCodec.encodeChanges(selected, selected.copy(callSignaling = ConfigurationCallSignaling(includePANI = false)))).jsonObject
        assertEquals(setOf("callSignaling"), call.keys)
    }

    @Test fun malformedOrContradictoryFactsNeverTurnIntoInheritedDefaults() {
        for (bad in listOf("unknown", "compatible", " ipv4-only", "IPV6-ONLY")) {
            assertFailsWith<ConfigurationFormatException.InvalidAddressFamily> { ConfigurationCodec.encode(overrides(bad)) }
        }
        for (bad in listOf(fact("compatible", "user", "compatible"), fact("ipv4-only", "user", "ipv6-only"),
            fact("ipv4-only", "default"), fact("ipv4-only", "user"), fact("ipv4-only", "catalog", "ipv4-only"))) {
            assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.show(show(bad)) }
        }
    }

    private fun overrides(value: String?) = ConfigurationOverrides(ConfigurationTransport.WIFI, "", "", "", "",
        expectedRevision = REVISION, callSignaling = ConfigurationCallSignaling(), epdgAddressFamily = value)
    private fun fact(value: String, source: String, override: String? = null) = buildJsonObject {
        put("field", "epdgAddressFamily"); put("state", "known"); put("source", source); put("value", value)
        override?.let { put("override", it) }
    }.toString()
    private fun facts(field: String?) = buildJsonObject {
        put("schema", 1); put("configRevision", REVISION); put("resolutionRevision", "rf1_" + "a".repeat(64))
        put("fields", JsonArray(listOfNotNull(field?.let { Json.parseToJsonElement(it) })))
    }
    private fun show(field: String?) = buildJsonObject {
        put("ok", true); put("formatState", "current"); put("configVersion", 1); put("configRevision", REVISION)
        put("displayLabel", "0001"); put("lifecycle", "stopped"); put("transport", "wifi")
        listOf("smsc", "epdgFqdn", "imsRealm", "dns").forEach { put(it, "") }
        put("resolvedEpdgFqdn", "epdg.example"); put("resolvedImsRealm", "ims.example")
        put("mcc", "001"); put("mnc", "01"); put("configuration", facts(field))
    }.toString()
    private companion object { const val REVISION = "r1_abcdefghijklmnopabcdefghijklmnop" }
}
