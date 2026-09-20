package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.ui.ConfigurationEditorDraft
import com.vowifi.sw.manager.ui.ConfigurationEditStatus
import com.vowifi.sw.manager.ui.ConfigurationFieldKey
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationDigestRealmTest {
    private val legacyClearGoReadback = """{"imsDigestRealm":"ims.spark.co.nz","imsDigestRealmSource":"carrier","ok":true,"displayLabel":"0001","lifecycle":"stopped","transport":"wifi","smsc":"+15550000001","epdgFqdn":"epdg.example.test","imsRealm":"","dns":"9.9.9.9","resolvedEpdgFqdn":"epdg.example.test","resolvedImsRealm":"ims.mnc005.mcc530.3gppnetwork.org","mcc":"530","mnc":"05","identitySource":"legacy","epdgResolutionKind":"configured","imsSipSecurity":"tcp_ipsec_3gpp","imsSipSecuritySource":"default","paniInitial":"absent","paniInitialSource":"default","paniCountry":"HK","paniCountrySource":"user"}"""

    private fun legacyStandardShow(mnc: String = "05"): String {
        val realm = "ims.mnc${mnc.padStart(3, '0')}.mcc530.3gppnetwork.org"
        return JsonObject(Json.parseToJsonElement(legacyClearGoReadback).jsonObject + mapOf(
            "imsRealm" to JsonPrimitive(realm),
            "resolvedImsRealm" to JsonPrimitive(realm),
            "imsDigestRealm" to JsonPrimitive(realm),
            "imsDigestRealmSource" to JsonPrimitive("identity"),
            "mnc" to JsonPrimitive(mnc),
        )).toString()
    }

    @Test fun legacyStandardRealmClearSavesExactlyOneKeyAndUsesCarrierReadback() {
        val id = ConfigurationInstanceId("legacy-clear-test")
        val original = ConfigurationEvaluator.instance(id, ConfigurationCodec.show(legacyStandardShow()))
        val confirmed = ConfigurationEvaluator.instance(id, ConfigurationCodec.show(legacyClearGoReadback))
        val draft = ConfigurationEditorDraft(original)
        val before = draft.overrides()
        assertEquals("ims.mnc005.mcc530.3gppnetwork.org", draft.imsRealm.text.toString())
        assertEquals("identity", original.imsPolicy.digestRealmSource)
        assertEquals("", draft.imsDigestRealm.text.toString())
        assertEquals("HK", draft.paniCountry.text.toString())
        var submissions = 0

        draft.imsRealm.edit { replace(0, length, "") }
        draft.submit { submittedId, next, complete ->
            submissions++
            assertEquals(id, submittedId)
            assertEquals("""{"imsRealm":""}""", ConfigurationCodec.encodeChanges(before, next))
            assertEquals(before.copy(imsRealm = ""), next)
            assertEquals(ConfigurationEditStatus.Saving, draft.status)
            complete(ConfigurationApplyOutcome.Confirmed(confirmed))
            ConfigurationApplyAcceptance.Accepted
        }

        assertEquals(1, submissions)
        assertEquals(ConfigurationEditStatus.Saved, draft.status)
        assertFalse(draft.dirty)
        assertEquals("ims.spark.co.nz", confirmed.imsPolicy.digestRealm)
        assertEquals("carrier", confirmed.imsPolicy.digestRealmSource)
        assertEquals(original.resolvedImsRealm, confirmed.resolvedImsRealm)
        assertEquals(original.imsPolicy.copy(digestRealm = "ims.spark.co.nz", digestRealmSource = "carrier"), confirmed.imsPolicy)
        assertEquals("", draft.imsDigestRealm.text.toString())
        assertEquals("{}", ConfigurationCodec.encodeChanges(draft.overrides(), ConfigurationEditorDraft(confirmed).overrides()))
    }

    @Test fun unrelatedSaveAcceptsIdentityDigestForLegacyAndUnmatchedOperator() {
        for (mnc in listOf("05", "06")) {
            val id = ConfigurationInstanceId("legacy-dns-$mnc")
            val original = ConfigurationEvaluator.instance(id, ConfigurationCodec.show(legacyStandardShow(mnc)))
            val draft = ConfigurationEditorDraft(original)
            val before = draft.overrides()
            val readback = JsonObject(Json.parseToJsonElement(legacyStandardShow(mnc)).jsonObject +
                ("dns" to JsonPrimitive("1.1.1.1"))).toString()
            val confirmed = ConfigurationEvaluator.instance(id, ConfigurationCodec.show(readback))
            var submissions = 0

            draft.dns.edit { replace(0, length, "1.1.1.1") }
            draft.submit { _, next, complete ->
                submissions++
                assertEquals("""{"dns":"1.1.1.1"}""", ConfigurationCodec.encodeChanges(before, next))
                complete(ConfigurationApplyOutcome.Confirmed(confirmed))
                ConfigurationApplyAcceptance.Accepted
            }

            assertEquals(1, submissions)
            assertEquals(ConfigurationEditStatus.Saved, draft.status)
            assertFalse(draft.dirty)
            assertEquals(original.imsPolicy, confirmed.imsPolicy)
            assertEquals("identity", confirmed.imsPolicy.digestRealmSource)
            assertEquals(before.copy(dns = "1.1.1.1"), draft.overrides())
        }
    }

    private val legacy = """{"ok":true,"displayLabel":"0001","lifecycle":"stopped","transport":"wifi","smsc":"","epdgFqdn":"","imsRealm":"","dns":"","resolvedEpdgFqdn":"epdg.example.test","resolvedImsRealm":"ims.mnc005.mcc530.3gppnetwork.org","mcc":"530","mnc":"05"}"""
    private fun instance(realm: String? = null, source: String? = null) = ConfigurationEvaluator.instance(
        ConfigurationInstanceId("digest-test"), ConfigurationCodec.show(JsonObject(buildMap {
            putAll(Json.parseToJsonElement(legacy).jsonObject)
            realm?.let { put("imsDigestRealm", JsonPrimitive(it)) }
            source?.let { put("imsDigestRealmSource", JsonPrimitive(it)) }
        }).toString()),
    )

    @Test fun effectiveValuesNeverBecomeOverridesAndDnsSaveIsIsolated() {
        for ((realm, source) in listOf("ims.spark.co.nz" to "carrier", "isim.test" to "identity")) {
            val original = instance(realm, source)
            assertEquals(realm, original.imsPolicy.digestRealm)
            assertEquals(source, original.imsPolicy.digestRealmSource)
            assertEquals("", original.imsPolicy.digestRealmOverride)
            val draft = ConfigurationEditorDraft(original)
            val before = draft.overrides()
            assertEquals("{}", ConfigurationCodec.encodeChanges(before, draft.overrides()))
            draft.dns.edit { replace(0, length, "1.1.1.1") }
            draft.submit { _, next, complete ->
                assertEquals("""{"dns":"1.1.1.1"}""", ConfigurationCodec.encodeChanges(before, next))
                complete(ConfigurationApplyOutcome.Confirmed(original.copy(dns = next.dns)))
                ConfigurationApplyAcceptance.Accepted
            }
            assertEquals(ConfigurationEditStatus.Saved, draft.status)
        }
    }

    @Test fun sameEffectiveValueIsExplicitAndClearRestoresInheritance() {
        val inherited = instance("ims.spark.co.nz", "carrier")
        val user = instance("ims.spark.co.nz", "user")
        for ((original, text, confirmed) in listOf(Triple(inherited, "ims.spark.co.nz", user), Triple(user, "", inherited))) {
            val draft = ConfigurationEditorDraft(original)
            val before = draft.overrides()
            draft.imsDigestRealm.edit { replace(0, length, text) }
            assertTrue(draft.dirty)
            draft.submit { _, next, complete ->
                assertEquals("""{"imsDigestRealm":"$text"}""", ConfigurationCodec.encodeChanges(before, next))
                complete(ConfigurationApplyOutcome.Confirmed(confirmed))
                ConfigurationApplyAcceptance.Accepted
            }
            assertEquals(ConfigurationEditStatus.Saved, draft.status)
            assertFalse(draft.dirty)
        }
    }

    @Test fun oldOrUnknownMetadataNeverClaimsCarrierOrUser() {
        val old = instance().imsPolicy
        assertEquals("ims.mnc005.mcc530.3gppnetwork.org", old.digestRealm)
        assertEquals("identity", old.digestRealmSource)
        assertEquals("identity", instance(source = "carrier").imsPolicy.digestRealmSource)
        assertEquals("", instance(source = "user").imsPolicy.digestRealmOverride)
        for (source in listOf(null, "unknown", "Carrier", "user ")) {
            assertEquals("identity", instance("public.test", source).imsPolicy.digestRealmSource)
            assertEquals("", instance("public.test", source).imsPolicy.digestRealmOverride)
        }
        val values = ConfigurationEditorDraft(instance()).overrides().copy(imsDigestRealm = null)
        assertFalse("imsDigestRealm" in Json.parseToJsonElement(ConfigurationCodec.encode(values)).jsonObject)
        val identity = instance("legacy_name.test", "identity")
        assertEquals("legacy_name.test", identity.imsPolicy.digestRealm)
        assertEquals("", identity.imsPolicy.digestRealmOverride)
        val identityDraft = ConfigurationEditorDraft(identity)
        assertEquals("""{"dns":"1.1.1.1"}""", ConfigurationCodec.encodeChanges(
            identityDraft.overrides(), identityDraft.overrides().copy(dns = "1.1.1.1")))
    }

    @Test fun unsafeInputIsRejectedAtBothBoundariesAndInline() {
        for (value in listOf("user@host.test", "host.test:5060", "host.test\n", "host\u007f.test", " host.test", "sip:host.test", "host/test", "host;foo", "host?foo", "[::1", "[::1]:\u0660")) {
            assertFailsWith<ConfigurationFormatException.InvalidDigestRealm> { instance(value, "user") }
            val draft = ConfigurationEditorDraft(instance())
            draft.imsDigestRealm.edit { replace(0, length, value) }
            assertFalse(draft.validate())
            assertEquals(setOf(ConfigurationFieldKey.DigestRealm), draft.errors.keys)
            draft.submit { _, _, _ -> fail("Invalid input reached backend") }
        }
        val draft = ConfigurationEditorDraft(instance())
        assertFailsWith<ConfigurationFormatException.IdentityLeak> {
            ConfigurationCodec.encode(draft.overrides().copy(imsDigestRealm = "123456789012345.test"))
        }
        for (value in listOf("Digest.Example.test", "host-name.test", "")) {
            assertEquals(value, Json.parseToJsonElement(ConfigurationCodec.encode(draft.overrides().copy(imsDigestRealm = value))).jsonObject.getValue("imsDigestRealm").jsonPrimitive.content)
        }
    }

    @Test fun quotesBackslashesAndInvalidDnsNamesNeverReachSave() {
        val tooLong = listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(62)).joinToString(".")
        val invalid = listOf(
            "bad\"host.test", "bad\\host.test", "bad'host.test", "host_name", "[::1]", "[::1]:0",
            ".host", "host..test", "host.test..", ".", "-host.test", "host-.test",
            "a".repeat(64) + ".test", tooLong, "$tooLong.", "höst.test", "host。test",
            "host\u0000.test", "host\r\n.test", "host\t.test", "host name.test", "host\u00a0.test",
        )
        for (value in invalid) {
            val original = instance("ims.spark.co.nz", "carrier").copy(dns = "9.9.9.9", smsc = "+15550000001")
            val draft = ConfigurationEditorDraft(original)
            val before = draft.overrides()
            assertFailsWith<ConfigurationFormatException.InvalidDigestRealm> { instance(value, "user") }
            assertFailsWith<ConfigurationFormatException.InvalidDigestRealm> {
                ConfigurationCodec.encode(before.copy(imsDigestRealm = value))
            }
            draft.imsDigestRealm.edit { replace(0, length, value) }
            draft.submit { _, _, _ -> fail("Invalid hostname reached save") }
            assertEquals(ConfigurationEditStatus.Invalid, draft.status)
            assertEquals(setOf(ConfigurationFieldKey.DigestRealm), draft.errors.keys)
            assertEquals(before, draft.overrides().copy(imsDigestRealm = ""))
            draft.discard()
            assertEquals("{}", ConfigurationCodec.encodeChanges(before, draft.overrides()))
        }
    }

    @Test fun validDnsBoundariesKeepCaseAndEmitOnlyExplicitDigestOverride() {
        val longest = listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(61)).joinToString(".")
        assertEquals(253, longest.length)
        for (value in listOf("a", "9", "a-b.Example", "xn--bcher-kva.example", "MiXeD.Example.", "a".repeat(63), longest, "$longest.")) {
            val original = instance("ims.spark.co.nz", "carrier")
            val draft = ConfigurationEditorDraft(original)
            val before = draft.overrides()
            draft.imsDigestRealm.edit { replace(0, length, value) }
            assertTrue(draft.validate())
            assertEquals(value, instance(value, "user").imsPolicy.digestRealmOverride)
            draft.submit { _, next, complete ->
                val patch = Json.parseToJsonElement(ConfigurationCodec.encodeChanges(before, next)).jsonObject
                assertEquals(setOf("imsDigestRealm"), patch.keys)
                assertEquals(value, patch.getValue("imsDigestRealm").jsonPrimitive.content)
                complete(ConfigurationApplyOutcome.Confirmed(instance(value, "user")))
                ConfigurationApplyAcceptance.Accepted
            }
            assertEquals(ConfigurationEditStatus.Saved, draft.status)
        }
    }
}
