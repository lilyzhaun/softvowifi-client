package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationMediaOverridesTest {
    @Test fun actualShowAndEvaluatorKeepOnlyExplicitMediaChoicesAndPreserveUnknown() {
        val group = """{"speechFormats":[{"codec":"amr-nb","framing":"oa","modeSet":[0,2,4],"initialMode":0,"payloadType":110}]}"""
        val snapshot = ConfigurationCodec.show(show("""{"state":"known","source":"user","value":$group,"override":$group}"""))
        assertEquals(0, snapshot.callMedia?.speechFormats?.single()?.initialMode)
        val row = ConfigurationEvaluator.instance(ConfigurationInstanceId("opaque"), snapshot)
        assertEquals(snapshot.callMedia, row.callMedia)
        assertEquals(ConfigurationCallMedia(), ConfigurationCodec.show(show("""{"state":"known","source":"default","baseline":"amr-compatible-v1"}""")).callMedia)
        assertNull(ConfigurationCodec.show(show(null)).callMedia)
        assertNull(ConfigurationCodec.show(show("""{"state":"unknown","source":"user"}""")).callMedia)
    }

    @Test fun mediaDeltaIsAtomicAndUnknownCannotBeReplacedOrClearedImplicitly() {
        val baseline = ConfigurationOverrides(ConfigurationTransport.WIFI, "", "", "", "", expectedRevision = REVISION,
            callMedia = ConfigurationCallMedia())
        val selected = ConfigurationCallMedia(listOf(ConfigurationSpeechFormat(ConfigurationSpeechCodec.AMR_NB, ConfigurationSpeechFraming.OA,
            modeSet = listOf(0, 2, 4), initialMode = 0, payloadType = 110)))
        val next = baseline.copy(callMedia = selected)
        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeChanges(baseline, next)).jsonObject
        assertEquals(setOf("callMedia"), patch.keys)
        assertEquals(ConfigurationCallMediaCodec.encode(selected), patch["callMedia"])
        assertEquals(JsonPrimitive(REVISION), Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(baseline, next)).jsonObject["expectedRevision"])
        assertEquals(JsonNull, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(next, baseline)).jsonObject["callMedia"])
        assertEquals(buildJsonObject {}, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(next, next.copy(callMedia = null))))
        assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.encodeChanges(baseline.copy(callMedia = null), next) }
        assertEquals(JsonNull, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(baseline.copy(callMedia = null), baseline)).jsonObject["callMedia"])
        assertEquals(buildJsonObject {}, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(next, next)))
    }

    private fun show(media: String?): String = buildJsonObject {
        put("ok", true); put("formatState", "current"); put("configVersion", 1); put("configRevision", REVISION)
        put("displayLabel", "0001"); put("lifecycle", "running"); put("transport", "wifi")
        listOf("smsc", "epdgFqdn", "imsRealm", "dns").forEach { put(it, "") }
        put("resolvedEpdgFqdn", "epdg.example.test"); put("resolvedImsRealm", "ims.example.test"); put("mcc", "001"); put("mnc", "01")
        put("configuration", buildJsonObject {
            put("schema", 1); put("configRevision", REVISION); put("resolutionRevision", "rf1_" + "a".repeat(64)); put("fields", buildJsonArray {})
            media?.let { put("callMedia", Json.parseToJsonElement(it)) }
        })
    }.toString()

    companion object { const val REVISION = "r1_abcdefghijklmnopabcdefghijklmnop" }
}
