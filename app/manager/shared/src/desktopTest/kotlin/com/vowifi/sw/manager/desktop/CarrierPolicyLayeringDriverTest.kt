package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.data.AutoProvisionRetry
import com.vowifi.sw.manager.data.AutoProvisionTracker
import com.vowifi.sw.manager.data.CandidateAssembly
import com.vowifi.sw.manager.data.RawSubscriptionIdentity
import com.vowifi.sw.manager.data.StandardCandidateAssembler
import com.vowifi.sw.manager.model.SimActiveJoinInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CarrierPolicyLayeringDriverTest {
    @Test
    fun drivesUnknownPlmnSelectionAndOmittedLayoutKeys() {
        val report = drive()

        println(report.render())
        assertTrue(report.unknownSelected)
        assertTrue(report.existingSkipped)
        assertTrue(report.malformedRejected)
        assertFalse(report.layoutPresent)
        assertTrue(report.emptyStoredEpdg)
        assertFalse("ikeProposalLayout" in report.candidateKeys)
        assertTrue("mcc" in report.candidateKeys)
        assertTrue("epdgFqdn" in report.candidateKeys)
        assertFalse(report.render().contains(TEMPLATE_ICCID))
    }
}

private class LayeringDriverReport(
    val unknownSelected: Boolean,
    val existingSkipped: Boolean,
    val malformedRejected: Boolean,
    val layoutPresent: Boolean,
    val emptyStoredEpdg: Boolean,
    val candidateKeys: List<String>,
) {
    fun render(): String = buildString {
        append("unknownSelected=").append(unknownSelected).append('\n')
        append("existingSkipped=").append(existingSkipped).append('\n')
        append("malformedRejected=").append(malformedRejected).append('\n')
        append("layoutPresent=").append(layoutPresent).append('\n')
        append("emptyStoredEpdg=").append(emptyStoredEpdg).append('\n')
        append("candidateKeys=").append(candidateKeys.joinToString(","))
    }
}

private fun drive(): LayeringDriverReport {
    val tracker = AutoProvisionTracker()
    val unknown = join("key-unknown", "999", "99")
    val selected = tracker.select(listOf(unknown), emptySet(), AutoProvisionRetry.Default)
    val existing = AutoProvisionTracker().select(listOf(unknown), setOf("key-unknown"), AutoProvisionRetry.Default)
    val malformed = AutoProvisionTracker().select(
        listOf(join("key-bad", "99", "99")),
        emptySet(),
        AutoProvisionRetry.Default,
    )
    val assembled = StandardCandidateAssembler.assemble(
        RawSubscriptionIdentity(
            iccid = TEMPLATE_ICCID,
            slot = 1,
            subscriptionId = 2,
            mcc = "999",
            mnc = "99",
            line1 = null,
            smsc = null,
        ),
    )
    val ready = assertIs<CandidateAssembly.Ready>(assembled)
    val payload = Json.parseToJsonElement(ready.payload).jsonObject
    val keys = payload.keys.sorted()
    return LayeringDriverReport(
        unknownSelected = selected.map(SimActiveJoinInput::key) == listOf("key-unknown"),
        existingSkipped = existing.isEmpty(),
        malformedRejected = malformed.isEmpty(),
        layoutPresent = "ikeProposalLayout" in payload,
        emptyStoredEpdg = payload["epdgFqdn"]?.jsonPrimitive?.content == "",
        candidateKeys = keys,
    )
}

private fun join(key: String, mcc: String, mnc: String) = SimActiveJoinInput(
    key = key,
    slotIndex = 0,
    carrierName = "probe",
    displayName = null,
    mcc = mcc,
    mnc = mnc,
    lastFour = null,
)

private const val TEMPLATE_ICCID = "8986000000000000001"
