package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class IccidNormalizerTest {
    @Test
    fun normalizeRejectsNullEmptyAndAllFiller() {
        assertNull(IccidNormalizer.normalize(null))
        assertNull(IccidNormalizer.normalize(""))
        assertNull(IccidNormalizer.normalize("F"))
        assertNull(IccidNormalizer.normalize("f"))
        assertNull(IccidNormalizer.normalize("FFFFFFFFFFFFFFFFFFFF"))
        assertNull(IccidNormalizer.normalize("ffffffffffffffff"))
        assertNull(IccidNormalizer.normalize("FfFf"))
    }

    @Test
    fun normalizeLeavesAlreadyValidMinAndMaxDigitIccidsUnchanged() {
        assertSame(DIGITS_15, IccidNormalizer.normalize(DIGITS_15))
        assertSame(DIGITS_19, IccidNormalizer.normalize(DIGITS_19))
        assertSame(DIGITS_22, IccidNormalizer.normalize(DIGITS_22))
    }

    @Test
    fun normalizeStripsFlip2BinderTerminalFillerToNineteenDigits() {
        val flip2BinderIccid = DIGITS_19 + "F"

        val normalized = IccidNormalizer.normalize(flip2BinderIccid)

        assertEquals(20, flip2BinderIccid.length)
        assertEquals(70, flip2BinderIccid[19].code)
        assertEquals(DIGITS_19, normalized)
        assertEquals(19, normalized?.length)
        assertNull(IccidNormalizer.normalize(flip2BinderIccid + " "))
    }

    @Test
    fun normalizeStripsMixedMultipleTrailingFillerCaseInsensitively() {
        assertEquals(DIGITS_19, IccidNormalizer.normalize(DIGITS_19 + "f"))
        assertEquals(DIGITS_19, IccidNormalizer.normalize(DIGITS_19 + "Ff"))
        assertEquals(DIGITS_19, IccidNormalizer.normalize(DIGITS_19 + "fFfF"))
        assertEquals(DIGITS_15, IccidNormalizer.normalize(DIGITS_15 + "FFf"))
        assertEquals(DIGITS_22, IccidNormalizer.normalize(DIGITS_22 + "f"))
    }

    @Test
    fun normalizeRejectsInternalFillerWhitespaceAndOtherNonDigits() {
        assertNull(IccidNormalizer.normalize("11111111111111F1111"))
        assertNull(IccidNormalizer.normalize("111111111111111f111"))
        assertNull(IccidNormalizer.normalize("F$DIGITS_19"))
        assertNull(IccidNormalizer.normalize(" $DIGITS_19"))
        assertNull(IccidNormalizer.normalize("$DIGITS_19 "))
        assertNull(IccidNormalizer.normalize("\t$DIGITS_19"))
        assertNull(IccidNormalizer.normalize("$DIGITS_19\n"))
        assertNull(IccidNormalizer.normalize("$DIGITS_19 A"))
        assertNull(IccidNormalizer.normalize(DIGITS_19 + "A"))
        assertNull(IccidNormalizer.normalize(DIGITS_19 + "a"))
    }

    @Test
    fun normalizeRejectsFourteenAndTwentyThreeDigitLengthsAfterFillerStrip() {
        assertNull(IccidNormalizer.normalize(DIGITS_14))
        assertNull(IccidNormalizer.normalize(DIGITS_14 + "F"))
        assertNull(IccidNormalizer.normalize(DIGITS_23))
        assertNull(IccidNormalizer.normalize(DIGITS_23 + "F"))
        assertNull(IccidNormalizer.normalize(DIGITS_23 + "fF"))
    }

    @Test
    fun toStringDoesNotExposeInputOrHoldIdentityState() {
        IccidNormalizer.normalize(DIGITS_19 + "F")

        val text = IccidNormalizer.toString()

        assertEquals("IccidNormalizer", text)
        assertFalse(text.contains(DIGITS_19))
        assertFalse(text.contains("F"))
        assertPrivate(text)
    }
}

private const val DIGITS_14 = "11111111111111"
private const val DIGITS_15 = "111111111111111"
private const val DIGITS_19 = "1111111111111111111"
private const val DIGITS_22 = "1111111111111111111111"
private const val DIGITS_23 = "11111111111111111111111"
