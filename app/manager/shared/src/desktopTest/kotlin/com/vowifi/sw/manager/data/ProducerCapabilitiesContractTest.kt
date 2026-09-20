package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ClientCapabilitiesResult
import com.vowifi.sw.manager.model.EngineProtocolFrame
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProducerCapabilitiesContractTest {
    @Test
    fun freshGoProducerWireIsConsumedByKotlin() {
        val wire = System.getenv("TASK7_FRESH_WIRE")?.let { File(it).readText() }
            ?: PublicClientCapabilitiesTest.CANONICAL
        val response = assertIs<EngineProtocolFrame.Response>(EngineProtocolCodec.decode(wire))
        val result = assertIs<ClientCapabilitiesResult.Ready>(EngineClientCapabilitiesCodec.decode(response))
        assertEquals(listOf("tcp_ipsec_3gpp", "absent", "", ""), result.capabilities.configMetadata.map { it.value })
        assertEquals(listOf("config.metadata.v1", "rpc.getConfig", "rpc.getStatus", "rpc.ping", "rpc.stop"), result.capabilities.capabilities)
    }
}
