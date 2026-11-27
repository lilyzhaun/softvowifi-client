package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationImsTimingContractTest {
    @Test fun catalogTimingIsReadableLocallyAndLoadedWithoutBecomingAnOverride() {
        val snapshot = ConfigurationCodec.show(show("""{"state":"known","source":"catalog","value":$GROUP}"""))
        assertEquals(ConfigurationImsTiming(), snapshot.imsTiming)
        assertEquals(ConfigurationImsTiming(), ConfigurationEvaluator.instance(ConfigurationInstanceId("opaque"), snapshot).imsTiming)
        val facts = snapshot.facts!!.imsTiming!!
        assertEquals(SELECTED, facts.value)
        assertEquals("catalog", facts.source)
        assertNull(facts.overrideValue)
        assertEquals("{}", ConfigurationCodec.encodeChanges(overrides(snapshot.imsTiming), overrides(snapshot.imsTiming)))
        val loaded = Json.parseToJsonElement(show("""{"state":"known","source":"catalog","value":$GROUP}""")).jsonObject.getValue("configuration")
        val runtime = ConfigurationFactsCodec.runtime(buildJsonObject {
            put("tunnel", "CONNECTED")
            put("configuration", buildJsonObject {
                put("schema", 1); put("loaded", loaded); put("negotiated", buildJsonObject {})
            })
        })!!
        assertEquals(facts, runtime.loaded.imsTiming)
        assertFalse(ConfigurationImsTimingCodec.facts(Json.parseToJsonElement("""{"state":"unknown","source":"catalog"}"""))!!.known)
        for (bad in listOf(
            """{"state":"known","source":"catalog","value":$GROUP,"override":$GROUP}""",
            """{"state":"known","source":"catalog","value":$GROUP,"override":null}""",
            """{"state":"known","source":"catalog"}""",
        )) assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.show(show(bad)) }
    }

    @Test fun showKeepsAllThreeGroupsAndUnknownIsNotCompatibility() {
        val selected = ConfigurationCodec.show(show("""{"state":"known","source":"user","value":$GROUP,"override":$GROUP}"""))
        assertEquals(SELECTED, selected.imsTiming)
        assertEquals(SELECTED, ConfigurationEvaluator.instance(ConfigurationInstanceId("opaque"), selected).imsTiming)
        assertEquals(ConfigurationImsTiming(), ConfigurationCodec.show(show("""{"state":"known","source":"default","baseline":"ims-timing-compatible-v1"}""")).imsTiming)
        assertNull(ConfigurationCodec.show(show(null)).imsTiming)
        assertNull(ConfigurationCodec.show(show("""{"state":"unknown","source":"user"}""")).imsTiming)
    }

    @Test fun revisionBoundGroupIsAtomicAndOnlyExplicitClearRemovesIt() {
        val base = overrides(ConfigurationImsTiming())
        val selected = base.copy(imsTiming = SELECTED)
        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(base, selected)).jsonObject
        assertEquals(JsonPrimitive(REVISION), patch["expectedRevision"])
        assertEquals(setOf("imsTiming"), patch.getValue("overrides").jsonObject.keys)
        assertEquals(Json.parseToJsonElement(GROUP), patch.getValue("overrides").jsonObject["imsTiming"])
        assertEquals(JsonNull, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(selected, base)).jsonObject["imsTiming"])
        assertEquals(buildJsonObject {}, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(selected, selected.copy(imsTiming = null))))
        assertFailsWith<ConfigurationFormatException.UnknownImsTiming> { ConfigurationCodec.encodeChanges(base.copy(imsTiming = null), selected) }
        assertEquals(JsonNull, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(base.copy(imsTiming = null), base)).jsonObject["imsTiming"])
        assertEquals(setOf("callSignaling"), Json.parseToJsonElement(ConfigurationCodec.encodeChanges(selected,
            selected.copy(callSignaling = ConfigurationCallSignaling(includePANI = true)))).jsonObject.keys)
    }

    @Test fun partialWrongTypeConflictingAndOverflowingValuesAreRejected() {
        listOf(
            GROUP.replace("\"t1Millis\":20", "\"t1Millis\":0"),
            GROUP.replace("\"t2Millis\":100", "\"t2Millis\":10"),
            GROUP.replace("\"fMillis\":200", "\"fMillis\":null"),
            GROUP.replace("\"bMillis\":250,", ""),
            GROUP.replace("\"dMillis\":32000", "\"dMillis\":1"),
            GROUP.replace("\"jMillis\":400", "\"jMillis\":\"400\""),
            GROUP.replace("\"t1Millis\":20", "\"t1Millis\":9223372036854775807"),
            GROUP.replace("\"refreshPercent\":60", "\"refreshPercent\":100"),
            GROUP.replace("\"requestedSeconds\":2400", "\"requestedSeconds\":2147483648"),
            GROUP.replace("\"maximumSeconds\":640", "\"maximumSeconds\":10"),
            GROUP.replace("\"unavailableSeconds\":120", "\"unavailableSeconds\":9223372036854775807"),
            GROUP.replace("\"recovery\":{", "\"recovery\":{\"unknown\":1,"),
        ).forEach { raw -> assertFailsWith<ConfigurationFormatException.InvalidImsTiming> {
            ConfigurationImsTimingCodec.decode(Json.parseToJsonElement(raw))
        } }
        assertFailsWith<ConfigurationFormatException.InvalidImsTiming> {
            ConfigurationImsTimingCodec.encode(SELECTED.copy(policy = SELECTED.policy!!.copy(
                transaction = SELECTED.policy!!.transaction.copy(t1Millis = null))))
        }
    }

    @Test fun factsRejectMismatchedOrUnconfirmedPolicies() {
        listOf(
            """{"state":"known","source":"default","baseline":"unknown"}""",
            """{"state":"unknown","source":"user","value":$GROUP}""",
            """{"state":"known","source":"user","value":$GROUP,"override":{}}""",
            """{"state":"known","source":"user","value":$GROUP,"override":$GROUP,"extra":1}""",
        ).forEach { facts -> assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.show(show(facts)) } }
        assertEquals("ConfigurationImsTiming", SELECTED.toString())
    }

    private fun overrides(value: ConfigurationImsTiming?) = ConfigurationOverrides(
        ConfigurationTransport.WIFI, "", "", "", "", expectedRevision = REVISION,
        callSignaling = ConfigurationCallSignaling(), imsTiming = value)

    private fun show(timing: String?) = buildJsonObject {
        put("ok", true); put("formatState", "current"); put("configVersion", 1); put("configRevision", REVISION)
        put("displayLabel", "0001"); put("lifecycle", "stopped"); put("transport", "wifi")
        listOf("smsc", "epdgFqdn", "imsRealm", "dns").forEach { put(it, "") }
        put("resolvedEpdgFqdn", "epdg.example.test"); put("resolvedImsRealm", "ims.example.test"); put("mcc", "001"); put("mnc", "01")
        put("configuration", buildJsonObject {
            put("schema", 1); put("configRevision", REVISION); put("resolutionRevision", "rf1_" + "a".repeat(64)); put("fields", buildJsonArray {})
            timing?.let { put("imsTiming", Json.parseToJsonElement(it)) }
        })
    }.toString()

    companion object {
        const val REVISION = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val GROUP = """{"transaction":{"t1Millis":20,"t2Millis":100,"t4Millis":70,"bMillis":250,"dMillis":32000,"fMillis":200,"hMillis":350,"jMillis":400},"registration":{"requestedSeconds":2400,"refreshPercent":60},"recovery":{"initialSeconds":20,"maximumSeconds":640,"unavailableSeconds":120}}"""
        val SELECTED = ConfigurationImsTiming(ConfigurationImsTimingPolicy(
            ConfigurationSipTransactionTiming(20, 100, 70, 250, 32000, 200, 350, 400),
            ConfigurationImsRegistrationTiming(2400, 60), ConfigurationImsRecoveryTiming(20, 640, 120)))
    }
}
