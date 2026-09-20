package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.SubscriptionDiscovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SubscriptionDiscoverySupportTest {
    @Test
    fun prefersCarrierNameThenDisplayName() {
        assertEquals("alpha", sanitizeCarrierLabel("alpha", "beta"))
        assertEquals("beta", sanitizeCarrierLabel(null, "beta"))
        assertEquals("beta", sanitizeCarrierLabel("  ", "beta"))
        assertEquals("beta", sanitizeCarrierLabel("\u0001\u0002", "  beta  "))
    }

    @Test
    fun trimsAndRemovesIsoControlCharacters() {
        assertEquals("alpha", sanitizeCarrierLabel("\u0000\u0001  alpha\u001F\u007F  ", "beta"))
        assertEquals("alpha", sanitizeCarrierLabel("\talpha\n", "beta"))
    }

    @Test
    fun fallsBackWhenBothSourcesBlank() {
        assertEquals(FALLBACK_LABEL, sanitizeCarrierLabel(null, null))
        assertEquals(FALLBACK_LABEL, sanitizeCarrierLabel("  ", "\t"))
        assertEquals(FALLBACK_LABEL, sanitizeCarrierLabel("\u0000", ""))
    }

    @Test
    fun rejectsFifteenOrLongerDigitRunsAndAllowsFourteen() {
        assertEquals("beta", sanitizeCarrierLabel(DIGITS_15, "beta"))
        assertEquals("beta", sanitizeCarrierLabel("alpha $DIGITS_15", "beta"))
        assertEquals("beta", sanitizeCarrierLabel(DIGITS_22, "beta"))
        assertEquals("beta", sanitizeCarrierLabel(DIGITS_23, "beta"))
        assertEquals(FALLBACK_LABEL, sanitizeCarrierLabel(null, DIGITS_15))
        assertEquals(FALLBACK_LABEL, sanitizeCarrierLabel(DIGITS_15, DIGITS_22))
        assertEquals(DIGITS_14, sanitizeCarrierLabel(DIGITS_14, "beta"))
        assertEquals("alpha $DIGITS_14", sanitizeCarrierLabel("alpha $DIGITS_14", "beta"))
        assertEquals(DIGITS_14, sanitizeCarrierLabel(DIGITS_14, DIGITS_15))
    }

    @Test
    fun unsafeCarrierNameFallsBackToSafeDisplayName() {
        assertEquals("beta", sanitizeCarrierLabel(DIGITS_15, "beta"))
        assertEquals("beta", matchedSubscription(DIGITS_15, "beta", 0, "001", "01").carrierLabel)
    }

    @Test
    fun capsLabelToThirtyTwoKotlinChars() {
        val raw = "abcdefghijklmnopqrstuvwxyz0123456789"
        val capped = sanitizeCarrierLabel(raw, "beta")

        assertEquals(32, capped.length)
        assertEquals(raw.take(32), capped)
    }

    @Test
    fun validatesMccMncAndSlotTables() {
        listOf(
            "001" to "001",
            "310" to "310",
            "01" to null,
            "0001" to null,
            "00a" to null,
            "" to null,
            null to null,
        ).forEach { (input, expected) ->
            assertEquals(expected, validatedMcc(input), "mcc $input")
        }
        listOf(
            "01" to "01",
            "001" to "001",
            "260" to "260",
            "1" to null,
            "0001" to null,
            "0a" to null,
            "" to null,
            null to null,
        ).forEach { (input, expected) ->
            assertEquals(expected, validatedMnc(input), "mnc $input")
        }
        listOf(
            0 to 0,
            3 to 3,
            7 to 7,
            -1 to null,
            8 to null,
            99 to null,
        ).forEach { (input, expected) ->
            assertEquals(expected, validatedSlotIndex(input), "slot $input")
        }
    }

    @Test
    fun factorySanitizesAndValidatesMatchedFields() {
        val matched = matchedSubscription("  alpha\u0001  ", "beta", 0, "001", "01")
        val unsafe = matchedSubscription(DIGITS_15, "beta", 8, "1", "abcd")

        assertEquals("alpha", matched.carrierLabel)
        assertEquals(0, matched.slotIndex)
        assertEquals("001", matched.mcc)
        assertEquals("01", matched.mnc)
        assertEquals("beta", unsafe.carrierLabel)
        assertNull(unsafe.slotIndex)
        assertNull(unsafe.mcc)
        assertNull(unsafe.mnc)
    }

    @Test
    fun summariesCoverAllStatesAndOmitMissingSegments() {
        val full = matchedSubscription("alpha", null, 0, "001", "01")
        val noSlot = SubscriptionDiscovery.Matched("alpha", null, "001", "01")
        val noPlmn = matchedSubscription("alpha", null, 2, null, "01")
        val labelOnly = matchedSubscription("alpha", null, -1, null, null)
        val invalidSlot = SubscriptionDiscovery.Matched("alpha", 9, "001", "01")
        val onlyMcc = SubscriptionDiscovery.Matched("alpha", 1, "001", null)
        val cases = listOf(
            SubscriptionDiscovery.NotAttempted to null,
            SubscriptionDiscovery.Missing to "缺少元数据",
            SubscriptionDiscovery.Unavailable to "不可用",
            full to "SIM 1 · alpha · 001 / 01",
            noSlot to "alpha · 001 / 01",
            noPlmn to "SIM 3 · alpha",
            labelOnly to "alpha",
            invalidSlot to "alpha · 001 / 01",
            onlyMcc to "SIM 2 · alpha",
        )

        cases.forEach { (discovery, expected) ->
            val actual = systemSubscriptionSummary(discovery)
            assertEquals(expected, actual, discovery.toString())
            if (actual != null) {
                assertPrivate(actual)
                assertFalse(actual.contains("["), actual)
                assertFalse(actual.contains("]"), actual)
            }
        }
        assertPrivate(full.carrierLabel)
    }
}

private const val FALLBACK_LABEL = "已匹配订阅"
private const val DIGITS_14 = "12345678901234"
private const val DIGITS_15 = "123456789012345"
private const val DIGITS_22 = "1234567890123456789012"
private const val DIGITS_23 = "12345678901234567890123"
