package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MessageOwnerRemarkTest {
    @Test fun trimsAndClearsWhitespace() {
        assertEquals("家用", MessageOwnerRemark.parse("  家用  ").value)
        assertEquals("", MessageOwnerRemark.parse("   ").value)
    }

    @Test fun countsUnicodeCodePointsRatherThanUtf16Units() {
        val input = "\uD83D\uDE00".repeat(40)
        assertEquals(input, MessageOwnerRemark.parse(input).value)
        assertFailsWith<InvalidMessageOwnerRemark> { MessageOwnerRemark.parse(input + "中") }
    }

    @Test fun rejectsControlsEvenAtTrimmedEdges() {
        listOf("\n家", "家\t", "\u007f", "\u0085").forEach { input ->
            assertFailsWith<InvalidMessageOwnerRemark> { MessageOwnerRemark.parse(input) }
        }
    }
}
