package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.configurationTunnelFactsSummary
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationNegotiatedTunnelFactsTest {
    @Test fun authenticatedTuplesRemainSeparateFromLoadedPolicyAndIMSRegistration() {
        val facts = ConfigurationFactsCodec.runtime(status(selection()))!!
        assertNull(facts.registrationSeconds)
        assertEquals(256, facts.tunnel?.ike?.keyBits)
        assertEquals(14, facts.tunnel?.ike?.dh)
        assertEquals(5, facts.tunnel?.ike?.prf)
        assertEquals(20, facts.tunnel?.child?.encryption)
        assertEquals(0, facts.tunnel?.child?.integrity)
        assertEquals(0, facts.tunnel?.child?.pfs)
        assertEquals(false, facts.tunnel?.child?.esn)
        val row = ConfigurationInstance(ConfigurationInstanceId("opaque"), "0001", ConfigurationLifecycle.RUNNING,
            ConfigurationTransport.WIFI, "", "", "", "", "epdg.example", "ims.example", "001", "01", runtimeFacts = facts)
        val text = configurationTunnelFactsSummary(row)
        assertTrue(text.contains("AES-CBC 256"))
        assertTrue(text.contains("HMAC-SHA2-256-128"))
        assertTrue(text.contains("PRF HMAC-SHA2-256"))
        assertTrue(text.contains("DH14"))
        assertTrue(text.contains("AES-GCM 128"))
        assertTrue(text.contains("无额外 PFS"))
        assertTrue(configurationTunnelFactsSummary(row.copy(lifecycle = ConfigurationLifecycle.STOPPED)).contains("未知"))
        assertFalse(configurationTunnelFactsSummary(row.copy(runtimeFacts = facts.copy(tunnel = null))).contains("AES"))
        assertEquals("ConfigurationNegotiatedTunnelFacts", facts.tunnel.toString())
    }

    @Test fun absentOldOrDisconnectedFactsCannotBecomeASelection() {
        assertNull(ConfigurationFactsCodec.runtime(status(null))!!.tunnel)
        assertNull(ConfigurationFactsCodec.runtime(status(JsonNull))!!.tunnel)
        for (state in listOf("CONNECTING", "DISCONNECTED", "BACKOFF", "unknown")) {
            assertNull(ConfigurationFactsCodec.runtime(status(selection(), state))!!.tunnel)
        }
        assertNull(ConfigurationFactsCodec.runtime(JsonObject(status(selection()) - "tunnel"))!!.tunnel)
    }

    @Test fun privateFieldsPartialTuplesInvalidTypesAndUnsupportedAlgorithmsAreRejected() {
        val valid = selection()
        val bad = listOf<JsonElement>(JsonArray(emptyList()), JsonObject(emptyMap()), JsonObject(valid - "child")) +
            listOf("keys", "spi", "peer", "imsi", "config").map { JsonObject(valid + (it to JsonPrimitive("not-public"))) } +
            listOf("keyBits" to JsonPrimitive("256"), "keyBits" to JsonPrimitive(0), "dh" to JsonPrimitive(999),
                "prf" to JsonPrimitive(0), "integrity" to JsonPrimitive(0), "encryption" to JsonPrimitive(999)).map { (key, value) ->
                JsonObject(valid + ("ike" to JsonObject(valid.getValue("ike").jsonObject + (key to value))))
            } +
            listOf("esn" to JsonPrimitive("false"), "esn" to JsonPrimitive(1), "esn" to JsonNull, "pfs" to JsonPrimitive(19),
                "integrity" to JsonPrimitive(12), "keyBits" to JsonPrimitive(4096), "key" to JsonPrimitive("not-public")).map { (key, value) ->
                JsonObject(valid + ("child" to JsonObject(valid.getValue("child").jsonObject + (key to value))))
            }
        bad.forEach { candidate -> assertFailsWith<ConfigurationFormatException> { ConfigurationFactsCodec.runtime(status(candidate)) } }
    }

    private fun selection() = Json.parseToJsonElement("""{"ike":{"encryption":12,"keyBits":256,"integrity":12,"prf":5,"dh":14},"child":{"encryption":20,"keyBits":128,"integrity":0,"esn":false,"pfs":0}}""").jsonObject

    private fun status(tunnel: JsonElement?, state: String = "CONNECTED") = buildJsonObject {
        put("tunnel", state)
        put("configuration", buildJsonObject {
            put("schema", 1)
            put("loaded", buildJsonObject {
                put("schema", 1); put("configRevision", "r1_abcdefghijklmnopabcdefghijklmnop")
                put("resolutionRevision", "rf1_" + "a".repeat(64)); put("fields", buildJsonArray {})
            })
            put("negotiated", buildJsonObject {})
            tunnel?.let { put("tunnel", it) }
        })
    }
}
