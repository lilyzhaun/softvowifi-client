package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.ui.ConfigurationEditorDraft
import com.vowifi.sw.manager.ui.ConfigurationEditStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigurationCarrierPolicyTest {
    private val carrierShow = """{"ok":true,"formatState":"current","configVersion":1,"configRevision":"r1_abcdefghijklmnopabcdefghijklmnop","displayLabel":"0001","lifecycle":"stopped","transport":"wifi","smsc":"+15550000001","epdgFqdn":"wlan.three.com.hk","imsRealm":"ims.mnc003.mcc454.3gppnetwork.org","dns":"","resolvedEpdgFqdn":"wlan.three.com.hk","resolvedImsRealm":"ims.mnc003.mcc454.3gppnetwork.org","mcc":"454","mnc":"03","identitySource":"legacy","epdgResolutionKind":"configured","imsSipSecurity":"swu_udp_aka","imsSipSecuritySource":"carrier","paniInitial":"wlan-node-country","paniInitialSource":"carrier","paniCountry":"HK","paniCountrySource":"carrier"}"""

    @Test fun actualShowRetainsEffectiveValuesAndPerFieldCarrierSources() {
        val policy = instance().imsPolicy
        assertEquals("swu_udp_aka", policy.security)
        assertEquals("wlan-node-country", policy.initial)
        assertEquals("HK", policy.country)
        assertEquals(listOf("carrier", "carrier", "carrier"), listOf(policy.securitySource, policy.initialSource, policy.countrySource))
        assertEquals(listOf("", "", ""), listOf(policy.securityOverride, policy.initialOverride, policy.countryOverride))
    }

    @Test fun unifiedCatalogSecurityIsReportedWithoutBecomingUserOverride() {
        val current = instance(mapOf("imsSipSecuritySource" to "catalog", "paniInitialSource" to "catalog", "paniCountrySource" to "home_mcc"))
        assertEquals("catalog", current.imsPolicy.securitySource)
        assertEquals("catalog", current.imsPolicy.initialSource)
        assertEquals("home_mcc", current.imsPolicy.countrySource)
        val draft = ConfigurationEditorDraft(current)
        assertEquals("", draft.overrides().imsSipSecurity)
        assertEquals("{}", ConfigurationCodec.encodeChanges(draft.overrides(), draft.overrides()))
    }

    @Test fun untouchedCarrierDraftProducesNoPatchAndDnsSaveDoesNotPersistPolicies() {
        val original = instance()
        val draft = ConfigurationEditorDraft(original)
        val before = draft.overrides()
        assertEquals(listOf("", "", ""), listOf(before.imsSipSecurity, before.paniInitial, before.paniCountry))
        assertFalse(draft.dirty)
        assertEquals("{}", ConfigurationCodec.encodeChanges(before, draft.overrides()))
        draft.dns.edit { replace(0, length, "1.1.1.1") }
        draft.submit { _, next, complete ->
            assertEquals("""{"dnsOverrides":{"wifi":"1.1.1.1:53"}}""", ConfigurationCodec.encodeChanges(before, next))
            complete(ConfigurationApplyOutcome.Confirmed(original.copy(dns = "1.1.1.1:53")))
            ConfigurationApplyAcceptance.Accepted
        }
        assertEquals(ConfigurationEditStatus.Saved, draft.status)
        assertFalse(draft.dirty)
    }

    @Test fun explicitlyChoosingTheSameEffectiveValueStillCreatesAUserOverride() {
        for (field in policyFields) {
            val original = instance()
            val draft = ConfigurationEditorDraft(original)
            val before = draft.overrides()
            when (field) {
                "imsSipSecurity" -> draft.imsSipSecurity = original.imsPolicy.security
                "paniInitial" -> draft.paniInitial = original.imsPolicy.initial
                "paniCountry" -> draft.paniCountry.edit { replace(0, length, original.imsPolicy.country) }
            }
            assertTrue(draft.dirty)
            draft.submit { _, next, complete ->
                val patch = Json.parseToJsonElement(ConfigurationCodec.encodeChanges(before, next)) as JsonObject
                assertEquals(setOf(field), patch.keys)
                assertEquals(showFields.getValue(field), patch.getValue(field))
                complete(ConfigurationApplyOutcome.Confirmed(instance(mapOf(field + "Source" to "user"))))
                ConfigurationApplyAcceptance.Accepted
            }
            assertEquals(ConfigurationEditStatus.Saved, draft.status)
        }
    }

    @Test fun clearingOneUserOverrideRestoresCarrierAndPreservesOtherOverrides() {
        val allUser = policyFields.associate { it + "Source" to "user" }
        for (field in policyFields) {
            val original = instance(allUser)
            val draft = ConfigurationEditorDraft(original)
            val before = draft.overrides()
            when (field) {
                "imsSipSecurity" -> draft.imsSipSecurity = ""
                "paniInitial" -> draft.paniInitial = ""
                "paniCountry" -> draft.paniCountry.edit { replace(0, length, "") }
            }
            draft.submit { _, next, complete ->
                assertEquals("""{"$field":""}""", ConfigurationCodec.encodeChanges(before, next))
                complete(ConfigurationApplyOutcome.Confirmed(instance(allUser + (field + "Source" to "carrier"))))
                ConfigurationApplyAcceptance.Accepted
            }
            assertEquals(ConfigurationEditStatus.Saved, draft.status)
            assertFalse(draft.dirty)
            val encoded = Json.parseToJsonElement(ConfigurationCodec.encode(draft.overrides())) as JsonObject
            for (other in policyFields - field) assertEquals(showFields[other], encoded[other])
            assertEquals(JsonPrimitive(""), encoded[field])
        }
    }

    @Test fun unknownSourcesUseExistingFallbacksAndNeverBecomeCarrierOrUser() {
        for (source in listOf("unknown", "Carrier", " carrier", "user ", "carrier/user", "")) {
            val policy = instance(policyFields.associate { it + "Source" to source }).imsPolicy
            assertEquals(listOf("default", "default", "home_mcc"), listOf(policy.securitySource, policy.initialSource, policy.countrySource))
            assertEquals(listOf("", "", ""), listOf(policy.securityOverride, policy.initialOverride, policy.countryOverride))
        }
    }

    private val policyFields = listOf("imsSipSecurity", "paniInitial", "paniCountry")
    private val showFields get() = Json.parseToJsonElement(carrierShow) as JsonObject
    private fun instance(changes: Map<String, String> = emptyMap()) = ConfigurationEvaluator.instance(
        ConfigurationInstanceId("carrier-policy"),
        ConfigurationCodec.show(JsonObject(showFields + changes.mapValues { JsonPrimitive(it.value) }).toString()),
    )
}
