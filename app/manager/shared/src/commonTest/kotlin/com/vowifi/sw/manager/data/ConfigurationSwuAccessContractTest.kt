package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationSwuAccessContractTest {
    @Test fun completeGroupRoundTripsAndUnknownPreservationIsNotAnImplicitClear() {
        val chosen = ConfigurationSwuAccessCodec.decode(Json.parseToJsonElement(GROUP))
        assertEquals(Json.parseToJsonElement(GROUP), ConfigurationSwuAccessCodec.encode(chosen))
        assertFalse(chosen.toString().contains("voice.example.test"))
        assertFalse(chosen.policy.toString().contains("voice.example.test"))
        val unknown = ConfigurationOverrides(ConfigurationTransport.WIFI, "", "", "", "", expectedRevision = REVISION)
        assertFailsWith<ConfigurationFormatException.UnknownSwuAccess> {
            ConfigurationCodec.encodeChanges(unknown, unknown.copy(swuAccess = chosen))
        }
        assertEquals(buildJsonObject { put("swuAccess", JsonNull) },
            Json.parseToJsonElement(ConfigurationCodec.encodeChanges(unknown, unknown.copy(swuAccess = ConfigurationSwuAccess()))))
        val known = unknown.copy(swuAccess = chosen)
        assertEquals(buildJsonObject {}, Json.parseToJsonElement(ConfigurationCodec.encodeChanges(known, known.copy(swuAccess = null))))
        val inherited = unknown.copy(swuAccess = ConfigurationSwuAccess())
        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(inherited, inherited.copy(swuAccess = chosen))).jsonObject
        assertEquals(JsonPrimitive(REVISION), patch["expectedRevision"])
        assertEquals(buildJsonObject { put("swuAccess", Json.parseToJsonElement(GROUP)) }, patch["overrides"])
    }

    @Test fun absentGroupKeepsExistingFactsCompatible() {
        assertEquals(REVISION, ConfigurationFactsCodec.local(facts(null))?.configRevision)
    }

    @Test fun actualFactsConsumerAcceptsOnlyExplicitCompatibleBaseline() {
        assertNotNull(ConfigurationFactsCodec.local(facts(Json.parseToJsonElement(
            """{"state":"known","source":"default","baseline":"swu-ims-access-compatible-v1"}"""))))
    }

    @Test fun actualFactsConsumerAcceptsTheCompleteUserGroup() {
        assertNotNull(ConfigurationFactsCodec.local(facts(user(Json.parseToJsonElement(GROUP)))))
    }

    @Test fun redactedUnknownAccessGroupDoesNotDestroyOtherFacts() {
        assertEquals(REVISION, ConfigurationFactsCodec.local(facts(Json.parseToJsonElement(
            """{"state":"unknown","source":"user"}""")))?.configRevision)
    }

    @Test fun loadedAccessPolicyIsNotAClaimOfNegotiationOrConnectivity() {
        val status = buildJsonObject {
            put("tunnel", "BACKOFF")
            put("configuration", buildJsonObject {
                put("schema", 1); put("loaded", facts(user(Json.parseToJsonElement(GROUP))))
                put("negotiated", buildJsonObject {})
            })
        }
        val runtime = assertNotNull(ConfigurationFactsCodec.runtime(status))
        assertNull(runtime.tunnel)
        assertEquals(REVISION, runtime.loaded.configRevision)
    }

    @Test fun unsupportedPartialOrCredentialGroupsRemainRejected() {
        for (group in listOf(
            GROUP.replace("\"ims\"", "\"ut\""),
            GROUP.replace("1340", "1279"), GROUP.replace("1340", "1501"),
            GROUP.replace("1340", "\"1340\""), GROUP.replace("1340", "1340.0"),
            GROUP.replace("voice.example.test", ""),
            GROUP.dropLast(1) + """, "password":"synthetic"}""",
            """{"purpose":"ims","requestedApn":"voice.example.test"}""",
        )) {
            assertFailsWith<ConfigurationFormatException> {
                ConfigurationFactsCodec.local(facts(user(Json.parseToJsonElement(group))))
            }
        }
        for (group in listOf(
            """{"state":"unknown","source":"user","value":$GROUP}""",
            """{"state":"known","source":"default","baseline":"invented"}""",
            """{"state":"known","source":"user","value":$GROUP,"override":null}""",
            """{"state":"known","source":"catalog","value":$GROUP}""",
        )) {
            assertFailsWith<ConfigurationFormatException> { ConfigurationFactsCodec.local(facts(Json.parseToJsonElement(group))) }
        }
    }

    private fun user(group: JsonElement) = buildJsonObject {
        put("state", "known"); put("source", "user"); put("value", group); put("override", group)
    }

    private fun facts(group: JsonElement?) = buildJsonObject {
        put("schema", 1); put("configRevision", REVISION); put("resolutionRevision", "rf1_" + "a".repeat(64))
        put("fields", buildJsonArray {})
        if (group != null) put("swuAccess", group)
    }

    private companion object {
        const val REVISION = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val GROUP = """{"purpose":"ims","requestedApn":"voice.example.test","interfaceMtu":1340}"""
    }
}
