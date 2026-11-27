package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ClientCapabilitiesFailure
import com.vowifi.sw.manager.model.ClientCapabilitiesResult
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoundCallCapabilitiesContractTest {
    @Test fun sdesDoesNotBorrowPlainMediaOrPcmuCapability() {
        val required = setOf("rpc.setConfig", "config.call-media.next-call.v1", "config.call-media.sdes.v1")
        val raw = javaClass.getResourceAsStream("/r7-bound-media-capabilities.json")!!.use { it.readBytes().decodeToString() }
        val current = EngineProtocolCodec.decode(raw) as EngineProtocolFrame.Response
        assertIs<ClientCapabilitiesResult.Ready>(EngineClientCapabilitiesCodec.decode(current, required = required))
        val root = assertNotNull(current.result)
        val older = JsonArray(root.getValue("capabilities").jsonArray.filter { it.jsonPrimitive.content != "config.call-media.sdes.v1" })
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported),
            EngineClientCapabilitiesCodec.decode(current.copy(result = JsonObject(root + ("capabilities" to older))), required = required))
    }

    @Test fun normalizedBoundGoWireIsAcceptedForTheSpecificNextCallAPI() {
        // Go checks its actual Unix RPC response against the same resource,
        // normalizing only its per-server random instance key. This is not an
        // Android socket exchange or a fresh random wire capture in this test.
        val result = assertIs<ClientCapabilitiesResult.Ready>(
            EngineClientCapabilitiesCodec.decode(response(), required = required),
        ).capabilities
        assertEquals(7, result.capabilities.size)
        assertEquals(result.capabilities.sorted(), result.capabilities)
        assertTrue(result.capabilities.containsAll(required))
    }

    @Test fun unsortedBoundWireIsStillRejectedRatherThanSilentlyReordered() {
        val original = response()
        val root = assertNotNull(original.result)
        val unordered = root.getValue("capabilities").jsonArray.reversed()
        val actual = EngineClientCapabilitiesCodec.decode(original.copy(result = JsonObject(
            root + ("capabilities" to JsonArray(unordered)),
        )))
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Invalid), actual)
    }

    @Test fun anUnboundLegacyEngineDoesNotAcquireWriteSupportFromAMethodName() {
        val original = response()
        val root = assertNotNull(original.result)
        val legacy = root.getValue("capabilities").jsonArray.filter { it.jsonPrimitive.content !in required }
        val actual = EngineClientCapabilitiesCodec.decode(original.copy(result = JsonObject(
            root + ("capabilities" to JsonArray(legacy)),
        )), required = required)
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported), actual)
    }

    @Test fun oldSignalingCapabilityNeverAuthorizesMediaAndTheNewBoundGoWireDoes() {
        val mediaRequired = setOf("rpc.setConfig", "config.call-media.next-call.v1")
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported),
            EngineClientCapabilitiesCodec.decode(response(), required = mediaRequired))
        val raw = javaClass.getResourceAsStream("/r7-bound-media-capabilities.json")!!.use { it.readBytes().decodeToString() }
        val result = assertIs<ClientCapabilitiesResult.Ready>(EngineClientCapabilitiesCodec.decode(
            EngineProtocolCodec.decode(raw) as EngineProtocolFrame.Response, required = mediaRequired + required)).capabilities
        assertEquals(13, result.capabilities.size)
        assertTrue("status.tunnel-sa-proof.v1" in result.capabilities)
        assertEquals(result.capabilities.sorted(), result.capabilities)
    }

    @Test fun oldMediaCapabilityDoesNotAuthorizeThePacketizationObject() {
        val required = setOf("rpc.setConfig", "config.call-media.next-call.v1", "config.call-media.packetization.v1")
        val raw = javaClass.getResourceAsStream("/r7-bound-media-capabilities.json")!!.use { it.readBytes().decodeToString() }
        val current = EngineProtocolCodec.decode(raw) as EngineProtocolFrame.Response
        assertIs<ClientCapabilitiesResult.Ready>(EngineClientCapabilitiesCodec.decode(current, required = required))
        val root = assertNotNull(current.result)
        val older = JsonArray(root.getValue("capabilities").jsonArray.filter { it.jsonPrimitive.content != "config.call-media.packetization.v1" })
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported),
            EngineClientCapabilitiesCodec.decode(current.copy(result = JsonObject(root + ("capabilities" to older))), required = required))
    }

    @Test fun mediaAndPacketizationCapabilitiesNeverAuthorizeInactivityWithoutItsOwnBoundCapability() {
        val required = setOf("rpc.setConfig", "config.call-media.next-call.v1", "config.call-media.inactivity.v1")
        val raw = javaClass.getResourceAsStream("/r7-bound-media-capabilities.json")!!.use { it.readBytes().decodeToString() }
        val current = EngineProtocolCodec.decode(raw) as EngineProtocolFrame.Response
        assertIs<ClientCapabilitiesResult.Ready>(EngineClientCapabilitiesCodec.decode(current, required = required))
        val root = assertNotNull(current.result)
        val older = JsonArray(root.getValue("capabilities").jsonArray.filter { it.jsonPrimitive.content != "config.call-media.inactivity.v1" })
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported),
            EngineClientCapabilitiesCodec.decode(current.copy(result = JsonObject(root + ("capabilities" to older))), required = required))
    }

    @Test fun pcmuRequiresItsOwnBoundCapabilityAndDoesNotBorrowAMRSupport() {
        val required = setOf("rpc.setConfig", "config.call-media.next-call.v1", "config.call-media.pcmu.v1")
        val raw = javaClass.getResourceAsStream("/r7-bound-media-capabilities.json")!!.use { it.readBytes().decodeToString() }
        val current = EngineProtocolCodec.decode(raw) as EngineProtocolFrame.Response
        assertIs<ClientCapabilitiesResult.Ready>(EngineClientCapabilitiesCodec.decode(current, required = required))
        val root = assertNotNull(current.result)
        val older = JsonArray(root.getValue("capabilities").jsonArray.filter { it.jsonPrimitive.content != "config.call-media.pcmu.v1" })
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported),
            EngineClientCapabilitiesCodec.decode(current.copy(result = JsonObject(root + ("capabilities" to older))), required = required))
    }

    private fun response(): EngineProtocolFrame.Response {
        val resource = assertNotNull(javaClass.classLoader.getResourceAsStream("r6-bound-capabilities.json"))
        val raw = resource.bufferedReader().use { it.readText() }
        return assertIs<EngineProtocolFrame.Response>(EngineProtocolCodec.decode(raw))
    }

    private val required = setOf("rpc.setConfig", "config.call-signaling.next-call.v1")
}
