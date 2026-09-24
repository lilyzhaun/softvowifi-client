package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import java.io.File
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue

class ProducerCapabilitiesContractTest {
    @Test
    fun freshGoProducerWireIsConsumedByKotlin() = runTest {
        // Given
        val response = freshResponse()
        val source = EngineClientCapabilitiesSource { method, params ->
            assertEquals("getClientCapabilities", method)
            assertEquals(Json.parseToJsonElement("""{"schema":1,"required":["config.metadata.v1"]}"""), params)
            response
        }
        // When
        val result = assertIs<ClientCapabilitiesResult.Ready>(source.query()).capabilities
        // Then
        assertEquals("0.1.0", result.engineVersion)
        assertTrue(Regex("[0-9a-f]{32}").matches(result.instanceKey))
        assertNotEquals("00647499b77a2163531b4fa05e1014fb", result.instanceKey)
        assertEquals(MetadataField.entries.toList(), result.configMetadata.map { it.field })
        assertEquals(listOf("tcp_ipsec_3gpp", "absent", "", ""), result.configMetadata.map { it.value })
        assertEquals(listOf(MetadataSource.Default, MetadataSource.Default, MetadataSource.HomeMcc, MetadataSource.Identity),
            result.configMetadata.map { it.source })
        assertTrue(result.configMetadata.all { it.decision == MetadataDecision.Inherited })
        assertEquals(listOf("config.metadata.v1", "rpc.getConfig", "rpc.getStatus", "rpc.ping", "rpc.stop"), result.capabilities)
    }

    @Test
    fun freshWireWithUnknownSchemaIsUnsupported() {
        // Given
        val response = freshResponse()
        val root = requireNotNull(response.result)
        // When
        val result = EngineClientCapabilitiesCodec.decode(response.copy(result = JsonObject(root + ("schema" to JsonPrimitive(2)))))
        // Then
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported), result)
    }

    @Test
    fun freshWireWithoutRequiredCapabilityIsUnsupported() {
        // Given
        val response = freshResponse()
        val root = requireNotNull(response.result)
        val ids = root.getValue("capabilities").jsonArray.filter { it != JsonPrimitive("config.metadata.v1") }
        // When
        val result = EngineClientCapabilitiesCodec.decode(response.copy(result = JsonObject(root + ("capabilities" to JsonArray(ids)))))
        // Then
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported), result)
    }

    @Test
    fun freshWireWithPrivateFieldIsRejectedWithoutEcho() {
        // Given
        val response = freshResponse()
        val root = requireNotNull(response.result)
        // When
        val result = EngineClientCapabilitiesCodec.decode(response.copy(result = JsonObject(root + ("imsi" to JsonPrimitive("private-canary")))))
        // Then
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Invalid), result)
    }

    @Test
    fun freshWireWithUnknownNestedKeyIsRejected() {
        // Given
        val response = freshResponse()
        val root = requireNotNull(response.result)
        val protocol = JsonObject(root.getValue("protocol").jsonObject + ("unknown" to JsonPrimitive("canary")))
        // When
        val result = EngineClientCapabilitiesCodec.decode(response.copy(result = JsonObject(root + ("protocol" to protocol))))
        // Then
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Invalid), result)
    }

    private fun freshResponse(): EngineProtocolFrame.Response {
        val path = System.getenv("TASK7_FRESH_WIRE")
        assumeTrue("SKIP: fresh Go RPC wire unavailable; TASK7_FRESH_WIRE is not set", !path.isNullOrBlank())
        return assertIs<EngineProtocolFrame.Response>(EngineProtocolCodec.decode(File(requireNotNull(path)).readText()))
    }
}
