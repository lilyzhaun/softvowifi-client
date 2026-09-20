package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals

class SmsEventTimeTest {
    @Test
    fun nonPositiveAndOverflowSecondsFallBackToClock() {
        assertEquals(NOW_MS, SmsEventTime.millis(null, NOW_MS))
        assertEquals(NOW_MS, SmsEventTime.millis(0L, NOW_MS))
        assertEquals(NOW_MS, SmsEventTime.millis(-1L, NOW_MS))
        assertEquals(NOW_MS, SmsEventTime.millis(Long.MAX_VALUE, NOW_MS))
        assertEquals(NOW_MS, SmsEventTime.millis((Long.MAX_VALUE / 1_000L) + 1L, NOW_MS))
    }

    @Test
    fun positiveInRangeSecondsConvertToMillis() {
        assertEquals(1_755_000_000_000L, SmsEventTime.millis(1_755_000_000L, NOW_MS))
        assertEquals(1_000L, SmsEventTime.millis(1L, NOW_MS))
        assertEquals(Long.MAX_VALUE / 1_000L * 1_000L, SmsEventTime.millis(Long.MAX_VALUE / 1_000L, NOW_MS))
    }
}

private const val NOW_MS = 1_700_000_000_000L
