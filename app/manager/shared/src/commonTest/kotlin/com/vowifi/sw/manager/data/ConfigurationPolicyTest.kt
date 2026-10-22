package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import com.vowifi.sw.manager.ui.ConfigurationEditorDraft
import com.vowifi.sw.manager.ui.ConfigurationEditStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ConfigurationPolicyTest {
    private val baseline = ConfigurationOverrides(ConfigurationTransport.WIFI, "", "", "", "")
    private val show = """{"ok":true,"displayLabel":"1234","lifecycle":"stopped","transport":"wifi","smsc":"","epdgFqdn":"","imsRealm":"","dns":"","resolvedEpdgFqdn":"example.invalid","resolvedImsRealm":"example.invalid","mcc":"310","mnc":"260"} """.trim()

    @Test fun optionalPoliciesSetAndClearWithoutTouchingOtherFields() {
        val next = baseline.copy(imsSipSecurity = "swu_udp_aka", paniInitial = "wlan-country", paniCountry = " hk ")
        val delta = Json.parseToJsonElement(ConfigurationCodec.encodeChanges(baseline, next)) as JsonObject
        assertEquals(setOf("imsSipSecurity", "paniInitial", "paniCountry"), delta.keys)
        assertEquals("\"HK\"", delta["paniCountry"].toString())
        assertEquals("{}", ConfigurationCodec.encodeChanges(next, next))
        val cleared = Json.parseToJsonElement(ConfigurationCodec.encodeChanges(next, baseline)) as JsonObject
        assertEquals(setOf("imsSipSecurity", "paniInitial", "paniCountry"), cleared.keys)
        assertEquals(setOf("\"\""), cleared.values.map { it.toString() }.toSet())
        assertFalse(ConfigurationCodec.encode(baseline).contains("imsSipSecurity"))
    }

    @Test fun invalidPoliciesNeverEncode() {
        for (value in listOf("auto", "udp", "TCP", " swu_udp_aka")) {
            assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.encode(baseline.copy(imsSipSecurity = value)) }
        }
        assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.encode(baseline.copy(paniInitial = "wlan_country")) }
        for (value in listOf("HKG", "1A", "éA", "ß")) {
            assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.encode(baseline.copy(paniCountry = value)) }
        }
    }

    @Test fun everySupportedPolicyRoundTripsAndBlankClears() {
        for (security in listOf("", "tcp_ipsec_3gpp", "swu_udp_aka")) {
            for (initial in listOf("", "absent", "wlan", "wlan-node", "wlan-country", "wlan-node-country")) {
                val values = baseline.copy(imsSipSecurity = security, paniInitial = initial, paniCountry = " us ")
                val fields = Json.parseToJsonElement(show) as JsonObject
                val encoded = Json.parseToJsonElement(ConfigurationCodec.encode(values)) as JsonObject
                val wire = JsonObject(fields + encoded + mapOf(
                    "imsSipSecuritySource" to JsonPrimitive(if (security.isEmpty()) "default" else "user"),
                    "paniInitialSource" to JsonPrimitive(if (initial.isEmpty()) "default" else "user"),
                    "paniCountrySource" to JsonPrimitive("user"),
                ))
                val policy = ConfigurationCodec.show(wire.toString()).imsPolicy
                assertEquals(security, policy.securityOverride)
                assertEquals(initial, policy.initialOverride)
                assertEquals("US", policy.countryOverride)
            }
        }
        val explicit = baseline.copy(imsSipSecurity = "tcp_ipsec_3gpp", paniInitial = "wlan", paniCountry = "US")
        assertEquals("{}", ConfigurationCodec.encodeChanges(baseline, baseline.copy(imsSipSecurity = "", paniInitial = "", paniCountry = " ")))
        assertEquals(ConfigurationCodec.encodeChanges(explicit, baseline),
            ConfigurationCodec.encodeChanges(explicit, baseline.copy(imsSipSecurity = "", paniInitial = "", paniCountry = " ")))
    }

    @Test fun legacyNullAndResolvedPoliciesReadWithoutInventingOverrides() {
        val legacy = ConfigurationCodec.show(show)
        assertEquals("tcp_ipsec_3gpp", legacy.imsPolicy.security)
        assertEquals("", legacy.imsPolicy.securityOverride)
        val current = ConfigurationCodec.show(show.dropLast(1) + """, "imsSipSecurity":"swu_udp_aka","imsSipSecuritySource":"user","paniInitial":"absent","paniInitialSource":"catalog","paniCountry":"US","paniCountrySource":"home_mcc","future":{"safe":true}}""")
        assertEquals("swu_udp_aka", current.imsPolicy.securityOverride)
        assertEquals("", current.imsPolicy.initialOverride)
        assertEquals("", current.imsPolicy.countryOverride)
        assertEquals("US", current.imsPolicy.country)
        assertEquals(legacy.imsPolicy, ConfigurationCodec.show(show.dropLast(1) + """, "imsSipSecurity":null,"paniInitial":null,"paniCountry":null}""").imsPolicy)
        assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.show(show.dropLast(1) + """, "imsSipSecurity":"auto"}""") }
    }

    @Test fun countryNormalizesBeforeAuthoritativeReadbackAndOlderReadbackCannotConfirm() {
        val instance = ConfigurationEvaluator.instance(ConfigurationInstanceId("sample"), ConfigurationCodec.show(show))
        val draft = ConfigurationEditorDraft(instance)
        draft.paniCountry.edit { replace(0, length, " hk ") }
        draft.submit { _, _, complete ->
            complete(ConfigurationApplyOutcome.Confirmed(instance.copy(imsPolicy = instance.imsPolicy.copy(country = "HK", countrySource = "user"))))
            ConfigurationApplyAcceptance.Accepted
        }
        assertEquals(ConfigurationEditStatus.Saved, draft.status)
        assertEquals("HK", draft.paniCountry.text.toString())
        draft.imsSipSecurity = "swu_udp_aka"
        draft.submit { _, _, complete ->
            complete(ConfigurationApplyOutcome.Confirmed(instance))
            ConfigurationApplyAcceptance.Accepted
        }
        assertEquals(ConfigurationEditStatus.Unconfirmed, draft.status)
        assertEquals("swu_udp_aka", draft.imsSipSecurity)
    }

    @Test fun advancedAuthenticatedPaniReadbackRemainsCompatibleAndIsNotClearedByOlderEditor() {
        val prior = ConfigurationCodec.show(show)
        val withAdvanced = ConfigurationCodec.show(show.dropLast(1) + """, "paniAuthenticated":"wlan-node-country","paniAuthenticatedSource":"user"}""")
        assertEquals(prior.imsPolicy, withAdvanced.imsPolicy)
        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeChanges(baseline, baseline.copy(dns = "9.9.9.9"))) as JsonObject
        assertEquals(setOf("dnsOverrides"), patch.keys)
        assertFalse(patch.containsKey("paniAuthenticated"))
    }
}
