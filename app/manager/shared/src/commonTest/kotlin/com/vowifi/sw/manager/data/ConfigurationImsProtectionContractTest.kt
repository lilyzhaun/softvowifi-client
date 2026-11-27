package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.imsProtectionFactsSummary
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationImsProtectionContractTest {
    @Test fun catalogProtectionIsReadableLocallyAndLoadedWithoutBecomingAnOverride() {
        val snapshot = ConfigurationCodec.show(show("""{"state":"known","source":"catalog","value":$GROUP}"""))
        assertEquals(ConfigurationImsProtection(), snapshot.imsProtection)
        assertEquals(ConfigurationImsProtection(), ConfigurationEvaluator.instance(ConfigurationInstanceId("opaque"), snapshot).imsProtection)
        val facts = snapshot.facts!!.imsProtection!!
        assertEquals(SELECTED, facts.value)
        assertNull(facts.overrideValue)
        assertTrue(imsProtectionFactsSummary(facts).startsWith("运营商目录"))
        assertEquals("{}", ConfigurationCodec.encodeChanges(overrides(snapshot.imsProtection), overrides(snapshot.imsProtection)))
        val loaded = Json.parseToJsonElement(show("""{"state":"known","source":"catalog","value":$GROUP}""")).jsonObject.getValue("configuration")
        val runtime = ConfigurationFactsCodec.runtime(buildJsonObject {
            put("tunnel", "CONNECTED")
            put("configuration", buildJsonObject {
                put("schema", 1); put("loaded", loaded); put("negotiated", buildJsonObject {})
            })
        })!!
        assertEquals(facts, runtime.loaded.imsProtection)
        assertNull(runtime.integrity)
        assertNull(runtime.encryption)
        assertFalse(ConfigurationImsProtectionCodec.facts(Json.parseToJsonElement("""{"state":"unknown","source":"catalog"}"""))!!.known)
        for (bad in listOf(
            """{"state":"known","source":"catalog","value":$GROUP,"override":$GROUP}""",
            """{"state":"known","source":"catalog","value":$GROUP,"override":null}""",
            """{"state":"known","source":"catalog"}""",
        )) assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.show(show(bad)) }
    }

    @Test fun exactCompleteGroupHasSeparateParsedLoadedAndUnknownFacts() {
        val snapshot = ConfigurationCodec.show(show("""{"state":"known","source":"user","value":$GROUP,"override":$GROUP}"""))
        assertEquals(SELECTED, snapshot.imsProtection)
        assertEquals(SELECTED, ConfigurationEvaluator.instance(ConfigurationInstanceId("opaque"), snapshot).imsProtection)
        for (baseline in listOf("ims-esp-compatible-v1", "swu-only-v1")) {
            val parsed = ConfigurationCodec.show(show("""{"state":"known","source":"default","baseline":"$baseline"}"""))
            assertEquals(ConfigurationImsProtection(), parsed.imsProtection)
            assertEquals(baseline, parsed.facts?.imsProtection?.baseline)
        }
        assertNull(ConfigurationCodec.show(show(null)).imsProtection)
        assertNull(ConfigurationCodec.show(show("""{"state":"unknown","source":"user"}""")).imsProtection)
        val local = snapshot.facts!!
        assertEquals(SELECTED, local.imsProtection?.value)
        assertEquals(SELECTED, local.imsProtection?.overrideValue)
        assertEquals("ConfigurationImsProtection", SELECTED.toString())
    }

    @Test fun exactDeltaClearAndUnknownPreservationUseTheOriginalRevisionContract() {
        val base = overrides(ConfigurationImsProtection())
        val selected = base.copy(imsProtection = SELECTED)
        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(base, selected)).jsonObject
        assertEquals(REVISION, patch.getValue("expectedRevision").jsonPrimitive.content)
        assertEquals(setOf("imsProtection"), patch.getValue("overrides").jsonObject.keys)
        assertEquals(Json.parseToJsonElement(GROUP), patch.getValue("overrides").jsonObject["imsProtection"])
        assertEquals(JsonNull, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(selected, base)).jsonObject["imsProtection"])
        assertEquals(JsonObject(emptyMap()), Json.parseToJsonElement(ConfigurationCodec.encodeChanges(selected, selected.copy(imsProtection = null))))
        assertFailsWith<ConfigurationFormatException.UnknownImsProtection> { ConfigurationCodec.encodeChanges(base.copy(imsProtection = null), selected) }
        assertEquals(JsonNull, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(base.copy(imsProtection = null), base)).jsonObject["imsProtection"])
        val callOnly = Json.parseToJsonElement(ConfigurationCodec.encodeChanges(selected, selected.copy(callSignaling = ConfigurationCallSignaling(includePANI = false)))).jsonObject
        assertEquals(setOf("callSignaling"), callOnly.keys)
    }

    @Test fun malformedGroupsUnknownSuitesAndConflictingTransportNeverBecomeDefaults() {
        for (raw in listOf("null", "[]", "{}", """{"suites":null}""", """{"suites":[]}""",
            """{"suites":[0]}""", """{"suites":["unknown"]}""", """{"suites":["esp-null-sha1-96","esp-null-sha1-96"]}""",
            """{"suites":["esp-null-sha1-96"],"key":"not-public"}""")) {
            assertFailsWith<ConfigurationFormatException> { ConfigurationImsProtectionCodec.decode(Json.parseToJsonElement(raw)) }
        }
        assertFailsWith<ConfigurationFormatException> { ConfigurationImsProtectionCodec.encode(ConfigurationImsProtection(emptyList())) }
        assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.encode(overrides(SELECTED).copy(imsSipSecurity = "swu_udp_aka")) }
        assertEquals(JsonNull, Json.parseToJsonElement(ConfigurationCodec.encode(overrides(ConfigurationImsProtection()).copy(imsSipSecurity = "swu_udp_aka"))).jsonObject["imsProtection"])
        for (raw in listOf(
            """{"state":"known","source":"default","baseline":"unknown"}""",
            """{"state":"unknown","source":"user","value":$GROUP}""",
            """{"state":"known","source":"user","value":$GROUP,"override":{"suites":["esp-null-sha1-96"]}}""",
        )) assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.show(show(raw)) }
    }

    private fun overrides(value: ConfigurationImsProtection?) = ConfigurationOverrides(ConfigurationTransport.WIFI, "", "", "", "",
        expectedRevision = REVISION, callSignaling = ConfigurationCallSignaling(), imsProtection = value)
    private fun show(protection: String?): String = buildJsonObject {
        put("ok", true); put("formatState", "current")
        put("displayLabel", "0001"); put("lifecycle", "stopped"); put("transport", "wifi")
        put("smsc", ""); put("epdgFqdn", ""); put("imsRealm", ""); put("dns", "")
        put("resolvedEpdgFqdn", "epdg.example"); put("resolvedImsRealm", "ims.example")
        put("mcc", "001"); put("mnc", "01"); put("configVersion", 1); put("configRevision", REVISION)
        put("configuration", buildJsonObject {
            put("schema", 1); put("configRevision", REVISION); put("resolutionRevision", "rf1_" + "a".repeat(64))
            put("fields", JsonArray(emptyList())); protection?.let { put("imsProtection", Json.parseToJsonElement(it)) }
        })
    }.toString()
    private companion object {
        const val REVISION = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val GROUP = """{"suites":["esp-aes128-sha1-96","esp-null-sha1-96"]}"""
        val SELECTED = ConfigurationImsProtection(listOf(ConfigurationImsSuite.AES128_SHA1, ConfigurationImsSuite.NULL_SHA1))
    }
}
