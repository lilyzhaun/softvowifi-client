package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.serialization.json.*

class PublicClientCapabilitiesTest {
    @Test
    fun canonicalFixturePreservesFourFieldsAndEmptyIdentity() {
        // Given
        val response = frame(CANONICAL)
        // When
        val result = EngineClientCapabilitiesCodec.decode(response)
        // Then
        val capabilities = assertIs<ClientCapabilitiesResult.Ready>(result).capabilities
        assertEquals("00647499b77a2163531b4fa05e1014fb", capabilities.instanceKey)
        assertEquals("0.1.0", capabilities.engineVersion)
        assertEquals(MetadataField.entries.toList(), capabilities.configMetadata.map { it.field })
        assertEquals(listOf("tcp_ipsec_3gpp", "absent", "", ""), capabilities.configMetadata.map { it.value })
        assertEquals(listOf(MetadataSource.Default, MetadataSource.Default, MetadataSource.HomeMcc, MetadataSource.Identity),
            capabilities.configMetadata.map { it.source })
        assertTrue(capabilities.configMetadata.all { it.decision == MetadataDecision.Inherited })
    }

    @Test
    fun requestUsesApprovedSchemaAndRequiredCapability() {
        // Given
        val params = EngineClientCapabilitiesCodec.params()
        // When
        val request = Json.parseToJsonElement(EngineProtocolCodec.request(41, "getClientCapabilities", params))
        // Then
        assertEquals(Json.parseToJsonElement("""{"id":41,"method":"getClientCapabilities","params":{"schema":1,"required":["config.metadata.v1"]}}"""), request)
    }

