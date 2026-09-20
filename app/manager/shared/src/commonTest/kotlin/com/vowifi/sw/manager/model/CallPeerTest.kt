package com.vowifi.sw.manager.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallPeerTest {
    @Test
    fun parseAcceptsOptionalPlusAndOneToTwentyDigits() {
        val plain = CallPeer.parse(SYNTH_PLAIN)
        val plus = CallPeer.parse(SYNTH_PLUS)
        val twenty = CallPeer.parse(DIGITS_20)
        val plusTwenty = CallPeer.parse("+$DIGITS_20")

        assertNotNull(plain)
        assertNotNull(plus)
        assertNotNull(twenty)
        assertNotNull(plusTwenty)
        assertEquals(SYNTH_PLAIN, plain.text())
        assertEquals(SYNTH_PLUS, plus.text())
        assertEquals(DIGITS_20, twenty.text())
        assertEquals("+$DIGITS_20", plusTwenty.text())
        assertNotEquals(plain, plus)
        assertEquals(plain, CallPeer.parse(SYNTH_PLAIN))
    }

    @Test
    fun parseRejectsSipTelRawControlAlphaAndOverlong() {
        listOf(
            "sip:alice@example.invalid",
            "sips:alice@example.invalid",
            "tel:+15550001",
            "raw:15551212",
            "raw:+15550001",
            "",
            "+",
            "++15550001",
            " 15551212",
            "15551212 ",
            "1555 1212",
            "peer1555",
            "1555a1212",
            "１２３",
            "\u000115551212",
            "1555\n1212",
            DIGITS_21,
            "+$DIGITS_21",
        ).forEach { raw ->
            assertNull(CallPeer.parse(raw), raw)
        }
    }

    @Test
    fun publicToStringNeverExposesPeerDigits() {
        val peer = requireNotNull(CallPeer.parse(SYNTH_PLUS))

        assertEquals("CallPeer", peer.toString())
        assertFalse(peer.toString().contains(SYNTH_PLUS))
        assertFalse(peer.toString().contains(SYNTH_PLUS.removePrefix("+")))
        assertTrue(peer.text() == SYNTH_PLUS)
    }
}

private const val SYNTH_PLAIN = "15551212"
private const val SYNTH_PLUS = "+15550001"
private const val DIGITS_20 = "12345678901234567890"
private const val DIGITS_21 = "123456789012345678901"
