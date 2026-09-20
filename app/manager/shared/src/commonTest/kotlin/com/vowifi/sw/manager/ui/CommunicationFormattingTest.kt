package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommunicationFormattingTest {
    private val localDate: (Long) -> String = { "8月24日" }

    @Test
    fun relativeTimeUsesBoundedHumanLabelsWithoutRawEpoch() {
        val now = 2_000_000_000_000L

        assertEquals("刚刚", formatCommunicationTime(now - 30_000L, now, localDate))
        assertEquals("5 分钟前", formatCommunicationTime(now - 5 * 60_000L, now, localDate))
        assertEquals("3 小时前", formatCommunicationTime(now - 3 * 3_600_000L, now, localDate))
        assertEquals("8月24日", formatCommunicationTime(now - 2 * 86_400_000L, now, localDate))
    }

    @Test
    fun invalidAndFutureTimesNeverExposeRawEpoch() {
        val now = 2_000_000_000_000L
        val values = listOf(-1L, 0L, now + 1L, Long.MAX_VALUE)

        values.forEach { value ->
            val display = formatCommunicationTime(value, now, localDate)
            assertEquals("时间未知", display)
            assertFalse(display.contains(value.toString()))
        }
        assertEquals("时间未知", formatCommunicationTime(now - 86_400_000L, now) { "\u0000/private/path" })
    }

    @Test
    fun privateUiPhoneDisplayShowsNormalizedNumbersAndRejectsUntrustedIdentities() {
        assertEquals("+15550123", privateUiPhoneDisplay("+15550123"))
        assertEquals("15550123", privateUiPhoneDisplay("15550123"))
        assertEquals("7", privateUiPhoneDisplay("7"))
        assertEquals("+7", privateUiPhoneDisplay("+7"))
        assertEquals("12345678901234567890", privateUiPhoneDisplay("12345678901234567890"))
        assertEquals("+12345678901234567890", privateUiPhoneDisplay("+12345678901234567890"))
        assertEquals("未知号码", privateUiPhoneDisplay(null))

        listOf(
            "anonymous" to "anonymous",
            "anonymous-case" to "ANONYMOUS",
            "sip" to "sip:user@example.invalid",
            "sips" to "sips:user@example.invalid",
            "raw" to "raw:15550123",
            "tel" to "tel:+15550123",
            "control" to "12\u000034",
            "plus-only" to "+",
            "empty" to "",
            "plain-21-digits" to "123456789012345678901",
            "plus-21-digits" to "+123456789012345678901",
        ).forEach { (caseName, raw) ->
            assertEquals("未知号码", privateUiPhoneDisplay(raw), caseName)
        }
    }

    @Test
    fun boundedExternalTextDropsSensitiveMarkersControlsAndOverlongContent() {
        assertEquals("短消息", boundedCommunicationText("  短消息  ", "内容不可显示", 12))
        assertEquals("内容不可显示", boundedCommunicationText("owner=sim-1 token=secret", "内容不可显示", 24))
        assertEquals("内容不可显示", boundedCommunicationText("sip:user@example.invalid", "内容不可显示", 24))
        assertEquals("内容不可显示", boundedCommunicationText("epoch 1700000000000", "内容不可显示", 24))
        assertEquals("一二三四五六七…", boundedCommunicationText("一二三四五六七八九", "内容不可显示", 8))
        assertTrue(boundedCommunicationText("a\nb", "内容不可显示", 8).contains(" "))
    }
}