    @Test
    fun unknownSchemaAndProtocolAreUnsupported() {
        // Given
        for (wire in listOf(CANONICAL.replace("\"schema\":1", "\"schema\":2"),
            CANONICAL.replace("\"version\":1", "\"version\":2"))) {
            // When
            val result = EngineClientCapabilitiesCodec.decode(frame(wire))
            // Then
            assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported), result)
        }
    }

    @Test
    fun invalidWireFailsClosedWithoutEcho() {
        // Given
        val invalid = listOf(
            CANONICAL.replace("\"schema\":1", "\"schema\":true"),
            CANONICAL.replace("\"schema\":1", "\"schema\":\"1\""),
            CANONICAL.replace("\"engineVersion\":\"0.1.0\"", "\"engineVersion\":\"private-canary@ims.invalid\""),
            CANONICAL.replace("\"source\":\"identity\"", "\"source\":\"carrier\""),
            CANONICAL.replace("\"value\":\"\",\"source\":\"identity\"", "\"value\":\"private-canary.invalid\",\"source\":\"identity\""),
            CANONICAL.replace("\"decision\":\"inherited\"", "\"decision\":\"override\""),
            CANONICAL.replace("\"field\":\"paniCountry\"", "\"field\":\"paniInitial\""),
            CANONICAL.replace("\"rpc.ping\"", "true"),
            CANONICAL.replace("\"rpc.ping\"", "1.5"),
            CANONICAL.replace("\"rpc.ping\"", "\"rpc.getStatus\""),
            CANONICAL.replace("\"rpc.ping\"", "\"é\""),
            CANONICAL.replace("00647499b77a2163531b4fa05e1014fb", "00647499B77a2163531b4fa05e1014fb"),
        )
        for (wire in invalid) {
            // When
            val result = EngineClientCapabilitiesCodec.decode(frame(wire))
            // Then
            assertIs<ClientCapabilitiesResult.Unavailable>(result)
            assertFalse(result.toString().contains("private-canary"))
        }
    }

    @Test
    fun opaqueNumericInstanceKeyAndOptionalUnknownCapabilityAreAccepted() {
        // Given
        val wire = CANONICAL.replace("00647499b77a2163531b4fa05e1014fb", "12345678901234567890123456789012")
            .replace("\"rpc.stop\"", "\"rpc.stop\",\"vendor.optional\"")
        // When
        val result = EngineClientCapabilitiesCodec.decode(frame(wire))
        // Then
        assertIs<ClientCapabilitiesResult.Ready>(result)
    }

    @Test
    fun missingOrUnknownRequiredCapabilityIsUnsupported() {
        // Given / When
        val missing = EngineClientCapabilitiesCodec.decode(frame(CANONICAL.replace("\"config.metadata.v1\",", "")))
        val unknown = EngineClientCapabilitiesCodec.decode(
            frame(CANONICAL.replace("\"rpc.stop\"", "\"rpc.stop\",\"vendor.optional\"")), setOf("vendor.optional"))
        // Then
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported), missing)
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported), unknown)
    }

    @Test
    fun privateAndUnknownKeysAreRejectedAtEveryMetadataBoundary() {
        // Given
        val root = requireNotNull(frame(CANONICAL).result)
        for (key in listOf("imsi", "iccid", "impi", "impu", "peer", "epdg", "pcscf", "smsc", "unknown")) {
            val canary = key to JsonPrimitive("raw-private-canary")
            val variants = listOf(
                JsonObject(root + canary),
                JsonObject(root + ("protocol" to JsonObject(root.getValue("protocol").jsonObject + canary))),
                JsonObject(root + ("instance" to JsonObject(root.getValue("instance").jsonObject + canary))),
                JsonObject(root + ("configMetadata" to JsonArray(root.getValue("configMetadata").jsonArray.map {
                    JsonObject(it.jsonObject + canary)
                }))),
            )
            for (variant in variants) {
                // When
                val result = EngineClientCapabilitiesCodec.decode(EngineProtocolFrame.Response(1, variant, null))
                // Then
                assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Invalid), result)
            }
        }
    }

    @Test
    fun missingFieldsInvalidTypesAndUnboundedIdsAreRejected() {
        // Given
        val root = requireNotNull(frame(CANONICAL).result)
        val variants = root.keys.map { JsonObject(root - it) } + listOf(
            JsonArray(listOf(JsonPrimitive("config.metadata.v1"), JsonPrimitive("a".repeat(65)))),
            JsonArray(listOf(JsonPrimitive("rpc.stop"), JsonPrimitive("config.metadata.v1"))),
            JsonArray((0..64).map { JsonPrimitive("id.$it") }),
            JsonArray(listOf(JsonPrimitive("config.metadata.v1"), JsonPrimitive("raw 123"))),
        ).map { JsonObject(root + ("capabilities" to it)) }
        for (variant in variants) {
            // When
            val result = EngineClientCapabilitiesCodec.decode(EngineProtocolFrame.Response(1, variant, null))
            // Then
            assertIs<ClientCapabilitiesResult.Unavailable>(result)
        }
        for (invalid in listOf("true", "1.0", "\"1\"", "null")) {
            assertIs<ClientCapabilitiesResult.Unavailable>(EngineClientCapabilitiesCodec.decode(
                frame(CANONICAL.replace("\"version\":1", "\"version\":$invalid"))))
        }
    }

    @Test
    fun metadataRequiresKnownFieldSourceDecisionAndStringValue() {
        // Given
        val root = requireNotNull(frame(CANONICAL).result)
        val metadata = root.getValue("configMetadata").jsonArray
        val item = metadata.first().jsonObject
        val invalidItems = item.keys.map { JsonObject(item - it) } + listOf(
            JsonObject(item + ("field" to JsonPrimitive("unknown"))),
            JsonObject(item + ("source" to JsonPrimitive("identity"))),
            JsonObject(item + ("decision" to JsonPrimitive("unknown"))),
            JsonObject(item + ("value" to JsonPrimitive(true))),
            JsonObject(item + ("value" to JsonPrimitive("private-canary"))),
        )
        for (invalid in invalidItems) {
            val variant = JsonObject(root + ("configMetadata" to JsonArray(listOf(invalid) + metadata.drop(1))))
            // When
            val result = EngineClientCapabilitiesCodec.decode(EngineProtocolFrame.Response(1, variant, null))
            // Then
            assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Invalid), result)
        }
    }

    @Test
    fun userDomainPreservesCaseAndRejectsIdentityDigitsAndAddresses() {
        // Given
        val template = CANONICAL.replace("\"value\":\"\",\"source\":\"identity\",\"decision\":\"inherited\"",
            "\"value\":\"MiXeD.Example.\",\"source\":\"user\",\"decision\":\"override\"")
        // When
        val result = EngineClientCapabilitiesCodec.decode(frame(template))
        // Then
        assertEquals("MiXeD.Example.", assertIs<ClientCapabilitiesResult.Ready>(result).capabilities.configMetadata.last().value)
        for (invalid in listOf("123456789012345.example", "192.0.2.1", "-bad.example", "a..b", "a@b", "a:5060", "")) {
            assertIs<ClientCapabilitiesResult.Unavailable>(EngineClientCapabilitiesCodec.decode(frame(template.replace("MiXeD.Example.", invalid))))
        }
    }

    private fun frame(wire: String) = assertIs<EngineProtocolFrame.Response>(EngineProtocolCodec.decode(wire))

    companion object {
        // Fixed synthetic fixture, never evidence of a fresh Go run.
        const val CANONICAL = """{"id":41,"result":{"schema":1,"protocol":{"version":1,"engineVersion":"0.1.0"},"instance":{"key":"00647499b77a2163531b4fa05e1014fb"},"capabilities":["config.metadata.v1","rpc.getConfig","rpc.getStatus","rpc.ping","rpc.stop"],"configMetadata":[{"field":"imsSipSecurity","value":"tcp_ipsec_3gpp","source":"default","decision":"inherited"},{"field":"paniInitial","value":"absent","source":"default","decision":"inherited"},{"field":"paniCountry","value":"","source":"home_mcc","decision":"inherited"},{"field":"imsDigestRealm","value":"","source":"identity","decision":"inherited"}]}}"""
    }
}
