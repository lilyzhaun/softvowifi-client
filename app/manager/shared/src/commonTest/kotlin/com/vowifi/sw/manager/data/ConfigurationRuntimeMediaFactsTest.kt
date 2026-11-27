package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.configurationCallFactsSummary
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationRuntimeMediaFactsTest {
    @Test fun actualSdesFactsUseTheAttachedSuiteAndDoNotInventProtectionWhenMissing() {
        val secure = JsonObject(media() + ("security" to buildJsonObject { put("keying", "sdes"); put("suite", "AEAD_AES_256_GCM") }))
        fun text(value: JsonObject): String {
            val runtime = ConfigurationFactsCodec.runtime(status(value))!!
            return configurationCallFactsSummary(ConfigurationInstance(ConfigurationInstanceId("opaque"), "0001", ConfigurationLifecycle.RUNNING,
                ConfigurationTransport.WIFI, "", "", "", "", "epdg.example", "ims.example", "001", "01", runtimeFacts = runtime))
        }
        assertTrue(text(secure).contains("媒体保护：SDES · AES-GCM 256"))
        assertTrue(text(JsonObject(media() + ("security" to buildJsonObject { put("keying", "none") }))).contains("媒体保护：RTP（无 SRTP）"))
        assertTrue(text(media()).contains("媒体保护：未报告"))
    }

    @Test fun pcmuRuntimeKeepsAsymmetricPT0AndItsOwnLabelWithoutInventingQuality() {
        val value = JsonObject(media() + mapOf("codec" to JsonPrimitive("pcmu"), "framing" to JsonPrimitive("sample"),
            "clockRate" to JsonPrimitive(8000), "payloadType" to JsonPrimitive(110), "receivePayloadType" to JsonPrimitive(0),
            "quality" to buildJsonObject { put("revision", 0) }))
        val runtime = ConfigurationFactsCodec.runtime(status(value))!!
        val facts = runtime.call!!.media!!
        assertEquals("pcmu", facts.codec.wire)
        assertEquals("sample", facts.framing.wire)
        assertEquals(110, facts.payloadType)
        assertEquals(0, facts.receivePayloadType)
        assertNull(facts.quality.receive)
        assertNull(facts.quality.send)
        val row = ConfigurationInstance(ConfigurationInstanceId("opaque"), "0001", ConfigurationLifecycle.RUNNING,
            ConfigurationTransport.WIFI, "", "", "", "", "epdg.example", "ims.example", "001", "01", runtimeFacts = runtime)
        assertTrue(configurationCallFactsSummary(row).contains("实际媒体：PCMU / SAMPLE，双向，8000 Hz"))
        for ((key, entry) in listOf("framing" to JsonPrimitive("be"), "framing" to JsonPrimitive("oa"),
            "clockRate" to JsonPrimitive(16000), "payloadType" to JsonPrimitive(8), "receivePayloadType" to JsonPrimitive(95))) {
            assertFailsWith<ConfigurationFormatException> { decode(JsonObject(value + (key to entry))) }
        }
        assertFailsWith<ConfigurationFormatException> { decode(JsonObject(media() + ("framing" to JsonPrimitive("sample")))) }
    }

    @Test fun theExistingCallFactsSummaryShowsOnlyActualMediaAndMarksUnmeasuredDirections() {
        val runtime = ConfigurationFactsCodec.runtime(status(media()))!!
        val row = ConfigurationInstance(ConfigurationInstanceId("opaque"), "0001", ConfigurationLifecycle.RUNNING,
            ConfigurationTransport.WIFI, "", "", "", "", "epdg.example", "ims.example", "001", "01", runtimeFacts = runtime)
        val text = configurationCallFactsSummary(row)
        assertTrue(text.contains("实际媒体：AMR-WB / OA，双向，16000 Hz"))
        assertTrue(text.contains("发送 60 ms，接收上限 80 ms"))
        assertTrue(text.contains("本地接收：未测量"))
        assertTrue(text.contains("对端报告：丢包分数 64/256，抖动 20 ms，累计丢包 -2"))
        assertTrue(text.contains("不代表可听"))
        assertFalse(text.contains("media-epoch") || text.contains(OLD) || text.contains(NEXT))
        val stopped = configurationCallFactsSummary(row.copy(lifecycle = ConfigurationLifecycle.STOPPED))
        assertTrue(stopped.contains("未知"))
        assertFalse(stopped.contains("AMR-WB") || stopped.contains("64/256"))
        val absent = configurationCallFactsSummary(row.copy(runtimeFacts = runtime.copy(call = runtime.call!!.copy(media = null))))
        assertTrue(absent.contains("实际媒体尚未确认"))
        assertFalse(absent.contains("AMR-WB") || absent.contains("64/256"))
    }

    @Test fun runningMediaIsTypedSeparatelyFromLoadedDefaultsAndItsCapturedPolicy() {
        val facts = decode(media())!!
        assertEquals(OLD, facts.configRevision)
        val running = facts.media!!
        assertEquals("media-epoch", running.epoch)
        assertEquals(3L, running.revision)
        assertEquals(ConfigurationSpeechCodec.AMR_WB, running.codec)
        assertEquals(ConfigurationSpeechFraming.OA, running.framing)
        assertEquals(16000, running.clockRate)
        assertEquals(96, running.payloadType)
        assertEquals(112, running.receivePayloadType)
        assertEquals(60, running.sendPtimeMs)
        assertEquals(80, running.receiveMaxPtimeMs)
        assertEquals("sendrecv", running.direction)
        assertEquals(60000, running.inactivityTimeoutMs)
        assertEquals(ConfigurationTransportQuality(2, send = ConfigurationTransportQualitySample(64, 20, -2)), running.quality)
        assertEquals("ConfigurationRuntimeMediaFacts", running.toString())
        assertEquals("ConfigurationTransportQuality", running.quality.toString())
        assertEquals("ConfigurationTransportQualitySample", running.quality.send.toString())
        // Runtime facts describe the negotiated peer packetization, not only
        // the smaller values used by one editor or one carrier example.
        for (ptime in 20..240 step 20) {
            val packetization = JsonObject(media() + ("sendPtimeMs" to JsonPrimitive(ptime)))
            assertEquals(ptime, decode(packetization)!!.media!!.sendPtimeMs)
        }
    }

    @Test fun oldEnginesUnknownDirectionsAndRevokedReceiveEvidenceNeverBecomeHealthyZeros() {
        assertNull(decode(null)!!.media)
        val unknown = JsonObject(media() + ("quality" to buildJsonObject { put("revision", 0) }))
        val quality = decode(unknown)!!.media!!.quality
        assertEquals(0L, quality.revision)
        assertNull(quality.receive)
        assertNull(quality.send)
        val revoked = JsonObject(unknown + ("quality" to buildJsonObject { put("revision", 7) }))
        assertEquals(ConfigurationTransportQuality(7), decode(revoked)!!.media!!.quality)
        assertNull(ConfigurationFactsCodec.runtime(status(null, includeCall = false))!!.call)
    }

    @Test fun signedLossAndJitterBoundsUseTheActualNarrowbandOrWidebandClock() {
        for ((codec, clock) in listOf("amr-nb" to 8000, "amr-wb" to 16000)) {
            val jitterLimit = 4294967295L * 1000 / clock
            val value = JsonObject(media() + mapOf("codec" to JsonPrimitive(codec), "clockRate" to JsonPrimitive(clock),
                "quality" to Json.parseToJsonElement("""{"revision":1,"receive":{"fractionLost":255,"jitterMs":$jitterLimit,"totalLost":-8388608}}""")))
            assertEquals(jitterLimit.toInt(), decode(value)!!.media!!.quality.receive!!.jitterMs)
            assertEquals(-8388608, decode(value)!!.media!!.quality.receive!!.totalLost)
        }
    }

    @Test fun privateUnknownMalformedAndUnitConflictingRuntimeLeavesAreRejectedAtTheWireBoundary() {
        val valid = media()
        val malformed = listOf<JsonElement>(JsonNull, JsonArray(emptyList()), JsonObject(emptyMap())) +
            listOf("peer", "ssrc", "remote", "cname", "mode").map { JsonObject(valid + (it to JsonPrimitive("not-public"))) } +
            listOf("epoch" to JsonPrimitive(""), "epoch" to JsonPrimitive("bad\nepoch"), "revision" to JsonPrimitive(0),
                "revision" to JsonPrimitive("3"), "codec" to JsonPrimitive("evs"), "clockRate" to JsonPrimitive(8000),
                "framing" to JsonPrimitive("unknown"), "payloadType" to JsonPrimitive(95), "receivePayloadType" to JsonPrimitive(128),
                 "sendPtimeMs" to JsonPrimitive(0), "sendPtimeMs" to JsonPrimitive(19), "sendPtimeMs" to JsonPrimitive(21),
                 "sendPtimeMs" to JsonPrimitive(81), "sendPtimeMs" to JsonPrimitive(241), "sendPtimeMs" to JsonPrimitive(260),
                 "receiveMaxPtimeMs" to JsonPrimitive(241), "direction" to JsonPrimitive("remote"),
                "inactivityTimeoutMs" to JsonPrimitive(-1), "inactivityTimeoutMs" to JsonPrimitive(86400001),
                "quality" to JsonNull, "quality" to Json.parseToJsonElement("""{"revision":0,"send":{"fractionLost":0,"jitterMs":0,"totalLost":0}}"""),
                "quality" to Json.parseToJsonElement("""{"revision":1,"send":{"fractionLost":256,"jitterMs":20,"totalLost":0}}"""),
                "quality" to Json.parseToJsonElement("""{"revision":1,"receive":null}"""),
                "quality" to Json.parseToJsonElement("""{"revision":1,"receive":{"fractionLost":0,"jitterMs":268435456,"totalLost":0}}"""),
                "quality" to Json.parseToJsonElement("""{"revision":1,"receive":{"fractionLost":0,"jitterMs":1.0,"totalLost":0}}"""),
                "quality" to Json.parseToJsonElement("""{"revision":1,"receive":{"fractionLost":0,"jitterMs":0,"totalLost":8388608}}"""),
                "quality" to Json.parseToJsonElement("""{"revision":1,"receive":{"fractionLost":0,"jitterMs":0,"totalLost":0,"ssrc":77}}"""))
                .map { (key, value) -> JsonObject(valid + (key to value)) } + valid.keys.map { JsonObject(valid - it) }
        malformed.forEach { assertFailsWith<ConfigurationFormatException> { decode(it) } }
    }

    private fun decode(media: JsonElement?): ConfigurationCallFacts? = ConfigurationFactsCodec.runtime(status(media))!!.call
    private fun status(media: JsonElement?, includeCall: Boolean = true) = buildJsonObject {
        put("configuration", buildJsonObject {
            put("schema", 1)
            put("loaded", buildJsonObject {
                put("schema", 1); put("configRevision", NEXT); put("resolutionRevision", "rf1_" + "a".repeat(64)); put("fields", JsonArray(emptyList()))
            })
            put("negotiated", JsonObject(emptyMap()))
            if (includeCall) put("call", buildJsonObject { put("configRevision", OLD); media?.let { put("media", it) } })
        })
    }
    private fun media() = Json.parseToJsonElement("""{"epoch":"media-epoch","revision":3,"codec":"amr-wb","framing":"oa","clockRate":16000,"payloadType":96,"receivePayloadType":112,"sendPtimeMs":60,"receiveMaxPtimeMs":80,"direction":"sendrecv","inactivityTimeoutMs":60000,"quality":{"revision":2,"send":{"fractionLost":64,"jitterMs":20,"totalLost":-2}}}""").jsonObject
    private companion object {
        const val OLD = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_ponmlkjihgfedcbaponmlkjihgfedcba"
    }
}
