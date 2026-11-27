package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationChildPfsContractTest {
    @Test fun completePfsPolicyRoundtripsButLegacyAndZeroDoNotMaterializeADefault() {
        val selected = decode(PFS)
        assertEquals(Json.parseToJsonElement(PFS), ConfigurationTunnelSecurityCodec.encode(selected))
        assertNotEquals(decode(LEGACY), selected)
        assertEquals(decode(LEGACY), decode(withPfs("0")))
        assertEquals(Json.parseToJsonElement(LEGACY), ConfigurationTunnelSecurityCodec.encode(decode(withPfs("0"))))
        for (bad in listOf("null", "true", "\"14\"", "-1", "2", "19", "65536", "1.5", "14.0")) {
            assertFailsWith<ConfigurationFormatException> { decode(withPfs(bad)) }
        }
        assertFailsWith<ConfigurationFormatException> { decode(PFS.replace("childPFSGroup", "unknownPFS")) }
    }

    @Test fun pfsCatalogAndLoadedFactsNeverBecomeRawOrAnObservedExchange() {
        val snapshot = ConfigurationCodec.show(show("catalog", PFS))
        assertEquals(ConfigurationTunnelSecurity(), snapshot.tunnelSecurity)
        assertEquals(decode(PFS), snapshot.facts!!.tunnelSecurity!!.value)
        assertNull(snapshot.facts!!.tunnelSecurity!!.overrideValue)
        val row = ConfigurationEvaluator.instance(ConfigurationInstanceId("opaque"), snapshot)
        val draft = ConfigurationEditorDraft(row)
        assertEquals(ConfigurationTunnelSecurity(), draft.overrides().tunnelSecurity)
        assertFalse(draft.dirty)
        val runtime = ConfigurationFactsCodec.runtime(status(0))!!
        assertEquals(decode(PFS), runtime.loaded.tunnelSecurity!!.value)
        assertNull(runtime.loaded.tunnelSecurity!!.overrideValue)
        assertEquals(0, runtime.tunnel!!.child.pfs)
        assertEquals("{}", ConfigurationCodec.encodeChanges(overrides(row), overrides(row)))
        assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.show(show("catalog", PFS, PFS)) }
    }

    @Test fun pfsOnlyEditIsAtomicNextStartAndMismatchedReadbackPreservesTheDraft() {
        val initial = ConfigurationEvaluator.instance(ConfigurationInstanceId("opaque"), ConfigurationCodec.show(show("user", LEGACY, LEGACY)))
            .copy(lifecycle = ConfigurationLifecycle.RUNNING)
        val selected = decode(PFS)
        val draft = ConfigurationEditorDraft(initial)
        assertTrue(draft.replaceTunnelSecurity(selected))
        assertFalse(draft.onlyNextCallChanges)
        assertTrue(configurationSaveRestarts(initial.lifecycle, draft))
        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(overrides(initial), draft.overrides())).jsonObject
        assertEquals(JsonPrimitive(REVISION), patch["expectedRevision"])
        assertEquals(buildJsonObject { put("tunnelSecurity", Json.parseToJsonElement(PFS)) }, patch["overrides"])
        draft.submit { _, payload, complete ->
            assertEquals(selected, payload.tunnelSecurity)
            complete(ConfigurationApplyOutcome.Confirmed(initial.copy(configRevision = NEXT)))
            ConfigurationApplyAcceptance.Accepted
        }
        assertEquals(ConfigurationEditStatus.Unconfirmed, draft.status)
        assertEquals(selected, draft.tunnelSecurity)
        assertEquals(REVISION, draft.overrides().expectedRevision)
        val retained = ConfigurationEditorDraft(initial.copy(configRevision = NEXT), draft.retained())
        assertEquals(selected, retained.tunnelSecurity)
        assertEquals(REVISION, retained.overrides().expectedRevision)
    }

    @Test fun diagnosticLayoutCannotSilentlyChangeThePfsGroup() {
        val resolved = PFS.replace("multiple-complete", "single-combined")
        val snapshot = ConfigurationCodec.show(show("diagnostic", resolved, PFS))
        assertEquals(decode(PFS), snapshot.tunnelSecurity)
        assertEquals(decode(resolved), snapshot.facts!!.tunnelSecurity!!.value)
        assertFailsWith<ConfigurationFormatException> {
            ConfigurationCodec.show(show("diagnostic", resolved, LEGACY))
        }
    }

    @Test fun actualRekeyPfsIsPresentedWithIndependentEsnAndStrictUnknownOrRetiredFacts() {
        val runtime = ConfigurationFactsCodec.runtime(status(14))!!
        assertEquals(14, runtime.tunnel!!.child.pfs)
        assertFalse(runtime.tunnel!!.child.esn)
        val row = ConfigurationEvaluator.instance(ConfigurationInstanceId("opaque"), ConfigurationCodec.show(show("catalog", PFS)))
            .copy(lifecycle = ConfigurationLifecycle.RUNNING, runtimeFacts = runtime)
        val text = configurationTunnelFactsSummary(row)
        assertTrue(text.contains("PFS") && text.contains("14"))
        assertFalse(text.contains("无额外 PFS"))
        assertNull(ConfigurationFactsCodec.runtime(status(14, state = "BACKOFF"))!!.tunnel)
        assertNull(ConfigurationFactsCodec.runtime(status(null))!!.tunnel)
        for (bad in listOf(JsonPrimitive(19), JsonPrimitive("14"), JsonNull, JsonPrimitive(true))) {
            assertFailsWith<ConfigurationFormatException> { ConfigurationFactsCodec.runtime(status(14, pfs = bad)) }
        }
        val esn = ConfigurationFactsCodec.runtime(status(14, esn = true))!!.tunnel!!.child
        assertTrue(esn.esn)
        assertEquals(14, esn.pfs)
    }

    private fun decode(value: String) = ConfigurationTunnelSecurityCodec.decode(Json.parseToJsonElement(value))
    private fun overrides(row: ConfigurationInstance) = ConfigurationOverrides(row.transport, row.smsc, row.epdgFqdn, row.imsRealm, row.dns,
        expectedRevision = row.configRevision, tunnelSecurity = row.tunnelSecurity)

    private fun show(source: String, value: String, raw: String? = null) = buildJsonObject {
        put("ok", true); put("formatState", "current"); put("configVersion", 1); put("configRevision", REVISION)
        put("displayLabel", "0001"); put("lifecycle", "stopped"); put("transport", "wifi")
        listOf("smsc", "epdgFqdn", "imsRealm", "dns").forEach { put(it, "") }
        put("resolvedEpdgFqdn", "epdg.example.test"); put("resolvedImsRealm", "ims.example.test"); put("mcc", "001"); put("mnc", "01")
        put("configuration", buildJsonObject {
            put("schema", 1); put("configRevision", REVISION); put("resolutionRevision", "rf1_" + "a".repeat(64)); put("fields", buildJsonArray {})
            put("tunnelSecurity", buildJsonObject {
                put("state", "known"); put("source", source); put("value", Json.parseToJsonElement(value))
                raw?.let { put("override", Json.parseToJsonElement(it)) }
            })
        })
    }.toString()

    private fun status(group: Int?, state: String = "CONNECTED", pfs: JsonElement = JsonPrimitive(group ?: 0), esn: Boolean = false) = buildJsonObject {
        put("tunnel", state)
        put("configuration", buildJsonObject {
            put("schema", 1)
            put("loaded", buildJsonObject {
                put("schema", 1); put("configRevision", REVISION); put("resolutionRevision", "rf1_" + "a".repeat(64)); put("fields", buildJsonArray {})
                put("tunnelSecurity", buildJsonObject {
                    put("state", "known"); put("source", "catalog"); put("value", Json.parseToJsonElement(PFS))
                })
            })
            put("negotiated", buildJsonObject {})
            if (group != null) put("tunnel", buildJsonObject {
                put("ike", Json.parseToJsonElement("""{"encryption":12,"keyBits":256,"integrity":12,"prf":5,"dh":14}"""))
                put("child", buildJsonObject {
                    put("encryption", 20); put("keyBits", 128); put("integrity", 0); put("esn", esn); put("pfs", pfs)
                })
            })
        })
    }

    private companion object {
        const val REVISION = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val LEGACY = """{"ikeSuites":["ike-cbc256-sha256-prfsha256-dh14"],"childSuites":["esp-gcm128-noesn"],"layout":"multiple-complete"}"""
        val PFS = withPfs("14")
        fun withPfs(value: String) = LEGACY.dropLast(1) + ",\"childPFSGroup\":$value}"
    }
}
