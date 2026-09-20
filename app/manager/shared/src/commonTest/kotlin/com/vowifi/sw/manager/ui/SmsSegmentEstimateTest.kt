package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmsSegmentEstimateTest {
    @Test fun basicAndExtensionUnitsRespectEncoding() {
        assertEquals(SmsSegmentEstimate(SmsEncoding.GSM7, 5, 1, false), estimateSmsSegments("hello"))
        assertEquals(SmsSegmentEstimate(SmsEncoding.GSM7, 20, 1, false), estimateSmsSegments("\u000c^{}\\[]~|€"))
        assertEquals(SmsSegmentEstimate(SmsEncoding.UTF16, 6, 1, false), estimateSmsSegments("hello测"))
    }
    @Test fun goldenEngineSegmentCounts() {
        listOf("A".repeat(200) to 2, "测".repeat(200) to 3, "A".repeat(1000) to 7,
            "测".repeat(1000) to 15, "😀".repeat(35) to 1, "😀".repeat(36) to 2,
            ("A".repeat(152) + "€" + "A".repeat(152)) to 3,
            ("测".repeat(66) + "😀" + "测".repeat(66)) to 3).forEach { (body, count) ->
            assertEquals(count, estimateSmsSegments(body)?.segments)
        }
    }
    @Test fun boundariesAndMalformedUtf16StayAdvisory() {
        assertNull(estimateSmsSegments(" \n"))
        assertEquals(1, estimateSmsSegments("A".repeat(160))?.segments)
        assertEquals(2, estimateSmsSegments("A".repeat(161))?.segments)
        assertEquals(70, estimateSmsSegments("😀".repeat(35))?.units)
        assertTrue(requireNotNull(estimateSmsSegments("\uD83D")).approximate)
        assertTrue(requireNotNull(estimateSmsSegments("测".repeat(2000))).segments > 16)
    }
}
