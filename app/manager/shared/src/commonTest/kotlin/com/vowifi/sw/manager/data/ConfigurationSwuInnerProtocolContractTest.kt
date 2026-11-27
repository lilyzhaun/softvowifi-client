package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationSwuInnerProtocolContractTest {
    @Test fun completeInnerProtocolRoundTripsThroughActualAccessCodec() {
        val value = ConfigurationSwuAccessCodec.decode(Json.parseToJsonElement(GROUP))
        assertEquals(Json.parseToJsonElement(GROUP), ConfigurationSwuAccessCodec.encode(value))
        assertFalse(value.toString().contains("voice.example.test"))
        assertFalse(value.policy.toString().contains("ipv6"))
    }

    @Test fun currentFactsRetainBothProtocolsWithoutChoosingFromLabels() {
        val parsed = assertNotNull(ConfigurationFactsCodec.local(facts()))
        val selected = assertNotNull(ConfigurationSwuAccessCodec.read(parsed))
        assertEquals(Json.parseToJsonElement(GROUP), ConfigurationSwuAccessCodec.encode(selected))
        assertEquals(REVISION, parsed.configRevision)
    }

    @Test fun nextStartDeltaKeepsTheWholeGroupAndRevisionBound() {
        val before = ConfigurationOverrides(ConfigurationTransport.WIFI, "", "", "", "",
            expectedRevision = REVISION, swuAccess = ConfigurationSwuAccess())
        val next = before.copy(swuAccess = ConfigurationSwuAccessCodec.decode(Json.parseToJsonElement(GROUP)))
        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(before, next)).jsonObject
        assertEquals(JsonPrimitive(REVISION), patch["expectedRevision"])
        assertEquals(buildJsonObject { put("swuAccess", Json.parseToJsonElement(GROUP)) }, patch["overrides"])
        assertEquals(buildJsonObject { put("swuAccess", JsonNull) }, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(next, before)))
    }

    @Test fun loadedProtocolsRemainConfigurationNotNegotiatedConnectivity() {
        val runtime = assertNotNull(ConfigurationFactsCodec.runtime(buildJsonObject {
            put("tunnel", "BACKOFF")
            put("configuration", buildJsonObject {
                put("schema", 1); put("loaded", facts()); put("negotiated", buildJsonObject {})
            })
        }))
        assertNull(runtime.tunnel)
        val selected = assertNotNull(ConfigurationSwuAccessCodec.read(runtime.loaded))
        assertEquals(Json.parseToJsonElement(GROUP), ConfigurationSwuAccessCodec.encode(selected))
    }

    @Test fun legacyThreeFieldAccessDoesNotMaterializeAnInnerOverride() {
        val group = Json.parseToJsonElement(LEGACY)
        val parsed = ConfigurationSwuAccessCodec.decode(group)
        assertEquals(group, ConfigurationSwuAccessCodec.encode(parsed))
        assertFalse(ConfigurationSwuAccessCodec.encode(parsed).jsonObject.containsKey("innerProtocol"))
    }

    @Test fun incompleteOrOuterFamilyValuesAreRejectedAfterTheSupportedControl() {
        assertEquals(Json.parseToJsonElement(GROUP),
            ConfigurationSwuAccessCodec.encode(ConfigurationSwuAccessCodec.decode(Json.parseToJsonElement(GROUP))))
        for (inner in listOf("null", "{}", "[]", "true", "\"ipv4\"",
            """{"home":"ipv4"}""", """{"home":"ipv4","roaming":null}""",
            """{"home":"prefer-ipv4","roaming":"ipv6"}""", """{"home":"ipv4-only","roaming":"ipv6"}""",
            """{"home":"ipv4","roaming":"ipv6","apn":"other.test"}""")) {
            val bad = Json.parseToJsonElement(LEGACY).jsonObject.toMutableMap()
            bad["innerProtocol"] = Json.parseToJsonElement(inner)
            assertFailsWith<ConfigurationFormatException> { ConfigurationSwuAccessCodec.decode(JsonObject(bad)) }
        }
    }

    private fun facts() = buildJsonObject {
        put("schema", 1); put("configRevision", REVISION); put("resolutionRevision", "rf1_" + "a".repeat(64))
        put("fields", buildJsonArray {})
        put("swuAccess", buildJsonObject {
            put("state", "known"); put("source", "user")
            put("value", Json.parseToJsonElement(GROUP)); put("override", Json.parseToJsonElement(GROUP))
        })
    }

    private companion object {
        const val REVISION = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val LEGACY = """{"purpose":"ims","requestedApn":"voice.example.test","interfaceMtu":1340}"""
        const val GROUP = """{"purpose":"ims","requestedApn":"voice.example.test","interfaceMtu":1340,"innerProtocol":{"home":"ipv4","roaming":"ipv6"}}"""
    }
}
