package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialKeyPresentationTest {
    @Test
    fun dialKeysContainOnlyApprovedSymbolsInStableOrder() {
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "+", "0", null),
            DialKey.entries.map { it.emittedText },
        )
        assertEquals(DialKey.BACKSPACE, DialKey.entries.last())
        assertNull(DialKey.BACKSPACE.emittedText)
    }

    @Test
    fun dialKeyVisualSpecUsesMiuixPressAndExplicitDisabledSemantics() {
        val enabled = dialKeyVisualSpec(DialKey.ONE, enabled = true)
        val disabled = dialKeyVisualSpec(DialKey.BACKSPACE, enabled = false)

        assertEquals(48, enabled.minimumTouchDp)
        assertEquals(PressFeedback.MIUIX_DEFAULT, enabled.pressFeedback)
        assertTrue(enabled.enabled)
        assertEquals("输入 1", enabled.semanticsLabel)
        assertFalse(disabled.enabled)
        assertEquals("退格（不可用）", disabled.semanticsLabel)
    }
}
