package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallRingbackPolicyTest {
    @Test fun zeroPcmIsNotAnAudibleNetworkRingback() {
        assertFalse(hasRingbackSuppressingPcm(ByteArray(PcmFrameCodec.PAYLOAD_BYTES)))
        assertFalse(hasRingbackSuppressingPcm(byteArrayOf()))
    }

    @Test fun evenQuietNonzeroPcmKeepsNetworkAnnouncementPriority() {
        val pcm = ByteArray(PcmFrameCodec.PAYLOAD_BYTES)
        pcm[0] = 1
        assertTrue(hasRingbackSuppressingPcm(pcm))
        pcm[0] = 0
        pcm[pcm.lastIndex] = 0x80.toByte()
        assertTrue(hasRingbackSuppressingPcm(pcm))
    }

    @Test fun receivedMediaSuppressesToneButAnActualGapRestoresIt() {
        assertFalse(shouldPlayLocalRingback(false, null, 0))
        assertTrue(shouldPlayLocalRingback(true, null, 0))
        assertFalse(shouldPlayLocalRingback(true, 100, 120))
        assertTrue(shouldPlayLocalRingback(true, 100, 1_500))
        assertFalse(shouldPlayLocalRingback(true, 1_600, 1_620))
        assertFalse(shouldPlayLocalRingback(false, 100, 1_500))
    }
}
