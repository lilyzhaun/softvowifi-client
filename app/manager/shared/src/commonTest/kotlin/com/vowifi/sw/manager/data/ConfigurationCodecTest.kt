package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ConfigurationCodecTest {
    @Test
    fun decodesValidRedactedShowResponse() {
        val snapshot = ConfigurationCodec.show(
            redactedShowJson(extra = buildJsonObject { put("future", true) }),
        )

        assertEquals(FAKE_DISPLAY_LABEL, snapshot.displayLabel)
        assertEquals(ConfigurationLifecycle.STOPPED, snapshot.lifecycle)
        assertEquals(ConfigurationTransport.WIFI, snapshot.transport)
        assertEquals(FAKE_SMSC, snapshot.smsc)
        assertEquals("", snapshot.epdgFqdn)
        assertEquals("", snapshot.imsRealm)
        assertEquals(FAKE_DNS, snapshot.dns)
        assertEquals(FAKE_RESOLVED_EPDG, snapshot.resolvedEpdgFqdn)
        assertEquals(FAKE_RESOLVED_REALM, snapshot.resolvedImsRealm)
        assertEquals(FAKE_MCC, snapshot.mcc)
        assertEquals(FAKE_MNC, snapshot.mnc)
        assertPrivate(snapshot.displayLabel)
        assertPrivate(snapshot.smsc)
        assertPrivate(snapshot.resolvedEpdgFqdn)
    }

    @Test
    fun normalizesBackendLastFourDisplayLabel() {
        val snapshot = ConfigurationCodec.show(redactedShowJson(displayLabel = "1234"))

        assertEquals(FAKE_DISPLAY_LABEL, snapshot.displayLabel)
        assertPrivate(snapshot.displayLabel)
    }

    @Test
    fun rejectsForbiddenImsiOrIccidKey() {
        assertFailsWith<ConfigurationFormatException.ForbiddenField> {
            ConfigurationCodec.show(redactedShowJson(extra = buildJsonObject { put("imsi", "x") }))
        }
        assertFailsWith<ConfigurationFormatException.ForbiddenField> {
            ConfigurationCodec.show(redactedShowJson(extra = buildJsonObject { put("iccid", "x") }))
        }
    }

    @Test
    fun rejectsRemainingForbiddenIdentityKeys() {
        listOf("imei", "line1", "slot", "enabled").forEach { key ->
            assertFailsWith<ConfigurationFormatException.ForbiddenField>(key) {
                ConfigurationCodec.show(redactedShowJson(extra = buildJsonObject { put(key, "x") }))
            }
        }
    }

    @Test
    fun rejectsLongDigitRunInShowPayload() {
        assertFailsWith<ConfigurationFormatException.IdentityLeak> {
            ConfigurationCodec.show(redactedShowJson(dns = FAKE_DIGIT_RUN))
        }
    }

    @Test
    fun acceptsReservedFifteenDigitSmscOnShowAndEncode() {
        val snapshot = ConfigurationCodec.show(redactedShowJson(smsc = FAKE_E164_SMSC))
        val encoded = ConfigurationCodec.encode(
            ConfigurationOverrides(
                transport = ConfigurationTransport.WIFI,
                smsc = FAKE_E164_SMSC,
                epdgFqdn = "",
                imsRealm = "",
                dns = "",
            ),
        )

        assertEquals(FAKE_E164_SMSC, snapshot.smsc)
        val body = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(FAKE_E164_SMSC, body.getValue("smsc").jsonPrimitive.content)
        assertEquals("wifi", body.getValue("transport").jsonPrimitive.content)
    }

    @Test
    fun rejectsDigitRunsOutsideSmscIncludingUnknownFields() {
        assertFailsWith<ConfigurationFormatException.IdentityLeak> {
            ConfigurationCodec.show(redactedShowJson(epdgFqdn = FAKE_DIGIT_RUN))
        }
        assertFailsWith<ConfigurationFormatException.IdentityLeak> {
            ConfigurationCodec.show(redactedShowJson(imsRealm = FAKE_DIGIT_RUN))
        }
        assertFailsWith<ConfigurationFormatException.IdentityLeak> {
            ConfigurationCodec.show(redactedShowJson(displayLabel = FAKE_DIGIT_RUN))
        }
        assertFailsWith<ConfigurationFormatException.IdentityLeak> {
            ConfigurationCodec.show(
                redactedShowJson(extra = buildJsonObject { put("hint", FAKE_DIGIT_RUN) }),
            )
        }
        assertFailsWith<ConfigurationFormatException.IdentityLeak> {
            ConfigurationCodec.encode(emptyOverrides(dns = FAKE_DIGIT_RUN))
        }
        assertFailsWith<ConfigurationFormatException.IdentityLeak> {
            ConfigurationCodec.encode(emptyOverrides(epdgFqdn = FAKE_DIGIT_RUN))
        }
        assertFailsWith<ConfigurationFormatException.IdentityLeak> {
            ConfigurationCodec.encode(emptyOverrides(imsRealm = FAKE_DIGIT_RUN))
        }
    }

    @Test
    fun rejectsUnsuccessfulOrMalformedShow() {
        assertFailsWith<ConfigurationFormatException.Unsuccessful> {
            ConfigurationCodec.show(redactedShowJson(ok = false))
        }
        assertFailsWith<ConfigurationFormatException.Unparseable> {
            ConfigurationCodec.show("""{"ok":true,"displayLabel":""")
        }
        assertFailsWith<ConfigurationFormatException.Unparseable> {
            ConfigurationCodec.show(redactedShowJson(includeOk = false))
        }
    }

    @Test
    fun rejectsInvalidTransportSmscLifecycleAndDisplayLabel() {
        assertFailsWith<ConfigurationFormatException.InvalidTransport> {
            ConfigurationCodec.show(redactedShowJson(transport = "udp"))
        }
        assertFailsWith<ConfigurationFormatException.InvalidSmsc> {
            ConfigurationCodec.show(redactedShowJson(smsc = "155501"))
        }
        assertFailsWith<ConfigurationFormatException.InvalidLifecycle> {
            ConfigurationCodec.show(redactedShowJson(lifecycle = "future"))
        }
        assertFailsWith<ConfigurationFormatException.InvalidLabel> {
            ConfigurationCodec.show(redactedShowJson(displayLabel = "card-1"))
        }
    }

    @Test
    fun decodesEveryTransportAndLifecycle() {
        val transports = mapOf(
            "wifi" to ConfigurationTransport.WIFI,
            "sim1" to ConfigurationTransport.SIM1,
            "sim2" to ConfigurationTransport.SIM2,
            "auto" to ConfigurationTransport.AUTO,
        )
        transports.forEach { (wire, expected) ->
            assertEquals(expected, ConfigurationCodec.show(redactedShowJson(transport = wire)).transport)
        }

        val lifecycles = mapOf(
            "config_missing" to ConfigurationLifecycle.CONFIG_MISSING,
            "stopped" to ConfigurationLifecycle.STOPPED,
            "starting" to ConfigurationLifecycle.STARTING,
            "running" to ConfigurationLifecycle.RUNNING,
            "stopping" to ConfigurationLifecycle.STOPPING,
        )
        lifecycles.forEach { (wire, expected) ->
            assertEquals(expected, ConfigurationCodec.show(redactedShowJson(lifecycle = wire)).lifecycle)
        }
        assertEquals(
            ConfigurationTransport.entries.toSet(),
            transports.values.toSet(),
        )
        assertEquals(
            ConfigurationLifecycle.entries.toSet(),
            lifecycles.values.toSet(),
        )
    }

    @Test
    fun acceptsOptionalIdentityAndEpdgMetadataWithoutChangingSnapshot() {
        val snapshot = ConfigurationCodec.show(
            redactedShowJson(
                extra = buildJsonObject {
                    put("identitySource", "formula")
                    put("identityReadStatus", "denied")
                    put("epdgResolutionKind", "catalog")
                },
            ),
        )

        assertEquals("", snapshot.epdgFqdn)
        assertEquals(FAKE_RESOLVED_EPDG, snapshot.resolvedEpdgFqdn)
        assertEquals(FAKE_MCC, snapshot.mcc)
        assertEquals(ConfigurationLifecycle.STOPPED, snapshot.lifecycle)
        assertPrivate(snapshot.resolvedEpdgFqdn)
    }

    @Test
    fun existingConfiguredShowWithoutMetadataStillDecodes() {
        val snapshot = ConfigurationCodec.show(
            redactedShowJson(epdgFqdn = FAKE_EPDG, resolvedEpdgFqdn = FAKE_EPDG),
        )

        assertEquals(FAKE_EPDG, snapshot.epdgFqdn)
        assertEquals(FAKE_EPDG, snapshot.resolvedEpdgFqdn)
        assertEquals(FAKE_MCC, snapshot.mcc)
        assertEquals(ConfigurationTransport.WIFI, snapshot.transport)
    }

    @Test
    fun encodesExactlyTheFiveAllowlistedOverrideKeys() {
        val encoded = ConfigurationCodec.encode(
            ConfigurationOverrides(
                transport = ConfigurationTransport.SIM1,
                smsc = FAKE_SMSC,
                epdgFqdn = FAKE_EPDG,
                imsRealm = FAKE_REALM,
                dns = FAKE_DNS,
            ),
        )

        val body = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(
            setOf("transport", "smsc", "epdgFqdn", "imsRealm", "dnsOverrides"),
            body.keys,
        )
        assertEquals("sim1", body.getValue("transport").jsonPrimitive.content)
        assertEquals(FAKE_SMSC, body.getValue("smsc").jsonPrimitive.content)
        assertEquals(FAKE_EPDG, body.getValue("epdgFqdn").jsonPrimitive.content)
        assertEquals(FAKE_REALM, body.getValue("imsRealm").jsonPrimitive.content)
        assertEquals(mapOf("sim1" to JsonPrimitive(FAKE_DNS)), body.getValue("dnsOverrides").jsonObject.toMap())
    }

    @Test
    fun encodesExplicitEmptyOptionalOverrideValues() {
        val encoded = ConfigurationCodec.encode(
            ConfigurationOverrides(
                transport = ConfigurationTransport.WIFI,
                smsc = "",
                epdgFqdn = "",
                imsRealm = "",
                dns = "",
            ),
        )

        val body = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(
            setOf("transport", "smsc", "epdgFqdn", "imsRealm", "dnsOverrides"),
            body.keys,
        )
        assertEquals("wifi", body.getValue("transport").jsonPrimitive.content)
        listOf("smsc", "epdgFqdn", "imsRealm").forEach { key ->
            assertEquals("", body.getValue(key).jsonPrimitive.content)
        }
        assertTrue(body.getValue("dnsOverrides").jsonObject.isEmpty())
    }

    @Test
    fun encodesEveryTransportAsLowercaseWireValue() {
        val expected = mapOf(
            ConfigurationTransport.WIFI to "wifi",
            ConfigurationTransport.SIM1 to "sim1",
            ConfigurationTransport.SIM2 to "sim2",
            ConfigurationTransport.AUTO to "auto",
        )
        assertEquals(ConfigurationTransport.entries.toSet(), expected.keys)
        expected.forEach { (transport, wire) ->
            val body = Json.parseToJsonElement(
                ConfigurationCodec.encode(emptyOverrides(transport = transport)),
            ).jsonObject
            assertEquals(wire, body.getValue("transport").jsonPrimitive.content)
            assertEquals(
                setOf("transport", "smsc", "epdgFqdn", "imsRealm", "dnsOverrides"),
                body.keys,
            )
        }
    }

    @Test
    fun rejectsUnsafeOverrideValuesBeforeEncoding() {
        assertFailsWith<ConfigurationFormatException.InvalidSmsc> {
            ConfigurationCodec.encode(emptyOverrides(smsc = "155501"))
        }
        assertFailsWith<ConfigurationFormatException.IdentityLeak> {
            ConfigurationCodec.encode(emptyOverrides(dns = FAKE_DIGIT_RUN))
        }
    }
}

private fun emptyOverrides(
    transport: ConfigurationTransport = ConfigurationTransport.WIFI,
    smsc: String = "",
    epdgFqdn: String = "",
    imsRealm: String = "",
    dns: String = "",
) = ConfigurationOverrides(transport, smsc, epdgFqdn, imsRealm, dns)
