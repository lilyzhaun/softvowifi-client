package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.ui.configurationApplicationSummary
import com.vowifi.sw.manager.ui.configurationFactsSummary
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ConfigurationFactsCodecTest {
    @Test fun malformedRecognizedFactsCannotBeSilentlyAcceptedAsAuthoritativeConfiguration() {
        val revision = "r1_abcdefghijklmnopabcdefghijklmnop"
        val base = """"ok":true,"formatState":"current","configVersion":1,"configRevision":"$revision","displayLabel":"0001","lifecycle":"running","transport":"wifi","smsc":"","epdgFqdn":"","imsRealm":"","dns":"","resolvedEpdgFqdn":"epdg.example.test","resolvedImsRealm":"ims.example.test","mcc":"310","mnc":"260""""
        val resolution = "rf1_" + "a".repeat(64)
        for (facts in listOf(
            """{"schema":1,"configRevision":"wrong","resolutionRevision":"$resolution","fields":[]}""",
            """{"schema":1,"configRevision":"$revision","resolutionRevision":"wrong","fields":[]}""",
            """{"schema":1,"configRevision":"$revision","resolutionRevision":"$resolution","fields":[{"field":"epdgFqdn","state":"known","source":"user"}]}""",
            """{"schema":1,"configRevision":"$revision","resolutionRevision":"$resolution","fields":[{"field":"epdgFqdn","state":"unknown","source":"formula","value":"invented.test"}]}""",
            """{"schema":1,"configRevision":"$revision","resolutionRevision":"$resolution","fields":[{"field":"imsi","state":"known","source":"sim","value":"redacted"}]}""",
        )) {
            assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.show("{$base,\"configuration\":$facts}") }
        }
    }

    @Test fun localAndRuntimeFactsDoNotTreatSavedPolicyAsLoadedOrNegotiated() {
        val revision = "r1_abcdefghijklmnopabcdefghijklmnop"
        val local = ConfigurationFactsCodec.local(facts(revision))!!
        val snapshot = ConfigurationCodec.show(base(revision))
        val instance = ConfigurationEvaluator.instance(com.vowifi.sw.manager.model.ConfigurationInstanceId("opaque"), snapshot)
            .copy(facts = local)
        assertTrue(configurationApplicationSummary(instance).contains("未知"))
        val runtime = ConfigurationFactsCodec.runtime(buildJsonObject {
            put("configuration", buildJsonObject { put("schema", 1); put("loaded", facts(revision)); put("negotiated", buildJsonObject {}) })
        })!!
        assertNull(runtime.registrationSeconds)
        assertTrue(configurationApplicationSummary(instance.copy(runtimeFacts = runtime)).contains("已加载"))
        assertTrue(configurationApplicationSummary(instance.copy(runtimeFacts = runtime.copy(
            loaded = runtime.loaded.copy(configRevision = "r1_ponmlkjihgfedcbaponmlkjihgfedcba"),
        ))).contains("尚未用于"))
        assertTrue(configurationApplicationSummary(instance.copy(runtimeFacts = runtime.copy(
            loaded = runtime.loaded.copy(resolutionRevision = "rf1_" + "b".repeat(64)),
        ))).contains("目录已变化"))
        assertTrue(configurationApplicationSummary(instance.copy(lifecycle = ConfigurationLifecycle.STOPPED, runtimeFacts = runtime)).contains("下次启动"))
        assertTrue(configurationFactsSummary(instance, "dns").contains("未知"))
    }

    @Test fun oldOrUnsupportedOptionalFactsStayUnknownAndNegotiationIsStrict() {
        assertNull(ConfigurationFactsCodec.runtime(buildJsonObject {}))
        assertNull(ConfigurationFactsCodec.local(buildJsonObject { put("schema", 2) }))
        val revision = "r1_abcdefghijklmnopabcdefghijklmnop"
        val accepted = ConfigurationFactsCodec.runtime(buildJsonObject {
            put("configuration", buildJsonObject {
                put("schema", 1); put("loaded", facts(revision))
                put("negotiated", buildJsonObject { put("registrationSeconds", 600); put("integrity", "hmac-sha-1-96"); put("encryption", "null") })
            })
        })!!
        assertEquals(600L, accepted.registrationSeconds)
        for (raw in listOf("""{"registrationSeconds":0}""", """{"integrity":"hmac-sha-1-96"}""", """{"registrationSeconds":600,"key":"secret"}""")) {
            assertFailsWith<ConfigurationFormatException> {
                ConfigurationFactsCodec.runtime(buildJsonObject {
                    put("configuration", buildJsonObject { put("schema", 1); put("loaded", facts(revision)); put("negotiated", Json.parseToJsonElement(raw)) })
                })
            }
        }
    }

    private fun facts(revision: String) = buildJsonObject {
        put("schema", 1); put("configRevision", revision); put("resolutionRevision", "rf1_" + "a".repeat(64))
        put("fields", buildJsonArray { add(buildJsonObject { put("field", "dns"); put("state", "unknown"); put("source", "network"); put("value", "") }) })
    }

    private fun base(revision: String) = """{"ok":true,"formatState":"current","configVersion":1,"configRevision":"$revision","displayLabel":"0001","lifecycle":"running","transport":"wifi","smsc":"","epdgFqdn":"","imsRealm":"","dns":"","resolvedEpdgFqdn":"epdg.example.test","resolvedImsRealm":"ims.example.test","mcc":"310","mnc":"260"}"""
}
