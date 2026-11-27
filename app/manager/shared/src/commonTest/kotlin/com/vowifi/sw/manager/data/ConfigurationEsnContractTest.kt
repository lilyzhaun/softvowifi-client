package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationEsnContractTest {
    @Test fun allCompleteEsnTuplesRoundtripAndAllowExplicitMixedMode() {
        val names = listOf("gcm256", "gcm128", "cbc256-sha256", "cbc128-sha256", "cbc128-sha1", "cbc256-sha512")
        for (name in names) {
            for (pfs in listOf(0, 14)) {
                val wire = policy(listOf("esp-$name-esn"), pfs)
                assertEquals(wire, ConfigurationTunnelSecurityCodec.encode(ConfigurationTunnelSecurityCodec.decode(wire)))
            }
        }
        val mixed = policy(names.flatMap { listOf("esp-$it-noesn", "esp-$it-esn") })
        assertEquals(mixed, ConfigurationTunnelSecurityCodec.encode(ConfigurationTunnelSecurityCodec.decode(mixed)))
        for (bad in listOf("esp-gcm128-esn2", "esp-cbc128-esn", "esp-gcm128-esn ")) {
            assertFailsWith<ConfigurationFormatException> { ConfigurationTunnelSecurityCodec.decode(policy(listOf(bad))) }
        }
        assertFailsWith<ConfigurationFormatException> {
            ConfigurationTunnelSecurityCodec.decode(policy(listOf("esp-gcm128-esn", "esp-gcm128-esn")))
        }
        assertFailsWith<ConfigurationFormatException> {
            ConfigurationTunnelSecurityCodec.decode(JsonObject(policy(listOf("esp-gcm128-noesn")) + ("enableESN" to JsonPrimitive(true))))
        }
    }

    @Test fun esnOnlyDraftKeepsWholeGroupAndRequiresNextStart() {
        val legacy = ConfigurationTunnelSecurityCodec.decode(policy(listOf("esp-gcm128-noesn")))
        val requested = ConfigurationTunnelSecurityCodec.decode(policy(listOf("esp-gcm128-esn")))
        val initial = ConfigurationEvaluator.instance(ConfigurationInstanceId("opaque"), ConfigurationCodec.show(show(legacy)))
            .copy(lifecycle = ConfigurationLifecycle.RUNNING)
        val draft = ConfigurationEditorDraft(initial)
        assertTrue(draft.replaceTunnelSecurity(requested))
        assertTrue(draft.dirty)
        assertFalse(draft.onlyNextCallChanges)
        assertTrue(configurationSaveRestarts(initial.lifecycle, draft))
        val from = ConfigurationOverrides(initial.transport, initial.smsc, initial.epdgFqdn, initial.imsRealm, initial.dns,
            expectedRevision = initial.configRevision, tunnelSecurity = legacy)
        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(from, draft.overrides())).jsonObject
        assertEquals(buildJsonObject { put("tunnelSecurity", policy(listOf("esp-gcm128-esn"))) }, patch["overrides"])
        assertEquals(JsonPrimitive(REVISION), patch["expectedRevision"])
    }

    @Test fun observedBooleanEsnAndPfsAreIndependentAndRetiredFactsClear() {
        for (esn in listOf(false, true)) for (pfs in listOf(0, 14)) {
            val facts = ConfigurationFactsCodec.runtime(status(JsonPrimitive(esn), pfs))!!
            assertEquals(esn, facts.tunnel!!.child.esn)
            assertEquals(pfs, facts.tunnel!!.child.pfs)
            val initial = ConfigurationEvaluator.instance(ConfigurationInstanceId("opaque"), ConfigurationCodec.show(show(
                ConfigurationTunnelSecurityCodec.decode(policy(listOf("esp-gcm128-noesn"))))))
            val text = configurationTunnelFactsSummary(initial.copy(lifecycle = ConfigurationLifecycle.RUNNING, runtimeFacts = facts))
            assertEquals(!esn, text.contains("无 ESN"))
            assertEquals(pfs == 0, text.contains("无额外 PFS"))
            assertNull(ConfigurationFactsCodec.runtime(status(JsonPrimitive(esn), pfs, "BACKOFF"))!!.tunnel)
        }
        for (bad in listOf(JsonPrimitive("true"), JsonPrimitive(1), JsonNull)) {
            assertFailsWith<ConfigurationFormatException> { ConfigurationFactsCodec.runtime(status(bad, 0)) }
        }
    }

    private fun policy(suites: List<String>, pfs: Int = 0) = buildJsonObject {
        put("ikeSuites", buildJsonArray { add("ike-cbc256-sha256-prfsha256-dh14") })
        put("childSuites", buildJsonArray { suites.forEach { add(it) } }); put("layout", "multiple-complete")
        if (pfs != 0) put("childPFSGroup", pfs)
    }

    private fun show(value: ConfigurationTunnelSecurity) = buildJsonObject {
        put("ok", true); put("formatState", "current"); put("configVersion", 1); put("configRevision", REVISION)
        put("displayLabel", "0001"); put("lifecycle", "stopped"); put("transport", "wifi")
        listOf("smsc", "epdgFqdn", "imsRealm", "dns").forEach { put(it, "") }
        put("resolvedEpdgFqdn", "epdg.example.test"); put("resolvedImsRealm", "ims.example.test"); put("mcc", "001"); put("mnc", "01")
        put("configuration", buildJsonObject {
            put("schema", 1); put("configRevision", REVISION); put("resolutionRevision", "rf1_" + "a".repeat(64)); put("fields", buildJsonArray {})
            put("tunnelSecurity", buildJsonObject {
                put("state", "known"); put("source", "user")
                put("value", ConfigurationTunnelSecurityCodec.encode(value)); put("override", ConfigurationTunnelSecurityCodec.encode(value))
            })
        })
    }.toString()

    private fun status(esn: JsonElement, pfs: Int, state: String = "CONNECTED") = buildJsonObject {
        put("tunnel", state)
        put("configuration", buildJsonObject {
            put("schema", 1)
            put("loaded", buildJsonObject {
                put("schema", 1); put("configRevision", REVISION); put("resolutionRevision", "rf1_" + "a".repeat(64)); put("fields", buildJsonArray {})
            })
            put("negotiated", buildJsonObject {})
            put("tunnel", buildJsonObject {
                put("ike", Json.parseToJsonElement("""{"encryption":12,"keyBits":256,"integrity":12,"prf":5,"dh":14}"""))
                put("child", buildJsonObject { put("encryption", 20); put("keyBits", 128); put("integrity", 0); put("esn", esn); put("pfs", pfs) })
            })
        })
    }

    private companion object { const val REVISION = "r1_abcdefghijklmnopabcdefghijklmnop" }
}
