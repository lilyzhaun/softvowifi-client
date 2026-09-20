package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class PublicClientCapabilitiesTest {
    @Test
    fun canonicalProducerResponsePreservesEmptyIdentityAndHomeCountry() {
        val response = frame(CANONICAL)
        val result = EngineClientCapabilitiesCodec.decode(response)
        val capabilities = assertIs<ClientCapabilitiesResult.Ready>(result).capabilities
        assertEquals("00647499b77a2163531b4fa05e1014fb", capabilities.instanceKey)
        assertEquals("0.1.0", capabilities.engineVersion)
        assertEquals(4, capabilities.configMetadata.size)
        assertEquals(MetadataSource.Identity, capabilities.configMetadata.last().source)
        assertEquals("", capabilities.configMetadata.last().value)
        assertEquals("", capabilities.configMetadata[2].value)
    }

    @Test
    fun requestUsesApprovedSchemaAndRequiredCapability() {
        val params = EngineClientCapabilitiesCodec.params()
        val request = Json.parseToJsonElement(EngineProtocolCodec.request(41, "getClientCapabilities", params))
        assertEquals(Json.parseToJsonElement("""{"id":41,"method":"getClientCapabilities","params":{"schema":1,"required":["config.metadata.v1"]}}"""), request)
    }

    @Test
    fun invalidWireFailsClosedWithoutEcho() {
        val invalid = listOf(
            CANONICAL.replace("\"schema\":1", "\"schema\":2"),
            CANONICAL.replace("\"version\":1", "\"version\":1.0"),
            CANONICAL.replace("\"schema\":1", "\"schema\":true"),
            CANONICAL.replace("\"schema\":1", "\"schema\":\"1\""),
            CANONICAL.replace("\"schema\":1", "\"schema\":1,\"imsi\":\"private-canary\""),
            CANONICAL.replace("\"key\":", "\"peer\":\"private-canary\",\"key\":"),
            CANONICAL.replace("\"engineVersion\":\"0.1.0\"", "\"engineVersion\":\"private-canary@ims.invalid\""),
            CANONICAL.replace("\"source\":\"identity\"", "\"source\":\"carrier\""),
            CANONICAL.replace("\"value\":\"\",\"source\":\"identity\"", "\"value\":\"private-canary.invalid\",\"source\":\"identity\""),
            CANONICAL.replace("\"decision\":\"inherited\"", "\"decision\":\"override\""),
            CANONICAL.replace("\"field\":\"paniCountry\"", "\"field\":\"paniInitial\""),
            CANONICAL.replace("\"config.metadata.v1\",", ""),
            CANONICAL.replace("\"rpc.ping\"", "true"),
            CANONICAL.replace("\"rpc.ping\"", "1.5"),
            CANONICAL.replace("\"rpc.ping\"", "\"rpc.getStatus\""),
            CANONICAL.replace("\"rpc.ping\"", "\"é\""),
            CANONICAL.replace("00647499b77a2163531b4fa05e1014fb", "00647499B77a2163531b4fa05e1014fb"),
        )
        for (wire in invalid) {
            val result = EngineClientCapabilitiesCodec.decode(frame(wire))
            assertIs<ClientCapabilitiesResult.Unavailable>(result)
            assertFalse(result.toString().contains("private-canary"))
        }
    }

    @Test
    fun opaqueNumericInstanceKeyAndOptionalUnknownCapabilityAreAccepted() {
        val wire = CANONICAL.replace("00647499b77a2163531b4fa05e1014fb", "12345678901234567890123456789012")
            .replace("\"rpc.stop\"", "\"rpc.stop\",\"vendor.optional\"")
        val result = EngineClientCapabilitiesCodec.decode(frame(wire))
        assertIs<ClientCapabilitiesResult.Ready>(result)
    }

    @Test
    fun unknownClientRequiredCapabilityFailsEvenWhenAdvertised() {
        val wire = CANONICAL.replace("\"rpc.stop\"", "\"rpc.stop\",\"vendor.optional\"")
        val result = EngineClientCapabilitiesCodec.decode(frame(wire), setOf("vendor.optional"))
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported), result)
    }

    @Test
    fun privateAndUnknownKeysAreRejectedAtEveryMetadataBoundary() {
        val root = frame(CANONICAL).result!!
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
                val result = EngineClientCapabilitiesCodec.decode(EngineProtocolFrame.Response(1, variant, null))
                assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Invalid), result)
            }
        }
    }

    @Test
    fun missingFieldsInvalidTypesAndUnboundedIdsAreRejected() {
        val root = frame(CANONICAL).result!!
        for (key in root.keys) {
            assertIs<ClientCapabilitiesResult.Unavailable>(EngineClientCapabilitiesCodec.decode(EngineProtocolFrame.Response(1, JsonObject(root - key), null)))
        }
        val invalidIds = listOf(
            JsonArray(listOf(JsonPrimitive("config.metadata.v1"), JsonPrimitive("a".repeat(65)))),
            JsonArray(listOf(JsonPrimitive("rpc.stop"), JsonPrimitive("config.metadata.v1"))),
            JsonArray((0..64).map { JsonPrimitive("id.$it") }),
            JsonArray(listOf(JsonPrimitive("config.metadata.v1"), JsonPrimitive("raw 123"))),
        )
        for (ids in invalidIds) {
            val response = EngineProtocolFrame.Response(1, JsonObject(root + ("capabilities" to ids)), null)
            assertIs<ClientCapabilitiesResult.Unavailable>(EngineClientCapabilitiesCodec.decode(response))
        }
        for (invalid in listOf("2", "true", "1.0", "\"1\"", "null")) {
            assertIs<ClientCapabilitiesResult.Unavailable>(EngineClientCapabilitiesCodec.decode(frame(CANONICAL.replace("\"version\":1", "\"version\":$invalid"))))
        }
    }

    @Test
    fun userDomainPreservesCaseAndRejectsIdentityDigitsAndAddresses() {
        val template = CANONICAL.replace("\"value\":\"\",\"source\":\"identity\",\"decision\":\"inherited\"",
            "\"value\":\"MiXeD.Example.\",\"source\":\"user\",\"decision\":\"override\"")
        val result = EngineClientCapabilitiesCodec.decode(frame(template))
        assertEquals("MiXeD.Example.", assertIs<ClientCapabilitiesResult.Ready>(result).capabilities.configMetadata.last().value)
        for (invalid in listOf("123456789012345.example", "192.0.2.1", "-bad.example", "a..b", "a@b", "a:5060", "")) {
            assertIs<ClientCapabilitiesResult.Unavailable>(EngineClientCapabilitiesCodec.decode(frame(template.replace("MiXeD.Example.", invalid))))
        }
    }

    @Test
    fun oldRpcFailureDoesNotEchoServerMessage() = runTest {
        val source = EngineClientCapabilitiesSource { _, _ -> EngineProtocolFrame.Response(1, null, EngineProtocolError(1001, "private-canary")) }
        val result = source.query()
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported), result)
    }

    @Test
    fun timeoutIsBoundedAndTyped() = runTest {
        val source = EngineClientCapabilitiesSource { _, _ -> awaitCancellation() }
        val result = source.query()
        assertEquals(250, testScheduler.currentTime)
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Timeout), result)
    }

    @Test
    fun parentCancellationIsNotConvertedToFailure() = runTest {
        val cancelled = CancellationException("parent")
        val source = EngineClientCapabilitiesSource { _, _ -> throw cancelled }
        assertEquals("parent", assertFailsWith<CancellationException> { source.query() }.message)
    }

    @Test
    fun outerTimeoutPropagatesRatherThanBecomingOptionalFailure() = runTest {
        val source = EngineClientCapabilitiesSource { _, _ -> awaitCancellation() }
        assertFailsWith<TimeoutCancellationException> { withTimeout(100) { source.query() } }
        assertEquals(100, testScheduler.currentTime)
    }

    @Test
    fun transportFailureIsFixedAndDoesNotEchoRawJson() = runTest {
        val source = EngineClientCapabilitiesSource { _, _ -> throw IllegalStateException(CANONICAL) }
        val result = source.query()
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Transport), result)
    }

    private fun frame(wire: String) = assertIs<EngineProtocolFrame.Response>(EngineProtocolCodec.decode(wire))

    companion object {
        const val CANONICAL = """{"id":41,"result":{"schema":1,"protocol":{"version":1,"engineVersion":"0.1.0"},"instance":{"key":"00647499b77a2163531b4fa05e1014fb"},"capabilities":["config.metadata.v1","rpc.getConfig","rpc.getStatus","rpc.ping","rpc.stop"],"configMetadata":[{"field":"imsSipSecurity","value":"tcp_ipsec_3gpp","source":"default","decision":"inherited"},{"field":"paniInitial","value":"absent","source":"default","decision":"inherited"},{"field":"paniCountry","value":"","source":"home_mcc","decision":"inherited"},{"field":"imsDigestRealm","value":"","source":"identity","decision":"inherited"}]}}"""
    }
}
