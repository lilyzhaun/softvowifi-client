package com.vowifi.sw.manager.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.*

class PhoneDraftNormalizationTest {
    @Test fun selectionAndCompositionFollowTheKeptPrefixIncludingMiddlePasteAndReversedSelection() {
        val paste = "9 (555) 123-4567 9"
        val middle = assertNotNull(normalizePhoneEditor(TextFieldValue(paste, TextRange(paste.lastIndexOf('9')))))
        assertEquals("955512345679", middle.text)
        assertEquals(TextRange(middle.text.length - 1), middle.selection, "光标应在插入内容后，不能跳过原后缀")
        val reversed = assertNotNull(normalizePhoneEditor(TextFieldValue("12 (34) 56", TextRange(9, 4), TextRange(4, 6))))
        assertEquals(TextRange(5, 2), reversed.selection)
        assertEquals(TextRange(2, 4), reversed.composition)
        val unchanged = TextFieldValue("123", TextRange(1))
        assertEquals(unchanged, normalizePhoneEditor(unchanged), "单键与仅移动光标不变")
        assertNull(normalizePhoneEditor(TextFieldValue("12abc")))
    }
    @Test fun formattedPastesNormalizeBeforeTheExistingDialGrammarIsChecked() {
        assertEquals("+15551234567", acceptPhoneDraft("9", "+1 (555) 123-4567"))
        assertEquals("13800138000", acceptPhoneDraft("9", "138 0013 8000"))
        assertEquals("123456", acceptPhoneDraft("9", "1\u00a02.3/4-5(6)"))
        assertEquals("*123#", acceptPhoneDraft("9", "* 123 #"))
    }
    @Test fun invalidInputIsNotSilentlyReinterpretedOrTruncated() {
        for (invalid in listOf("123abc", "12+3", "++123", "123\n45", "123456789012345678901")) {
            assertEquals("9", acceptPhoneDraft("9", invalid))
        }
        assertEquals("", acceptPhoneDraft("9", ""))
        assertEquals("+", acceptPhoneDraft("9", "+"))
    }
}
