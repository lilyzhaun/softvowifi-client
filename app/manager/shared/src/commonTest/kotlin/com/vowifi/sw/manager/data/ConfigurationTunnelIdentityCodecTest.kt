package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationTunnelIdentityCodecTest {
    @Test fun catalogTypesNeverBecomeAnEditablePrivateOverride() {
        val catalog = FACTS.replace("\"source\":\"user\",\"value\":$TYPES,\"override\":$TYPES", "\"source\":\"catalog\",\"value\":$TYPES")
        val facts = ConfigurationFactsCodec.local(Json.parseToJsonElement(catalog))!!
        assertTrue(facts.tunnelIdentity!!.known)
        assertEquals("catalog", facts.tunnelIdentity!!.source)
        assertEquals(ConfigurationTunnelIdentity(), ConfigurationTunnelIdentityCodec.read(null, facts))
        assertFailsWith<ConfigurationFormatException> { ConfigurationTunnelIdentityCodec.read(Json.parseToJsonElement(GROUP), facts) }
        val raw = """{"ok":true,"formatState":"current","configVersion":1,"configRevision":"$REVISION","displayLabel":"实例 •••• 0001","lifecycle":"stopped","transport":"wifi","smsc":"","epdgFqdn":"","imsRealm":"","dns":"","resolvedEpdgFqdn":"epdg.example.invalid","resolvedImsRealm":"ims.example.invalid","mcc":"001","mnc":"01","configuration":$catalog}"""
        assertEquals(ConfigurationTunnelIdentity(), ConfigurationCodec.show(raw).tunnelIdentity)
        assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.show(raw.dropLast(1) + """, "tunnelIdentityOverride":$GROUP}""") }
        val runtime = ConfigurationFactsCodec.runtime(Json.parseToJsonElement(
            """{"tunnel":"CONNECTED","configuration":{"schema":1,"loaded":$catalog,"negotiated":{}}}""").jsonObject)!!
        assertNull(runtime.identity)
        assertFalse(facts.toString().contains("peer.example.invalid"))
    }

    @Test fun catalogFactsRejectPrivateValuesAndOverridesWhileUnknownRemainsUnknown() {
        for (bad in listOf(
            """{"state":"known","source":"catalog","value":$TYPES,"override":$TYPES}""",
            """{"state":"known","source":"catalog","value":$GROUP}""",
            """{"state":"known","source":"catalog"}""",
        )) assertFailsWith<ConfigurationFormatException> { ConfigurationTunnelIdentityCodec.facts(Json.parseToJsonElement(bad)) }
        assertFalse(ConfigurationTunnelIdentityCodec.facts(Json.parseToJsonElement("""{"state":"unknown","source":"catalog"}"""))!!.known)
    }

    @Test fun localEditorRoundTripsButRuntimeFactsContainOnlyTypes() {
        val policy = ConfigurationTunnelIdentityCodec.decode(Json.parseToJsonElement(GROUP))
        assertEquals(Json.parseToJsonElement(GROUP), ConfigurationTunnelIdentityCodec.encode(policy))
        val facts = ConfigurationFactsCodec.local(Json.parseToJsonElement(FACTS))!!
        assertEquals(policy, ConfigurationTunnelIdentityCodec.read(Json.parseToJsonElement(GROUP), facts))
        assertNull(ConfigurationTunnelIdentityCodec.read(null, facts))
        val runtime = ConfigurationFactsCodec.runtime(Json.parseToJsonElement(
            """{"tunnel":"CONNECTED","configuration":{"schema":1,"loaded":$FACTS,"negotiated":{},"identity":{"localType":11,"requestType":2,"peerType":2,"peerConstrained":true}}}""").jsonObject)!!
        assertEquals(ConfigurationAuthenticatedIdentity(ConfigurationIkeIdType.KEY_ID, ConfigurationIkeIdType.FQDN, 2, true), runtime.identity)
        assertFalse(facts.toString().contains("peer.example.invalid"))
        assertFalse(policy.toString().contains("peer.example.invalid"))
        assertFailsWith<ConfigurationFormatException> { ConfigurationTunnelIdentityCodec.connection(Json.parseToJsonElement(
            """{"localType":3,"requestType":2,"peerType":2,"peerConstrained":true}"""), facts.tunnelIdentity) }
    }

    @Test fun unknownClearAndUnrelatedChangesKeepTheirSeparateMeanings() {
        val policy = ConfigurationTunnelIdentityCodec.decode(Json.parseToJsonElement(GROUP))
        val unknown = ConfigurationOverrides(ConfigurationTransport.WIFI, "", "", "", "", expectedRevision = REVISION)
        assertFailsWith<ConfigurationFormatException.UnknownTunnelIdentity> { ConfigurationCodec.encodeChanges(unknown, unknown.copy(tunnelIdentity = policy)) }
        assertEquals("""{"tunnelIdentity":null}""", ConfigurationCodec.encodeChanges(unknown, unknown.copy(tunnelIdentity = ConfigurationTunnelIdentity())))
        val known = unknown.copy(tunnelIdentity = policy)
        assertEquals(setOf("callSignaling"), Json.parseToJsonElement(ConfigurationCodec.encodeChanges(known, known.copy(callSignaling = ConfigurationCallSignaling()))).jsonObject.keys)
        assertEquals("{}", ConfigurationCodec.encodeChanges(known, known.copy(tunnelIdentity = null)))
    }

    @Test fun rejectsPartialPolicyUnknownTypesMalformedPeerAndEncodedSubscriber() {
        for (raw in listOf("null", "{}", GROUP.replace("\"key-id\"", "\"fqdn\""), GROUP.replace("\"requestType\":\"fqdn\"", "\"requestType\":\"rfc822\""),
            GROUP.replace("peer.example.invalid", "*.example.invalid"), GROUP.replace("peer.example.invalid", "001010000000001.example.invalid"),
            """{"localType":"rfc822","requestType":"fqdn","peer":{"type":"key-id","value":"MDAxMDEwMDAwMDAwMDAx"}}""",
            """{"localType":"rfc822","requestType":"fqdn","peer":{"type":"ipv6","value":"::ffff:192.0.2.1::"}}""")) {
            assertFailsWith<ConfigurationFormatException> { ConfigurationTunnelIdentityCodec.decode(Json.parseToJsonElement(raw)) }
        }
        for ((type, value) in listOf("ipv4" to "192.0.2.1", "ipv6" to "2001:db8::1", "key-id" to "cGVlcg==", "rfc822" to "peer@example.invalid")) {
            val raw = """{"localType":"rfc822","requestType":"fqdn","peer":{"type":"$type","value":"$value"}}"""
            assertEquals(Json.parseToJsonElement(raw), ConfigurationTunnelIdentityCodec.encode(ConfigurationTunnelIdentityCodec.decode(Json.parseToJsonElement(raw))))
        }
    }

    @Test fun showReadsOnlyARevisionBoundSafeEditorProjection() {
        val raw = """{"ok":true,"formatState":"current","configVersion":1,"configRevision":"$REVISION","displayLabel":"实例 •••• 0001","lifecycle":"stopped","transport":"wifi","smsc":"","epdgFqdn":"","imsRealm":"","dns":"","resolvedEpdgFqdn":"epdg.example.invalid","resolvedImsRealm":"ims.example.invalid","mcc":"001","mnc":"01","configuration":$FACTS,"tunnelIdentityOverride":$GROUP}"""
        assertEquals(ConfigurationTunnelIdentityCodec.decode(Json.parseToJsonElement(GROUP)), ConfigurationCodec.show(raw).tunnelIdentity)
        assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.show(raw.replace("\"tunnelIdentityOverride\":$GROUP", "\"tunnelIdentityOverride\":" + GROUP.replace("key-id", "rfc822"))) }
    }

    @Test fun actualUnconstrainedPeerTypesKeepTheirIanaNumberAndOtherRuntimeFacts() {
        val loaded = FACTS.replace("\"peerType\":\"fqdn\"", "\"peerType\":\"\"")
        for (peerType in listOf(9, 10, 254)) {
            val result = ConfigurationFactsCodec.runtime(Json.parseToJsonElement(
                """{"tunnel":"CONNECTED","configuration":{"schema":1,"loaded":$loaded,"negotiated":{"registrationSeconds":1800},"identity":{"localType":11,"requestType":2,"peerType":$peerType,"peerConstrained":false}}}""").jsonObject)!!
            assertEquals(1800L, result.registrationSeconds)
            assertEquals(REVISION, result.loaded.configRevision)
            assertNotNull(result.identity)
            assertEquals<Any?>(peerType, result.identity.peerType)
        }
        for (peerType in listOf(0, 256)) {
            assertFailsWith<ConfigurationFormatException> { ConfigurationTunnelIdentityCodec.connection(Json.parseToJsonElement(
                """{"localType":11,"requestType":2,"peerType":$peerType,"peerConstrained":false}"""), ConfigurationFactsCodec.local(Json.parseToJsonElement(loaded))!!.tunnelIdentity) }
        }
        assertFailsWith<ConfigurationFormatException> { ConfigurationTunnelIdentityCodec.connection(Json.parseToJsonElement(
            """{"localType":11,"requestType":2,"peerType":9,"peerConstrained":true}"""), ConfigurationFactsCodec.local(Json.parseToJsonElement(FACTS))!!.tunnelIdentity) }
    }

    companion object {
        const val REVISION = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val GROUP = """{"localType":"key-id","requestType":"fqdn","peer":{"type":"fqdn","value":"peer.example.invalid"}}"""
        const val TYPES = """{"localType":"key-id","requestType":"fqdn","peerType":"fqdn"}"""
        const val FACTS = """{"schema":1,"configRevision":"$REVISION","resolutionRevision":"rf1_abcdefghijklmnopabcdefghijklmnopabcdefghijklmnopabcdefghijklmnop","fields":[],"tunnelIdentity":{"state":"known","source":"user","value":$TYPES,"override":$TYPES}}"""
    }
}
