package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageHistoryLine
import com.vowifi.sw.manager.ui.lineTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HistoryLineNameTest {
    private val a = SimOwnerId.fromHmacSha256(ByteArray(32) { 1 })!!
    private val b = SimOwnerId.fromHmacSha256(ByteArray(32) { 2 })!!

    @Test fun uniqueOperatorKeepsHomeNameAndDuplicateOperatorsUseOnlyLastFour() {
        val first = HistoryLineName.parse("T-Mobile", "1234")
        assertEquals("T-Mobile", historyLineTitles(mapOf(a to first)).getValue(a))
        val titles = historyLineTitles(mapOf(a to first, b to HistoryLineName.parse("T-Mobile", "5678")))
        assertEquals("T-Mobile · •••• 1234", titles[a])
        assertEquals("T-Mobile · •••• 5678", titles[b])
        assertEquals("HistoryLineName", first.toString())
    }

    @Test fun unknownOrUnsafeNameNeverUsesIdentityAndHasSafeTail() {
        val raw = "89" + "123456789012345678"
        val name = HistoryLineName.parse("ICCID $raw", "1234")
        assertEquals("归属运营商未知 · •••• 1234", historyLineTitles(mapOf(a to name))[a])
        assertFalse(name.toString().contains(raw))
        assertEquals(null, HistoryLineName.parse("T-Mobile", raw).lastFour)
    }

    @Test fun savedMessageRemarkOverridesDefaultWithoutDestroyingDefaultOnClear() {
        val label = MessageOwnerLabel(9, "", defaultName = "T-Mobile · •••• 1234")
        val line = MessageHistoryLine(a, 1, false, label)
        assertEquals("T-Mobile · •••• 1234", line.lineTitle())
        assertEquals("旅行线路", line.copy(label = label.copy(remark = "旅行线路")).lineTitle())
        assertEquals("T-Mobile · •••• 1234", line.copy(simLabel = "SIM 2 · T-Mobile").lineTitle())
        assertEquals("MessageOwnerLabel", label.toString())
    }
}
