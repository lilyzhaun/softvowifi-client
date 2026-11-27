package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationTunnelTimingContractTest {
    @Test fun catalogTimingIsReadableWithoutBecomingAnEditableOverride() {
        val facts = ConfigurationTunnelTimingCodec.facts(Json.parseToJsonElement("""{"state":"known","source":"catalog","value":$GROUP}"""))!!
        assertTrue(facts.known)
        assertEquals("catalog", facts.source)
        assertEquals(SELECTED, facts.value)
        assertNull(facts.overrideValue)
        val local = ConfigurationFacts(REVISION, "rf1_" + "a".repeat(64), emptyList(), tunnelTiming = facts)
        assertEquals(ConfigurationTunnelTiming(), ConfigurationTunnelTimingCodec.read(local))
        assertEquals("{}", ConfigurationCodec.encodeChanges(overrides(ConfigurationTunnelTiming()), overrides(ConfigurationTunnelTiming())))
        val snapshot = ConfigurationCodec.show(show("""{"state":"known","source":"catalog","value":$GROUP}"""))
        assertEquals(ConfigurationTunnelTiming(), snapshot.tunnelTiming)
        assertEquals(ConfigurationTunnelTiming(), ConfigurationEvaluator.instance(ConfigurationInstanceId("opaque"), snapshot).tunnelTiming)
        assertFalse(ConfigurationTunnelTimingCodec.facts(Json.parseToJsonElement("""{"state":"unknown","source":"catalog"}"""))!!.known)
        for (bad in listOf(
            """{"state":"known","source":"catalog","value":$GROUP,"override":$GROUP}""",
            """{"state":"known","source":"catalog"}""",
            """{"state":"known","source":"user","value":$GROUP}""",
        )) assertFailsWith<ConfigurationFormatException> { ConfigurationTunnelTimingCodec.facts(Json.parseToJsonElement(bad)) }
    }

    @Test fun showProjectionKeepsCompleteTimingAndDistinguishesUnknown() {
        val snapshot = ConfigurationCodec.show(show("""{"state":"known","source":"user","value":$GROUP,"override":$GROUP}"""))
        assertEquals(SELECTED, snapshot.tunnelTiming)
        assertEquals(SELECTED, ConfigurationEvaluator.instance(ConfigurationInstanceId("opaque"), snapshot).tunnelTiming)
        assertEquals(ConfigurationTunnelTiming(), ConfigurationCodec.show(show("""{"state":"known","source":"default","baseline":"swu-renewal-compatible-v1"}""")).tunnelTiming)
        assertNull(ConfigurationCodec.show(show(null)).tunnelTiming)
        assertNull(ConfigurationCodec.show(show("""{"state":"unknown","source":"user"}""")).tunnelTiming)
    }

    @Test fun revisionPatchIsAtomicAndOnlyExplicitClearWritesNull() {
        val base = overrides(ConfigurationTunnelTiming())
        val selected = base.copy(tunnelTiming = SELECTED)
        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(base, selected)).jsonObject
        assertEquals(JsonPrimitive(REVISION), patch["expectedRevision"])
        assertEquals(setOf("tunnelTiming"), patch.getValue("overrides").jsonObject.keys)
        assertEquals(Json.parseToJsonElement(GROUP), patch.getValue("overrides").jsonObject["tunnelTiming"])
        assertEquals(JsonNull, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(selected, base)).jsonObject["tunnelTiming"])
        assertEquals(buildJsonObject {}, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(selected, selected.copy(tunnelTiming = null))))
        assertFailsWith<ConfigurationFormatException.UnknownTunnelTiming> { ConfigurationCodec.encodeChanges(base.copy(tunnelTiming = null), selected) }
        assertEquals(JsonNull, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(base.copy(tunnelTiming = null), base)).jsonObject["tunnelTiming"])
        val callOnly = Json.parseToJsonElement(ConfigurationCodec.encodeChanges(selected, selected.copy(callSignaling = ConfigurationCallSignaling(includePANI = true)))).jsonObject
        assertEquals(setOf("callSignaling"), callOnly.keys)
    }

    @Test fun incompleteConflictingAndOverflowingPolicyNeverBecomesASavablePatch() {
        val complete = SELECTED.policy!!
        val invalid = listOf(
            ConfigurationTunnelTimingPolicy(), complete.copy(ike = complete.ike.copy(hardSeconds = 1200)),
            complete.copy(child = complete.child.copy(jitterSeconds = 1500)), complete.copy(dpdSeconds = -1),
            complete.copy(reauthSeconds = Long.MAX_VALUE), complete.copy(retransmit = complete.retransmit.copy(retries = 33)),
            complete.copy(retransmit = complete.retransmit.copy(initialMillis = 0)),
            complete.copy(retransmit = complete.retransmit.copy(maximumMillis = 1000)),
            complete.copy(retransmit = complete.retransmit.copy(backoffFactor = Double.NaN)),
            complete.copy(retransmit = complete.retransmit.copy(backoffFactor = Double.POSITIVE_INFINITY)),
        )
        invalid.forEach { policy -> assertFailsWith<ConfigurationFormatException.InvalidTunnelTiming> {
            ConfigurationCodec.encode(overrides(ConfigurationTunnelTiming(policy)))
        } }
    }

    @Test fun explicitZeroIsNotInheritedAndFactsRejectMismatchOrExtraData() {
        val selected = SELECTED.copy(policy = SELECTED.policy!!.copy(dpdSeconds = 0, natKeepaliveSeconds = 0))
        val encoded = Json.parseToJsonElement(ConfigurationCodec.encode(overrides(selected))).jsonObject.getValue("tunnelTiming").jsonObject
        assertEquals(JsonPrimitive(0), encoded["dpdSeconds"])
        assertEquals(JsonPrimitive(0), encoded["natKeepaliveSeconds"])
        listOf(
            """{"state":"known","source":"default","baseline":"unknown-baseline"}""",
            """{"state":"unknown","source":"user","value":$GROUP}""",
            """{"state":"known","source":"user","value":$GROUP,"override":{}}""",
            """{"state":"known","source":"user","value":$GROUP,"override":$GROUP,"extra":"forbidden"}""",
            """{"state":"known","source":"diagnostic","value":$GROUP,"override":$GROUP}""",
        ).forEach { facts -> assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.show(show(facts)) } }
        assertEquals("ConfigurationTunnelTiming", selected.toString())
    }

    @Test fun wireRejectsMissingUnknownNullFractionalAndStringLeaves() {
        listOf(
            GROUP.replace("\"dpdSeconds\":20,", ""),
            GROUP.replace("\"dpdSeconds\":20", "\"dpdSeconds\":null"),
            GROUP.replace("\"dpdSeconds\":20", "\"dpdSeconds\":\"20\""),
            GROUP.replace("\"softSeconds\":1200", "\"softSeconds\":1200.5"),
            GROUP.replace("\"hardSeconds\":1800,", ""),
            GROUP.replace("\"jitterSeconds\":30", "\"JitterSeconds\":30"),
            GROUP.replace("\"backoffFactor\":1.8", "\"backoffFactor\":1.8,\"extra\":0"),
            GROUP.replace("\"retransmit\":{", "\"retransmit\":{\"initialSeconds\":4,"),
        ).forEach { group -> assertFailsWith<ConfigurationFormatException.InvalidTunnelTiming> {
            ConfigurationTunnelTimingCodec.decode(Json.parseToJsonElement(group))
        } }
    }

    @Test fun sdkEquivalentOverflowValidationAppliesTheCapBeforeConversion() {
        val policy = SELECTED.policy!!
        val bounded = ConfigurationTunnelTiming(policy.copy(retransmit = ConfigurationTunnelRetry(1, 1000, 2000, Double.MAX_VALUE)))
        assertEquals(bounded, ConfigurationTunnelTimingCodec.decode(ConfigurationTunnelTimingCodec.encode(bounded)))
        listOf(
            policy.copy(natKeepaliveSeconds = ConfigurationTunnelTimingCodec.MAX_SECONDS, natDpdMarginSeconds = 1),
            policy.copy(retransmit = ConfigurationTunnelRetry(1, ConfigurationTunnelTimingCodec.MAX_MILLIS / 2 + 1, 0, 1.0)),
            policy.copy(retransmit = ConfigurationTunnelRetry(1, 1000, 0, Double.MAX_VALUE)),
        ).forEach { bad -> assertFailsWith<ConfigurationFormatException.InvalidTunnelTiming> { ConfigurationTunnelTimingCodec.validate(bad) } }
    }

    private fun overrides(value: ConfigurationTunnelTiming?) = ConfigurationOverrides(
        ConfigurationTransport.WIFI, "", "", "", "", expectedRevision = REVISION,
        callSignaling = ConfigurationCallSignaling(), tunnelTiming = value)

    private fun show(timing: String?) = buildJsonObject {
        put("ok", true); put("formatState", "current"); put("configVersion", 1); put("configRevision", REVISION)
        put("displayLabel", "0001"); put("lifecycle", "stopped"); put("transport", "wifi")
        listOf("smsc", "epdgFqdn", "imsRealm", "dns").forEach { put(it, "") }
        put("resolvedEpdgFqdn", "epdg.example.test"); put("resolvedImsRealm", "ims.example.test"); put("mcc", "001"); put("mnc", "01")
        put("configuration", buildJsonObject {
            put("schema", 1); put("configRevision", REVISION); put("resolutionRevision", "rf1_" + "a".repeat(64)); put("fields", buildJsonArray {})
            timing?.let { put("tunnelTiming", Json.parseToJsonElement(it)) }
        })
    }.toString()

    companion object {
        const val REVISION = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val GROUP = """{"ike":{"softSeconds":1200,"hardSeconds":1800,"jitterSeconds":30},"child":{"softSeconds":1500,"hardSeconds":2100,"jitterSeconds":30},"reauthSeconds":0,"dpdSeconds":20,"natKeepaliveSeconds":20,"natDpdMarginSeconds":150,"retransmit":{"retries":5,"initialMillis":4000,"maximumMillis":0,"backoffFactor":1.8}}"""
        val SELECTED = ConfigurationTunnelTiming(ConfigurationTunnelTimingPolicy(
            ike = ConfigurationSaTiming(1200, 1800, 30), child = ConfigurationSaTiming(1500, 2100, 30)))
    }
}
