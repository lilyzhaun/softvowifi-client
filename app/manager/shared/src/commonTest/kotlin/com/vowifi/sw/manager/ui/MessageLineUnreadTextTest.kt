package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageLineUnreadTextTest {
    @Test fun onlyPositiveKnownCountsAreVisibleAndLargeCountsUseTheExistingCap() {
        assertNull(messageLineUnreadText(null))
        assertNull(messageLineUnreadText(0))
        assertEquals("1 条未读", messageLineUnreadText(1))
        assertEquals("99 条未读", messageLineUnreadText(99))
        assertEquals("99+ 条未读", messageLineUnreadText(100))
        assertEquals("99+ 条未读", messageLineUnreadText(3_000_000_000))
    }
}
