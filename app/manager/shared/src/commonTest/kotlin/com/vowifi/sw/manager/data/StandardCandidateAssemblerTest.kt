package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class StandardCandidateAssemblerTest {
    @Test
    fun privateReadCanCompleteWithoutImsiOrImei() {
        val identity = blankIdentity()

        val assembled = StandardCandidateAssembler.assemble(identity)

        val ready = assertIs<CandidateAssembly.Ready>(assembled)
        val payload = Json.parseToJsonElement(ready.payload).jsonObject
        assertEquals("", payload.getValue("imsi").jsonPrimitive.content)
        assertEquals("", payload.getValue("imei").jsonPrimitive.content)
        assertEquals("auto", payload.getValue("imsIdentityMode").jsonPrimitive.content)
        assertFalse(payload.containsKey("impi"))
        assertFalse(payload.containsKey("sipImpu"))
        assertFalse(payload.containsKey("imsHomeDomain"))
        assertFalse(payload.containsKey("imsIdentityReadStatus"))
        assertEquals(TEMPLATE_ICCID, payload.getValue("iccid").jsonPrimitive.content)
        assertEquals(TEMPLATE_SLOT, payload.getValue("slot").jsonPrimitive.int)
        assertEquals(TEMPLATE_SUB_ID, payload.getValue("subscriptionId").jsonPrimitive.int)
        assertPrivate(identity.toString())
        assertPrivate(ready.toString())
        assertFalse(identity.toString().contains(TEMPLATE_ICCID))
        assertFalse(ready.toString().contains(TEMPLATE_ICCID))
        assertFalse(identity.toString().contains("subscriptionId", ignoreCase = true))
        assertFalse(ready.toString().contains(ready.payload))
    }

    @Test
    fun assembleEmitsEmptyIdentityAndPublicProfileWithoutLeakage() {
        val identity = blankIdentity(
            line1 = "  +1555  ",
            smsc = " +155501 ",
        )

        val assembled = StandardCandidateAssembler.assemble(identity)

        val ready = assertIs<CandidateAssembly.Ready>(assembled)
        val payload = Json.parseToJsonElement(ready.payload).jsonObject
        assertEquals("", payload.getValue("imsi").jsonPrimitive.content)
        assertEquals("", payload.getValue("imei").jsonPrimitive.content)
        assertEquals("auto", payload.getValue("imsIdentityMode").jsonPrimitive.content)
        assertFalse(payload.containsKey("impi"))
        assertFalse(payload.containsKey("sipImpu"))
        assertFalse(payload.containsKey("imsHomeDomain"))
        assertFalse(payload.containsKey("imsIdentityReadStatus"))
        assertEquals("310", payload.getValue("mcc").jsonPrimitive.content)
        assertEquals("260", payload.getValue("mnc").jsonPrimitive.content)
        assertEquals(3, payload.getValue("mncLen").jsonPrimitive.int)
        assertEquals("", payload.getValue("epdgFqdn").jsonPrimitive.content)
        assertEquals("ims.mnc260.mcc310.3gppnetwork.org", payload.getValue("imsRealm").jsonPrimitive.content)
        assertEquals("wifi", payload.getValue("transport").jsonPrimitive.content)
        assertEquals("+155501", payload.getValue("smsc").jsonPrimitive.content)
        assertEquals("", payload.getValue("dns").jsonPrimitive.content)
        assertEquals(true, payload.getValue("enabled").jsonPrimitive.boolean)
        assertEquals("+1555", payload.getValue("line1").jsonPrimitive.content)
        assertEquals(TEMPLATE_SUB_ID, payload.getValue("subscriptionId").jsonPrimitive.int)
        assertFalse(payload.containsKey("subId"))
        IDENTITY_TOKENS.forEach { token ->
            assertFalse(ready.toString().contains(token, ignoreCase = true), ready.toString())
            assertFalse(identity.toString().contains(token, ignoreCase = true), identity.toString())
        }
        assertPrivate(identity.toString())
        assertPrivate(ready.toString())
    }

    @Test
    fun assembleEmitsAutoIdentityModeWithoutIdentityFields() {
        val assembled = StandardCandidateAssembler.assemble(blankIdentity())

        val ready = assertIs<CandidateAssembly.Ready>(assembled)
        val payload = Json.parseToJsonElement(ready.payload).jsonObject
        assertEquals("auto", payload.getValue("imsIdentityMode").jsonPrimitive.content)
        assertFalse(payload.containsKey("impi"))
        assertFalse(payload.containsKey("sipImpu"))
        assertFalse(payload.containsKey("imsHomeDomain"))
        assertFalse(payload.containsKey("imsIdentityReadStatus"))
        assertFalse(ready.payload.contains("impi"))
        assertFalse(ready.payload.contains("sipImpu"))
        assertPrivate(ready.toString())
    }

    @Test
    fun assembleKeepsCreateCandidateDnsEmpty() {
        val assembled = StandardCandidateAssembler.assemble(blankIdentity())

        val ready = assertIs<CandidateAssembly.Ready>(assembled)
        val payload = Json.parseToJsonElement(ready.payload).jsonObject
        assertEquals("", payload.getValue("dns").jsonPrimitive.content)
    }

    @Test
    fun assembleOmitsIkeProposalLayoutForLebara50503() {
        val assembled = StandardCandidateAssembler.assemble(blankIdentity(mcc = "505", mnc = "03"))

        val ready = assertIs<CandidateAssembly.Ready>(assembled)
        val payload = Json.parseToJsonElement(ready.payload).jsonObject
        assertEquals("505", payload.getValue("mcc").jsonPrimitive.content)
        assertEquals("03", payload.getValue("mnc").jsonPrimitive.content)
        assertEquals("", payload.getValue("epdgFqdn").jsonPrimitive.content)
        assertFalse(payload.containsKey("ikeProposalLayout"))
        assertFalse(ready.payload.contains("ikeProposalLayout"))
    }

    @Test
    fun assembleOmitsIkeProposalLayoutForTmobile310260() {
        val assembled = StandardCandidateAssembler.assemble(blankIdentity(mcc = "310", mnc = "260"))

        val ready = assertIs<CandidateAssembly.Ready>(assembled)
        val payload = Json.parseToJsonElement(ready.payload).jsonObject
        assertEquals("", payload.getValue("epdgFqdn").jsonPrimitive.content)
        assertFalse(payload.containsKey("ikeProposalLayout"))
        assertFalse(ready.payload.contains("ikeProposalLayout"))
    }

    @Test
    fun assembleLeavesThreeHkEpdgEmptyAndOmitsIkeProposalLayout() {
        val assembled = StandardCandidateAssembler.assemble(blankIdentity(mcc = "454", mnc = "03"))

        val ready = assertIs<CandidateAssembly.Ready>(assembled)
        val payload = Json.parseToJsonElement(ready.payload).jsonObject
        assertEquals("", payload.getValue("epdgFqdn").jsonPrimitive.content)
        assertFalse(payload.containsKey("ikeProposalLayout"))
        assertFalse(ready.payload.contains("ikeProposalLayout"))
    }

    @Test
    fun assembleOmitsIkeProposalLayoutForUnknownValidPlmn() {
        val assembled = StandardCandidateAssembler.assemble(blankIdentity(mcc = "999", mnc = "99"))

        val ready = assertIs<CandidateAssembly.Ready>(assembled)
        val payload = Json.parseToJsonElement(ready.payload).jsonObject
        assertEquals("999", payload.getValue("mcc").jsonPrimitive.content)
        assertEquals("99", payload.getValue("mnc").jsonPrimitive.content)
        assertEquals(2, payload.getValue("mncLen").jsonPrimitive.int)
        assertEquals("", payload.getValue("epdgFqdn").jsonPrimitive.content)
        assertFalse(payload.containsKey("ikeProposalLayout"))
        assertFalse(ready.payload.contains("ikeProposalLayout"))
    }

    @Test
    fun assembleRejectsInvalidIccidSlotOrUnsupportedPlmn() {
        assertIs<CandidateAssembly.Invalid>(
            StandardCandidateAssembler.assemble(blankIdentity(iccid = "8986abc")),
        )
        assertIs<CandidateAssembly.Invalid>(
            StandardCandidateAssembler.assemble(blankIdentity(slot = 2)),
        )
        assertIs<CandidateAssembly.Invalid>(
            StandardCandidateAssembler.assemble(blankIdentity(subscriptionId = -1)),
        )
        assertIs<CandidateAssembly.Unsupported>(
            StandardCandidateAssembler.assemble(blankIdentity(mcc = null, mnc = "260")),
        )
    }

    @Test
    fun assembleOmitsCarrierIdWhenUnknownZeroOrAbsent() {
        listOf(null, AndroidCarrierId.UNKNOWN, 0).forEach { raw ->
            val identity = blankIdentity(carrierId = raw)
            val assembled = StandardCandidateAssembler.assemble(identity)
            val ready = assertIs<CandidateAssembly.Ready>(assembled)
            val payload = Json.parseToJsonElement(ready.payload).jsonObject
            assertFalse(payload.containsKey("carrierId"))
            assertFalse(ready.payload.contains("carrierId"))
            assertPrivate(identity.toString())
            assertPrivate(ready.toString())
            assertFalse(identity.toString().contains("carrierId", ignoreCase = true))
            assertFalse(ready.toString().contains("carrierId", ignoreCase = true))
        }
    }

    @Test
    fun assemblePersistsPositiveCarrierIdWithoutLeakingToString() {
        val identity = blankIdentity(carrierId = SYNTH_CARRIER_ID)
        val assembled = StandardCandidateAssembler.assemble(identity)
        val ready = assertIs<CandidateAssembly.Ready>(assembled)
        val payload = Json.parseToJsonElement(ready.payload).jsonObject
        assertEquals(SYNTH_CARRIER_ID, payload.getValue("carrierId").jsonPrimitive.int)
        assertFalse(payload.containsKey("ikeProposalLayout"))
        assertPrivate(identity.toString())
        assertPrivate(ready.toString())
        IDENTITY_TOKENS.forEach { token ->
            assertFalse(identity.toString().contains(token, ignoreCase = true), identity.toString())
            assertFalse(ready.toString().contains(token, ignoreCase = true), ready.toString())
        }
        assertFalse(identity.toString().contains(SYNTH_CARRIER_ID.toString()))
        assertFalse(ready.toString().contains(SYNTH_CARRIER_ID.toString()))
        assertFalse(ready.toString().contains(ready.payload))
    }

    private fun blankIdentity(
        iccid: String = TEMPLATE_ICCID,
        slot: Int = TEMPLATE_SLOT,
        subscriptionId: Int = TEMPLATE_SUB_ID,
        mcc: String? = "310",
        mnc: String? = "260",
        line1: String? = null,
        smsc: String? = null,
        carrierId: Int? = null,
    ) = RawSubscriptionIdentity(
        iccid = iccid,
        slot = slot,
        subscriptionId = subscriptionId,
        mcc = mcc,
        mnc = mnc,
        line1 = line1,
        smsc = smsc,
        carrierId = carrierId,
    )

    private companion object {
        const val TEMPLATE_ICCID = "8986000000000000001"
        const val TEMPLATE_SLOT = 1
        const val TEMPLATE_SUB_ID = 2
        const val SYNTH_CARRIER_ID = 15
        val IDENTITY_TOKENS = listOf(
            "iccid",
            "imsi",
            "imei",
            "impi",
            "sipImpu",
            "imsHomeDomain",
            "msisdn",
            "line1",
            "subId",
            "subscriptionId",
            "carrierId",
        )
    }
}
