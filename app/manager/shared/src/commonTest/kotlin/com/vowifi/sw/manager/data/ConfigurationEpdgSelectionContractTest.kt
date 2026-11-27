package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationEpdgSelectionContractTest {
    @Test fun firstEquivalentHomeIsAnOrderedSourceWithoutAStoredNetworkValue() {
        val raw = Json.parseToJsonElement("""{"candidates":[{"kind":"first-ehplmn","value":"","access":"any","registeredPlmns":[]},{"kind":"home-plmn","value":"","access":"any","registeredPlmns":[]}]}""")
        val value = ConfigurationEpdgSelectionCodec.decode(raw)
        assertEquals(raw, ConfigurationEpdgSelectionCodec.encode(value))
        assertFailsWith<ConfigurationFormatException.InvalidEpdgSelection> {
            ConfigurationEpdgSelectionCodec.decode(Json.parseToJsonElement(raw.toString().replaceFirst("\"value\":\"\"", "\"value\":\"00102\"")))
        }
    }

    @Test fun inheritedCatalogGroupIsReadableButNeverBecomesAnEditableOverride() {
        val group = Json.parseToJsonElement("""{"state":"known","source":"catalog","value":$POLICY}""")
        val local = ConfigurationFactsCodec.local(JsonObject(loaded().jsonObject + ("epdgSelection" to group)))!!
        val facts = local.epdgSelection!!
        assertTrue(facts.known)
        assertEquals("catalog", facts.source)
        assertEquals(ConfigurationEpdgSelectionCodec.decode(Json.parseToJsonElement(POLICY)), facts.value)
        assertNull(facts.overrideValue)
        assertEquals(ConfigurationEpdgSelection(), ConfigurationEpdgSelectionCodec.read(local))
        assertEquals("{}", ConfigurationCodec.encodeChanges(overrides(ConfigurationEpdgSelection()), overrides(ConfigurationEpdgSelection())))
        assertEquals(2, ConfigurationEpdgSelectionCodec.connection(Json.parseToJsonElement("""{"candidate":2,"registered":{"state":"unknown"}}"""), facts)!!.candidate)
        assertFalse(ConfigurationEpdgSelectionCodec.facts(Json.parseToJsonElement("""{"state":"unknown","source":"catalog"}"""))!!.known)
        for (bad in listOf(
            """{"state":"known","source":"catalog","value":$POLICY,"override":$POLICY}""",
            """{"state":"known","source":"catalog"}""",
            """{"state":"known","source":"user","value":$POLICY}""",
        )) assertFailsWith<ConfigurationFormatException> { ConfigurationEpdgSelectionCodec.facts(Json.parseToJsonElement(bad)) }
    }

    @Test fun completeOrderedGroupRoundTripsAndOnlyExplicitClearWritesNull() {
        val selected = ConfigurationEpdgSelectionCodec.decode(Json.parseToJsonElement(POLICY))
        assertEquals(listOf(ConfigurationEpdgKind.REGISTERED_PLMN, ConfigurationEpdgKind.ADDRESS), selected.candidates!!.map { it.kind })
        assertEquals(Json.parseToJsonElement(POLICY), ConfigurationEpdgSelectionCodec.encode(selected))
        val unknown = overrides(null)
        val compatible = unknown.copy(epdgSelection = ConfigurationEpdgSelection())
        assertEquals("{}", ConfigurationCodec.encodeChanges(unknown, unknown))
        assertEquals(JsonNull, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(unknown, compatible)).jsonObject["epdgSelection"])
        assertFailsWith<ConfigurationFormatException.UnknownEpdgSelection> { ConfigurationCodec.encodeChanges(unknown, unknown.copy(epdgSelection = selected)) }
        assertEquals(setOf("epdgSelection"), Json.parseToJsonElement(ConfigurationCodec.encodeChanges(compatible, compatible.copy(epdgSelection = selected))).jsonObject.keys)
        assertEquals("{}", ConfigurationCodec.encodeChanges(compatible.copy(epdgSelection = selected), unknown))
    }

    @Test fun malformedGroupsAndConflictingSingleHostNeverBecomeUsableDefaults() {
        for (raw in listOf("null", "{}", "[]", """{"candidates":[]}""", POLICY.replace("roaming", "unknown"),
            POLICY.replace("\"registeredPlmns\":[\"00101\"]", "\"registeredPlmns\":null"),
            POLICY.replace("fallback.example", "host.example:500"), POLICY.replace("fallback.example", "0".repeat(19) + ".example"))) {
            assertFailsWith<ConfigurationFormatException> { ConfigurationEpdgSelectionCodec.decode(Json.parseToJsonElement(raw)) }
        }
        val selected = ConfigurationEpdgSelectionCodec.decode(Json.parseToJsonElement(POLICY))
        assertFailsWith<ConfigurationFormatException.InvalidEpdgSelection> { ConfigurationCodec.encode(overrides(selected).copy(epdgFqdn = "single.example")) }
        val fact = ConfigurationEpdgSelectionCodec.facts(Json.parseToJsonElement("""{"state":"unknown","source":"user"}"""))!!
        assertFalse(fact.known); assertNull(fact.value)
        assertNull(ConfigurationEpdgSelectionCodec.facts(null))
    }

    @Test fun runtimeSelectionRequiresTheLoadedCandidateAndNarrowConsistentRegistration() {
        val loaded = loaded()
        fun status(selection: String, tunnel: String = "CONNECTED") = buildJsonObject {
            put("tunnel", tunnel)
            put("configuration", buildJsonObject {
                put("schema", 1); put("loaded", loaded); put("negotiated", buildJsonObject {})
                put("epdgSelection", Json.parseToJsonElement(selection))
            })
        }
        val home = """{"candidate":2,"registered":{"state":"known","plmn":"00101","roaming":false}}"""
        val facts = ConfigurationFactsCodec.runtime(status(home))!!
        assertEquals(false, facts.epdgSelection!!.roaming)
        assertEquals(2, facts.epdgSelection!!.candidate)
        assertNull(ConfigurationFactsCodec.runtime(status(home, "DISCONNECTED"))!!.epdgSelection)
        for (bad in listOf(home.replace("candidate\":2", "candidate\":3"), home.replace("candidate\":2", "candidate\":1"),
            home.replace("\"roaming\":false", "\"roaming\":\"false\""), home.replace("\"state\":\"known\"", "\"state\":\"unknown\""),
            home.replace("\"plmn\":", "\"subId\":7,\"plmn\":"))) {
            assertFailsWith<ConfigurationFormatException> { ConfigurationFactsCodec.runtime(status(bad)) }
        }
        val unknown = ConfigurationFactsCodec.runtime(status("""{"candidate":2,"registered":{"state":"unknown"}}"""))!!.epdgSelection!!
        assertNull(unknown.roaming); assertNull(unknown.registeredPlmn)
    }

    @Test fun embeddedIpv4MustBeTheActualAddressSuffix() {
        for (invalid in listOf("192.0.2.1::", "1:192.0.2.1::", "192.0.2.1::1", "1:2:3:4:5:6::192.0.2.1")) {
            assertFailsWith<ConfigurationFormatException> {
                ConfigurationEpdgSelectionCodec.decode(Json.parseToJsonElement(POLICY.replace("fallback.example", invalid)))
            }
        }
        for (valid in listOf("::ffff:192.0.2.1", "::192.0.2.1", "1:2:3:4:5:6:192.0.2.1")) {
            val decoded = ConfigurationEpdgSelectionCodec.decode(Json.parseToJsonElement(POLICY.replace("fallback.example", valid)))
            assertEquals(valid, decoded.candidates!!.last().value)
        }
    }

    private fun loaded() = Json.parseToJsonElement("""{"schema":1,"configRevision":"$REV","resolutionRevision":"rf1_${"a".repeat(64)}","fields":[],"epdgSelection":{"state":"known","source":"user","value":$POLICY,"override":$POLICY}}""")
    private fun overrides(selection: ConfigurationEpdgSelection?) = ConfigurationOverrides(ConfigurationTransport.WIFI, "", "", "", "", expectedRevision = REV, epdgSelection = selection)
    private companion object {
        const val REV = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val POLICY = """{"candidates":[{"kind":"registered-plmn","value":"","access":"roaming","registeredPlmns":["00101"]},{"kind":"address","value":"fallback.example","access":"any","registeredPlmns":[]}]}"""
    }
}
