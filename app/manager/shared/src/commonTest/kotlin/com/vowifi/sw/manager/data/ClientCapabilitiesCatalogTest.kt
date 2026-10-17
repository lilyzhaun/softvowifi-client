package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Schema 1 includes inherited catalog SIP policy; its provenance must survive decoding. */
class ClientCapabilitiesCatalogTest {
    @Test fun inheritedCatalogSipPoliciesKeepTheirExactSource() {
        for (policy in listOf("tcp_ipsec_3gpp", "swu_udp_aka")) {
            val result = assertIs<ClientCapabilitiesResult.Ready>(EngineClientCapabilitiesCodec.decode(frame(policy)))
            val item = result.capabilities.configMetadata.single { it.field == MetadataField.SipSecurity }
            assertEquals(policy, item.value)
            assertEquals(MetadataSource.Catalog, item.source)
            assertEquals(MetadataDecision.Inherited, item.decision)
        }
    }

    @Test fun theProductionQueryAcceptsCatalogWithoutRewritingTheReply() = runTest {
        var requests = 0
        val source = EngineClientCapabilitiesSource { method, params ->
            requests++
            assertEquals("getClientCapabilities", method)
            assertEquals(EngineClientCapabilitiesCodec.params(), params)
            frame()
        }
        val result = assertIs<ClientCapabilitiesResult.Ready>(source.query())
        assertEquals(MetadataSource.Catalog,
            result.capabilities.configMetadata.single { it.field == MetadataField.SipSecurity }.source)
        assertEquals(1, requests)
    }

    @Test fun catalogDoesNotRelaxPolicyValueSourceOrDecisionValidation() {
        val invalid = ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Invalid)
        assertEquals(invalid, EngineClientCapabilitiesCodec.decode(frame(value = "unapproved")))
        assertEquals(invalid, EngineClientCapabilitiesCodec.decode(frame(source = "identity")))
        assertEquals(invalid, EngineClientCapabilitiesCodec.decode(frame(source = "home_mcc")))
        assertEquals(invalid, EngineClientCapabilitiesCodec.decode(frame(source = "unknown")))
        assertEquals(invalid, EngineClientCapabilitiesCodec.decode(frame(decision = "override")))
    }

    private fun frame(value: String = "tcp_ipsec_3gpp", source: String = "catalog", decision: String = "inherited"): EngineProtocolFrame.Response {
        val original = assertIs<EngineProtocolFrame.Response>(EngineProtocolCodec.decode(PublicClientCapabilitiesTest.CANONICAL))
        val root = requireNotNull(original.result)
        val metadata = root.getValue("configMetadata").jsonArray.map { element ->
            val item = element.jsonObject
            if (item.getValue("field").jsonPrimitive.content != "imsSipSecurity") item else JsonObject(item + mapOf(
                "value" to JsonPrimitive(value), "source" to JsonPrimitive(source), "decision" to JsonPrimitive(decision),
            ))
        }
        return original.copy(result = JsonObject(root + ("configMetadata" to JsonArray(metadata))))
    }
}
