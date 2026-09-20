package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals

class PeerNumberNormalizerTest {
    @Test
    fun optionalSingleLeadingPlusProducesTheSameConversationKey() {
        assertEquals("456", PeerNumberNormalizer.normalize("456"))
        assertEquals("456", PeerNumberNormalizer.normalize("+456"))
    }

    @Test
    fun validAsciiDigitsPreserveLengthAndLeadingZeros() {
        assertEquals("0", PeerNumberNormalizer.normalize("0"))
        assertEquals("0456", PeerNumberNormalizer.normalize("0456"))
        assertEquals(DIGITS_20, PeerNumberNormalizer.normalize("+$DIGITS_20"))
    }

    @Test
    fun invalidPeersUseExactValueIsolationKeys() {
        val invalidPeers = listOf(
            "",
            "+",
            " 456",
            "456 ",
            "45 6",
            "peer456",
            "++456",
            "+ +456",
            "１２３",
            DIGITS_21,
        )

        invalidPeers.forEach { peer ->
            assertEquals("raw:$peer", PeerNumberNormalizer.normalize(peer), peer)
        }
    }
}

private const val DIGITS_20 = "12345678901234567890"
private const val DIGITS_21 = "123456789012345678901"
