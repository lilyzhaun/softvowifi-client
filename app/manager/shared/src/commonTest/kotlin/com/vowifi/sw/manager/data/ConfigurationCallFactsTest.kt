package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.configurationCallFactsSummary
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationCallFactsTest {
    @Test fun theCurrentCallKeepsItsCapturedRevisionWhenTheNextCallDefaultChanges() {
        val result = ConfigurationFactsCodec.runtime(status(call(300, "remote")))!!
        assertEquals(NEXT, result.loaded.configRevision)
        assertEquals(ConfigurationCallFacts(OLD, 300, "remote"), result.call)
        assertNull(result.registrationSeconds)
        assertEquals("ConfigurationCallFacts", result.call.toString())
    }

    @Test fun missingCallAndRevisionOnlyNeverInventAnAcceptedSessionOrTimerDisabledState() {
        assertNull(ConfigurationFactsCodec.runtime(status())!!.call)
        val captured = ConfigurationFactsCodec.runtime(status(call()))!!.call!!
        assertEquals(OLD, captured.configRevision)
        assertNull(captured.sessionSeconds)
        assertNull(captured.sessionRefresher)
        assertEquals(Int.MAX_VALUE.toLong(), ConfigurationFactsCodec.runtime(status(call(Int.MAX_VALUE, "local")))!!.call?.sessionSeconds)
    }

    @Test fun recognizedCallFactsRejectUnknownKeysInvalidRevisionsAndHalfAgreements() {
        for (raw in listOf(
            "null", "[]", "{}", """{"configRevision":"bad"}""",
            """{"configRevision":"$OLD","sessionSeconds":300}""",
            """{"configRevision":"$OLD","sessionRefresher":"remote"}""",
            """{"configRevision":"$OLD","sessionSeconds":0,"sessionRefresher":"remote"}""",
            """{"configRevision":"$OLD","sessionSeconds":89,"sessionRefresher":"remote"}""",
            """{"configRevision":"$OLD","sessionSeconds":2147483648,"sessionRefresher":"remote"}""",
            """{"configRevision":"$OLD","sessionSeconds":"300","sessionRefresher":"remote"}""",
            """{"configRevision":"$OLD","sessionSeconds":300,"sessionRefresher":"uas"}""",
            """{"configRevision":"$OLD","sessionSeconds":300,"sessionRefresher":null}""",
            """{"configRevision":"$OLD","sessionSeconds":null,"sessionRefresher":"local"}""",
            """{"configRevision":"$OLD","peer":"not-public"}""",
        )) assertFailsWith<ConfigurationFormatException>(raw) {
            ConfigurationFactsCodec.runtime(status(Json.parseToJsonElement(raw)))
        }
    }

    @Test fun presentationSeparatesCapturedCallPolicyNegotiatedSessionAndStoppedUnknowns() {
        val row = ConfigurationInstance(ConfigurationInstanceId("opaque"), "0001", ConfigurationLifecycle.RUNNING,
            ConfigurationTransport.WIFI, "", "", "", "", "epdg.example", "ims.example", "001", "01")
        val live = row.copy(runtimeFacts = ConfigurationFactsCodec.runtime(status(call(300, "remote"))))
        val summary = configurationCallFactsSummary(live)
        assertTrue(summary.contains("先前捕获"))
        assertTrue(summary.contains("300 秒"))
        assertTrue(summary.contains("对端"))
        assertFalse(summary.contains(OLD) || summary.contains(NEXT))
        assertTrue(configurationCallFactsSummary(live.copy(lifecycle = ConfigurationLifecycle.STOPPED)).contains("未知"))
        assertFalse(configurationCallFactsSummary(live.copy(lifecycle = ConfigurationLifecycle.STOPPED)).contains("300"))
        val pending = row.copy(runtimeFacts = ConfigurationFactsCodec.runtime(status(call())))
        assertTrue(configurationCallFactsSummary(pending).contains("暂无已确认"))
        assertFalse(configurationCallFactsSummary(pending).contains("已关闭"))
        val same = row.copy(runtimeFacts = ConfigurationFactsCodec.runtime(status(call(300, "local", NEXT))))
        assertTrue(configurationCallFactsSummary(same).contains("当前运行默认"))
        assertTrue(configurationCallFactsSummary(same).contains("本机"))
    }

    @Test fun theAdditiveProjectionDoesNotChangeTheExistingCallControlSnapshot() {
        val response = EngineProtocolFrame.Response(1, buildJsonObject {
            put("epoch", "opaque-epoch"); put("revision", 3); put("stage", "IN_CALL")
            put("callId", "opaque-call"); put("direction", "OUTGOING")
            put("confirmedElapsedMs", 120); put("dtmfSupported", false); put("configuration", call(300, "local"))
        }, null)
        val decoded = CallSnapshotCodec.decode(response)!!
        assertEquals(CallPhase.InCall, decoded.phase)
        assertEquals("opaque-call", decoded.token)
        assertEquals(120L, decoded.elapsed)
    }

    private fun call(seconds: Int? = null, refresher: String? = null, revision: String = OLD) = buildJsonObject {
        put("configRevision", revision)
        seconds?.let { put("sessionSeconds", it) }
        refresher?.let { put("sessionRefresher", it) }
    }
    private fun status(call: JsonElement? = null) = buildJsonObject {
        put("configuration", buildJsonObject {
            put("schema", 1)
            put("loaded", buildJsonObject {
                put("schema", 1); put("configRevision", NEXT); put("resolutionRevision", "rf1_" + "a".repeat(64)); put("fields", JsonArray(emptyList()))
            })
            put("negotiated", JsonObject(emptyMap()))
            call?.let { put("call", it) }
        })
    }
    private companion object {
        const val OLD = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_ponmlkjihgfedcbaponmlkjihgfedcba"
    }
}
