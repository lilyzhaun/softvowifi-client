package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.CallBinding
import com.vowifi.sw.manager.model.MessageHistoryLine
import kotlin.test.*

class RegisteredLinePresentationTest {
    private val first = requireNotNull(SimOwnerId.fromHmacSha256(ByteArray(32) { 1 }))
    private val second = requireNotNull(SimOwnerId.fromHmacSha256(ByteArray(32) { 2 }))

    @Test fun realSlotLabelTakesPrecedenceOverUnrelatedHistoricalOrdinal() {
        val line = MessageHistoryLine(second, 2, true, MessageOwnerLabel(7, "Personal"), true, true, "SIM 2 · Example")
        assertEquals("SIM 2 · Example · Personal", line.lineTitle())
        assertFalse(line.lineSummary().contains("线路 7"))
        assertEquals(listOf(line), filterMessageLines(listOf(line), "SIM 2"))
        assertEquals("历史线路 7", line.copy(simLabel = null, label = MessageOwnerLabel(7, "")).lineTitle())
    }

    @Test fun emptyCallDraftTracksDefaultButTypedNumberKeepsOriginalOwner() {
        val a = lineState(first, "SIM 1")
        val b = lineState(second, "SIM 2")
        val draft = CallDialDraft()
        draft.acceptInitial(b)
        assertEquals(second, draft.owner)
        draft.acceptInitial(a)
        assertEquals(first, draft.owner)
        assertEquals("SIM 1", draft.label)
        draft.number = "155501"
        draft.acceptInitial(b)
        assertEquals(first, draft.owner)
        assertEquals("155501", draft.number)
        assertNull(draft.binding(b))
        val manual = CallDialDraft()
        manual.select(first, a)
        manual.acceptInitial(b)
        assertEquals(first, manual.owner)
    }

    private fun lineState(owner: SimOwnerId, label: String) = CallLineState(
        listOf(CallLineChoice(owner, label, true, true, true, true, true)), true, owner,
        CallBinding("synthetic-owner", null, 1, "epoch"),
    )
}
