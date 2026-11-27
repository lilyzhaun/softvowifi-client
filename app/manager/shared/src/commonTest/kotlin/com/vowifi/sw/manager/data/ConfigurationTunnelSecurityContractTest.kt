package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.tunnelFactsSummary
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationTunnelSecurityContractTest {
    @Test fun catalogPolicyIsReadableWithoutBecomingAUserOverride() {
        val snapshot = ConfigurationCodec.show(show("""{"state":"known","source":"catalog","value":$GROUP}"""))
        assertEquals(ConfigurationTunnelSecurity(), snapshot.tunnelSecurity)
        assertEquals(ConfigurationTunnelSecurity(), ConfigurationEvaluator.instance(ConfigurationInstanceId("opaque"), snapshot).tunnelSecurity)
        assertEquals(SELECTED, snapshot.facts!!.tunnelSecurity!!.value)
        assertNull(snapshot.facts!!.tunnelSecurity!!.overrideValue)
        assertTrue(tunnelFactsSummary(snapshot.facts!!.tunnelSecurity!!).startsWith("运营商目录"))
        assertFalse(tunnelFactsSummary(snapshot.facts!!.tunnelSecurity!!).contains("手动设置"))
        assertLoadedInheritance(snapshot, "catalog")
        assertEquals("{}", ConfigurationCodec.encodeChanges(overrides(snapshot.tunnelSecurity), overrides(snapshot.tunnelSecurity)))
        assertFalse(ConfigurationTunnelSecurityCodec.facts(Json.parseToJsonElement("""{"state":"unknown","source":"catalog"}"""))!!.known)
        for (bad in listOf(
            """{"state":"known","source":"catalog","value":$GROUP,"override":$GROUP}""",
            """{"state":"known","source":"catalog"}""",
        )) assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.show(show(bad)) }
    }

    @Test fun diagnosticInheritedGroupCannotManufactureARawEditableOverride() {
        val resolved = GROUP.replace("multiple-complete", "single-combined")
        val snapshot = ConfigurationCodec.show(show("""{"state":"known","source":"diagnostic","value":$resolved}"""))
        assertEquals(ConfigurationTunnelSecurity(), snapshot.tunnelSecurity)
        assertEquals("diagnostic", snapshot.facts!!.tunnelSecurity!!.source)
        assertEquals(ConfigurationTunnelLayout.SINGLE_COMBINED, snapshot.facts!!.tunnelSecurity!!.value!!.policy!!.layout)
        assertNull(snapshot.facts!!.tunnelSecurity!!.overrideValue)
        assertTrue(tunnelFactsSummary(snapshot.facts!!.tunnelSecurity!!).startsWith("诊断布局"))
        assertLoadedInheritance(snapshot, "diagnostic")
        assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.show(show("""{"state":"known","source":"diagnostic","value":$resolved,"override":null}""")) }
    }

    @Test fun actualShowAndProjectionPreserveCompleteExplicitPolicyAndUnknown() {
        val snapshot = ConfigurationCodec.show(show("""{"state":"known","source":"user","value":$GROUP,"override":$GROUP}"""))
        assertEquals(SELECTED, snapshot.tunnelSecurity)
        assertEquals(SELECTED, ConfigurationEvaluator.instance(ConfigurationInstanceId("opaque"), snapshot).tunnelSecurity)
        assertEquals(ConfigurationTunnelSecurity(), ConfigurationCodec.show(show("""{"state":"known","source":"default","baseline":"swu-compatible-v1"}""")).tunnelSecurity)
        assertNull(ConfigurationCodec.show(show(null)).tunnelSecurity)
        assertNull(ConfigurationCodec.show(show("""{"state":"unknown","source":"user"}""")).tunnelSecurity)
    }

    @Test fun revisionPatchKeepsPolicyAtomicAndOnlyExplicitClearWritesNull() {
        val baseline = overrides(ConfigurationTunnelSecurity())
        val selected = baseline.copy(tunnelSecurity = SELECTED)
        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(baseline, selected)).jsonObject
        assertEquals(JsonPrimitive(REVISION), patch["expectedRevision"])
        assertEquals(setOf("tunnelSecurity"), patch.getValue("overrides").jsonObject.keys)
        assertEquals(Json.parseToJsonElement(GROUP), patch.getValue("overrides").jsonObject["tunnelSecurity"])
        assertEquals(JsonNull, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(selected, baseline)).jsonObject["tunnelSecurity"])
        assertEquals(buildJsonObject {}, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(selected, selected.copy(tunnelSecurity = null))))
        assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.encodeChanges(baseline.copy(tunnelSecurity = null), selected) }
        assertEquals(JsonNull, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(baseline.copy(tunnelSecurity = null), baseline)).jsonObject["tunnelSecurity"])
    }

    @Test fun partialDraftAndCrossProductCannotBecomeDefaultOrPartialPatch() {
        assertEquals(Json.parseToJsonElement(GROUP), Json.parseToJsonElement(ConfigurationCodec.encode(overrides(SELECTED))).jsonObject["tunnelSecurity"])
        val complete = SELECTED.policy!!
        val invalid = listOf(
            ConfigurationTunnelPolicy(), complete.copy(ikeSuites = emptyList()), complete.copy(childSuites = emptyList()),
            complete.copy(ikeSuites = complete.ikeSuites + complete.ikeSuites),
            complete.copy(childSuites = complete.childSuites + complete.childSuites),
            complete.copy(ikeSuites = listOf(ConfigurationIkeSuite.CBC256_SHA256_DH14, ConfigurationIkeSuite.CBC128_SHA256_DH2)),
            complete.copy(ikeSuites = listOf(ConfigurationIkeSuite.CBC256_SHA256_DH14, ConfigurationIkeSuite.CBC256_SHA384_DH14), layout = ConfigurationTunnelLayout.SINGLE_COMBINED),
        )
        invalid.forEach { value -> assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.encode(overrides(ConfigurationTunnelSecurity(value))) } }
        val closed = complete.copy(ikeSuites = listOf(ConfigurationIkeSuite.CBC128_SHA256_DH14, ConfigurationIkeSuite.CBC256_SHA256_DH14), layout = ConfigurationTunnelLayout.SINGLE_COMBINED)
        assertEquals(JsonPrimitive("single-combined"), Json.parseToJsonElement(ConfigurationCodec.encode(overrides(ConfigurationTunnelSecurity(closed)))).jsonObject.getValue("tunnelSecurity").jsonObject["layout"])
    }

    @Test fun unknownPolicyFactsNeverEchoOrInventACompleteGroup() {
        assertEquals(SELECTED, ConfigurationCodec.show(show("""{"state":"known","source":"user","value":$GROUP,"override":$GROUP}""")).tunnelSecurity)
        listOf(
            """{"state":"known","source":"default","baseline":"unrecognized"}""",
            """{"state":"unknown","source":"user","value":$GROUP}""",
            """{"state":"known","source":"user","value":$GROUP,"override":{}}""",
            """{"state":"known","source":"user","value":$GROUP,"override":$GROUP,"key":"forbidden"}""",
        ).forEach { facts -> assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.show(show(facts)) } }
    }

    @Test fun diagnosticLayoutRemainsSeparateFromTheStoredWholeGroupAndCannotWidenIt() {
        val resolved = GROUP.replace("multiple-complete", "single-combined")
        val snapshot = ConfigurationCodec.show(show("""{"state":"known","source":"diagnostic","value":$resolved,"override":$GROUP}"""))
        assertEquals(SELECTED, snapshot.tunnelSecurity)
        assertEquals(ConfigurationTunnelLayout.SINGLE_COMBINED, snapshot.facts!!.tunnelSecurity!!.value!!.policy!!.layout)
        assertEquals(ConfigurationTunnelLayout.MULTIPLE_COMPLETE, snapshot.facts!!.tunnelSecurity!!.overrideValue!!.policy!!.layout)
        val foreign = resolved.replace("esp-gcm128-noesn", "esp-gcm256-noesn")
        assertFailsWith<ConfigurationFormatException> {
            ConfigurationCodec.show(show("""{"state":"known","source":"diagnostic","value":$foreign,"override":$GROUP}"""))
        }
    }

    private fun overrides(value: ConfigurationTunnelSecurity?) =
        ConfigurationOverrides(ConfigurationTransport.WIFI, "", "", "", "", expectedRevision = REVISION, tunnelSecurity = value)

    private fun assertLoadedInheritance(snapshot: ConfigurationSnapshot, source: String) {
        val runtime = ConfigurationFactsCodec.runtime(buildJsonObject {
            put("tunnel", "CONNECTED")
            put("configuration", buildJsonObject {
                put("schema", 1)
                put("loaded", buildJsonObject {
                    put("schema", 1); put("configRevision", REVISION); put("resolutionRevision", "rf1_" + "a".repeat(64)); put("fields", buildJsonArray {})
                    put("tunnelSecurity", buildJsonObject {
                        put("state", "known"); put("source", source)
                        put("value", ConfigurationTunnelSecurityCodec.encode(snapshot.facts!!.tunnelSecurity!!.value!!))
                    })
                })
                put("negotiated", buildJsonObject {})
            })
        })!!
        assertEquals(snapshot.facts!!.tunnelSecurity, runtime.loaded?.tunnelSecurity)
        assertNull(runtime.loaded?.tunnelSecurity?.overrideValue)
        assertNull(runtime.tunnel)
    }

    private fun show(policy: String?): String = buildJsonObject {
        put("ok", true); put("formatState", "current"); put("configVersion", 1); put("configRevision", REVISION)
        put("displayLabel", "0001"); put("lifecycle", "stopped"); put("transport", "wifi")
        listOf("smsc", "epdgFqdn", "imsRealm", "dns").forEach { put(it, "") }
        put("resolvedEpdgFqdn", "epdg.example.test"); put("resolvedImsRealm", "ims.example.test"); put("mcc", "001"); put("mnc", "01")
        put("configuration", buildJsonObject {
            put("schema", 1); put("configRevision", REVISION); put("resolutionRevision", "rf1_" + "a".repeat(64)); put("fields", buildJsonArray {})
            policy?.let { put("tunnelSecurity", Json.parseToJsonElement(it)) }
        })
    }.toString()

    companion object {
        const val REVISION = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val GROUP = """{"ikeSuites":["ike-cbc256-sha256-prfsha256-dh14"],"childSuites":["esp-gcm128-noesn"],"layout":"multiple-complete"}"""
        val SELECTED = ConfigurationTunnelSecurity(ConfigurationTunnelPolicy(listOf(ConfigurationIkeSuite.CBC256_SHA256_DH14), listOf(ConfigurationChildSuite.GCM128)))
    }
}
