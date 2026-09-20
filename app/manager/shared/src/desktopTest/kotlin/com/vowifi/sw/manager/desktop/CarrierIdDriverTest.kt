package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.data.AndroidCarrierId
import com.vowifi.sw.manager.data.CandidateAssembly
import com.vowifi.sw.manager.data.RawSubscriptionIdentity
import com.vowifi.sw.manager.data.StandardCandidateAssembler
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class CarrierIdDriverTest {
    @Test
    fun drivesProjectionAndCandidateSerializationBooleans() {
        val report = drive()
        println(report.render())
        assertTrue(report.unknownAbsent)
        assertTrue(report.zeroAbsent)
        assertTrue(report.knownPresent)
        assertTrue(report.absentOmitted)
        assertFalse(report.layoutPresent)
        assertFalse(report.render().contains(TEMPLATE_ICCID))
        assertFalse(report.render().contains("15"))
    }
}

private class CarrierIdDriverReport(
    val unknownAbsent: Boolean,
    val zeroAbsent: Boolean,
    val knownPresent: Boolean,
    val absentOmitted: Boolean,
    val layoutPresent: Boolean,
) {
    fun render(): String = buildString {
        append("unknownAbsent=").append(unknownAbsent).append('\n')
        append("zeroAbsent=").append(zeroAbsent).append('\n')
        append("knownPresent=").append(knownPresent).append('\n')
        append("absentOmitted=").append(absentOmitted).append('\n')
        append("layoutPresent=").append(layoutPresent)
    }
}

private fun drive(): CarrierIdDriverReport {
    val unknown = assemble(AndroidCarrierId.UNKNOWN)
    val zero = assemble(0)
    val known = assemble(15)
    val absent = assemble(null)
    return CarrierIdDriverReport(
        unknownAbsent = !hasCarrierId(unknown),
        zeroAbsent = !hasCarrierId(zero),
        knownPresent = hasCarrierId(known),
        absentOmitted = !hasCarrierId(absent),
        layoutPresent = Json.parseToJsonElement(
            assertIs<CandidateAssembly.Ready>(known).payload,
        ).jsonObject.containsKey("ikeProposalLayout"),
    )
}

private fun assemble(carrierId: Int?): CandidateAssembly {
    return StandardCandidateAssembler.assemble(
        RawSubscriptionIdentity(
            iccid = TEMPLATE_ICCID,
            slot = 1,
            subscriptionId = 2,
            mcc = "310",
            mnc = "260",
            line1 = null,
            smsc = null,
            carrierId = carrierId,
        ),
    )
}

private fun hasCarrierId(assembled: CandidateAssembly): Boolean {
    val ready = assertIs<CandidateAssembly.Ready>(assembled)
    return Json.parseToJsonElement(ready.payload).jsonObject.containsKey("carrierId")
}

private const val TEMPLATE_ICCID = "8986000000000000001"
