package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationalLogDetailTest {
    @Test fun detailKeepsSanitizedContentBeyondTheSummary() {
        val message = "safe ".repeat(40) + "password=synthetic-password token=synthetic-token 完整结束"
        val entry = EngineOperationalLogParser().parse("2026-09-11 12:00:00.000 [INFO] [ims] [0001] $message").entries.single()
        assertTrue(entry.safeSummary.length <= 160)
        assertTrue(entry.safeDetail.endsWith("完整结束"), "details must not be the 160-character summary")
        assertFalse(entry.safeDetail.contains("synthetic-password"))
        assertFalse(entry.safeDetail.contains("synthetic-token"))
        assertFalse(entry.detailTruncated)
    }

    @Test fun detailRedactsBeforeClippingAndNeverSplitsSurrogatePairs() {
        val entry = EngineOperationalLogParser().parse(
            "2026-09-11 12:00:00.000 [INFO] [ims] [0001] " + "界😀".repeat(900) + " token=synthetic-tail-secret",
        ).entries.single()
        assertTrue(entry.detailTruncated)
        assertTrue(entry.safeDetail.length <= 2048)
        assertTrue(entry.safeDetail.endsWith("…"))
        assertFalse(entry.safeDetail.dropLast(1).last().isHighSurrogate())
    }

    @Test fun parserPreservesSourceOrder() {
        val entries = EngineOperationalLogParser().parse(
            "2026-09-11 12:00:00.000 [INFO] [ims] [0001] earlier\n" +
                "2026-09-11 12:00:01.000 [INFO] [ims] [0001] later",
        ).entries
        assertEquals(listOf("earlier", "later"), entries.map { it.safeSummary })
    }
}
