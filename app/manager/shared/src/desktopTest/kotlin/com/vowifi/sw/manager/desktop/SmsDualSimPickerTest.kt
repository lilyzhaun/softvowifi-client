package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.model.MessageUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmsDualSimPickerTest {
    @Test fun newMessagePickerShowsTruthAndRemarksDoNotChangeDraftOwner() = smsOnEdt {
        val choices = smsChoices(selected = smsOwnerA).let { choices -> choices.copy(lines = choices.lines.map {
            if (it.owner == smsOwnerB) it.copy(active = null, sendable = false) else it
        }) }
        val source = SmsDualSimSource(smsReady(), choices)
        SmsDualSimDriver(source, initial = null).use { page ->
            page.click("新建短信")
            page.edit(0, smsPeerA)
            page.edit(1, "synthetic A draft")
            assertTrue(page.hasText("发送线路：Synthetic A"))
            page.openPicker()
            assertFalse(page.hasText("仅切换浏览历史，不切换发送线路"))
            assertTrue(page.hasText("0 个会话 · SIM 状态未知 · 已连接 · 暂不可发送"))
            page.capture("new-message-picker")
            page.edit(page.fields().lastIndex, "Synthetic B")
            page.click("备注")
            page.edit(page.fields().lastIndex, "Synthetic Travel")
            page.click("保存")
            assertEquals(listOf(smsOwnerB to "Synthetic Travel"), source.remarks)
            assertTrue(source.selections.isEmpty())
            assertEquals(smsPeerA, page.field(0))
            assertEquals("synthetic A draft", page.field(1))
            page.capture("new-message-bound")
            page.openPicker()
            page.click("Synthetic Travel")
            assertEquals(listOf(smsOwnerB), source.selections)
            assertTrue(page.hasText("发送线路：Synthetic Travel"))
            assertEquals("", page.field(1))
        }
    }

    @Test fun boundDraftRemainsEditableWhenItsOwnBackendIsReadonly() = smsOnEdt {
        SmsDualSimDriver().use { page ->
            page.edit(0, smsPeerA)
            page.edit(1, "synthetic before disconnect")
            page.source.publish(smsReady(authorized = false))
            page.frame()
            page.edit(1, "synthetic offline edit")
            assertFalse(page.canSend())
            page.source.publish(MessageUiState.Loading)
            page.frame()
            assertEquals("synthetic offline edit", page.field(1))
        }
    }
}
