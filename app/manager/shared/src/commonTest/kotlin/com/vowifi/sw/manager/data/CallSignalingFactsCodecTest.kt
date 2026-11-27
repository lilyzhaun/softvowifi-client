package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFormatException
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CallSignalingFactsCodecTest {
    @Test fun completeSignalingFactsRemainSeparateFromNegotiatedNetworkValues() {
        val local = assertNotNull(ConfigurationFactsCodec.local(facts()))
        assertEquals(17, local.fields.size)
        values.forEach { (leaf, value) ->
            val fact = local.fields.single { it.field == "callSignaling.$leaf" }
            assertEquals(value, fact.value)
            assertEquals(value, fact.overrideValue)
            assertEquals("user", fact.source)
        }
        val status = buildJsonObject {
            put("configuration", buildJsonObject {
                put("schema", 1); put("loaded", facts()); put("negotiated", buildJsonObject {})
            })
        }
        val runtime = assertNotNull(ConfigurationFactsCodec.runtime(status))
        assertEquals(local, runtime.loaded)
        assertNull(runtime.registrationSeconds)
        assertNull(runtime.integrity)
    }

    @Test fun malformedSignalingScalarsCannotBeDisplayedAsKnownConfiguration() {
        for ((leaf, badValue) in listOf(
            "sessionTimer.enabled" to "yes",
            "sessionTimer.enabled" to "",
            "sessionTimer.expiresSeconds" to "89",
            "sessionTimer.minimumSeconds" to "2147483648",
            "sessionTimer.refresher" to "local",
            "sessionTimer.refreshMethod" to "options",
            "sessionTimer.refreshMethod" to "update",
            "reliableProvisional.uasMode" to "183",
            "addressing.requestURI" to "sips",
            "addressing.contactTransport" to "tcp",
            "addressing.phoneContext" to "context.example;injected=1",
            "includePANI" to "null",
        )) {
            assertFailsWith<ConfigurationFormatException>("$leaf=$badValue") {
                ConfigurationFactsCodec.local(facts(values + (leaf to badValue)))
            }
        }
        val mismatched = facts().toMutableMap()
        mismatched["fields"] = buildJsonArray {
            add(buildJsonObject {
                put("field", "callSignaling.sessionTimer.refreshMethod"); put("state", "known")
                put("source", "user"); put("value", "invite"); put("override", "update")
            })
        }
        assertFailsWith<ConfigurationFormatException>("an invalid explicit override must not hide behind a valid resolved value") {
            ConfigurationFactsCodec.local(JsonObject(mismatched))
        }
    }

    @Test fun allExistingAndSignalingFieldsFitTheSameBoundedFactsProjection() {
        val input = facts().toMutableMap()
        val existing = listOf("transport", "epdgFqdn", "imsRealm", "ikeProposalLayout", "ikeDHGroup", "protectedTCPMSS",
            "imsSipSecurity", "paniInitial", "paniAuthenticated", "paniCountry", "dtmfNarrowband", "dtmfWideband", "dns", "smsc", "imsDigestRealm")
        input["fields"] = buildJsonArray {
            (input.getValue("fields") as JsonArray).forEach { add(it) }
            existing.forEach { field -> add(buildJsonObject {
                put("field", field); put("state", "unknown"); put("source", "network"); put("value", "")
            }) }
        }
        assertEquals(32, assertNotNull(ConfigurationFactsCodec.local(JsonObject(input))).fields.size)
    }

    @Test fun legacyUpdateAndUnknownPrivateContextAreNotInventedAsOverrides() {
        val input = buildJsonObject {
            put("schema", 1); put("configRevision", revision); put("resolutionRevision", resolution)
            put("fields", buildJsonArray {
                add(buildJsonObject {
                    put("field", "callSignaling.sessionTimer.refreshMethod"); put("state", "known")
                    put("source", "default"); put("value", "update")
                })
                add(buildJsonObject {
                    put("field", "callSignaling.addressing.phoneContext"); put("state", "unknown")
                    put("source", "user"); put("value", "")
                })
            })
        }
        val parsed = assertNotNull(ConfigurationFactsCodec.local(input))
        assertEquals("update", parsed.fields.first().value)
        assertNull(parsed.fields.first().overrideValue)
        assertNull(parsed.fields.last().value)
    }

    private fun facts(fields: Map<String, String> = values) = buildJsonObject {
        put("schema", 1); put("configRevision", revision); put("resolutionRevision", resolution)
        put("fields", buildJsonArray {
            fields.forEach { (leaf, value) -> add(buildJsonObject {
                put("field", "callSignaling.$leaf"); put("state", "known"); put("source", "user")
                put("value", value); put("override", value)
            }) }
        })
    }

    private val revision = "r1_abcdefghijklmnopabcdefghijklmnop"
    private val resolution = "rf1_" + "a".repeat(64)
    private val values = linkedMapOf(
        "sessionTimer.enabled" to "true", "sessionTimer.expiresSeconds" to "1200",
        "sessionTimer.minimumSeconds" to "120", "sessionTimer.refresher" to "uas",
        "sessionTimer.refreshMethod" to "invite", "sessionTimer.requirePeer" to "true",
        "reliableProvisional.uacEnabled" to "true", "reliableProvisional.requirePeer" to "true",
        "reliableProvisional.uasMode" to "all-18x", "preconditions.uacEnabled" to "true",
        "preconditions.uasEnabled" to "true", "preconditions.requirePeer" to "true",
        "preconditions.wifiAllowed" to "false", "addressing.requestURI" to "sip",
        "addressing.phoneContext" to "context.example", "addressing.contactTransport" to "selected",
        "includePANI" to "false",
    )
}
