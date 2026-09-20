package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PcmFrameCodecTest {
    @Test
    fun exposesT3bWireConstants() {
        assertEquals(644, PcmFrameCodec.FRAME_BYTES)
        assertEquals(640, PcmFrameCodec.PAYLOAD_BYTES)
        assertEquals(4, PcmFrameCodec.HEADER_BYTES)
        assertEquals(16_000, PcmFrameCodec.SAMPLE_RATE_HZ)
        assertEquals(1, PcmFrameCodec.CHANNEL_COUNT)
        assertEquals(320, PcmFrameCodec.SAMPLES_PER_FRAME)
    }

    @Test
    fun encodeWritesBigEndianLengthAndLittleEndianSamples() {
        val payload = signedPayload()

        val frame = PcmFrameCodec.encode(payload)

        assertEquals(644, frame.size)
        assertContentEquals(be32(644), frame.copyOfRange(0, 4))
        assertContentEquals(le16(0x1234), frame.copyOfRange(4, 6))
        assertContentEquals(le16(-1), frame.copyOfRange(6, 8))
        assertContentEquals(le16(32767), frame.copyOfRange(8, 10))
        assertContentEquals(le16(-32768), frame.copyOfRange(10, 12))
        assertContentEquals(le16(0x0102), frame.copyOfRange(4 + 160 * 2, 4 + 160 * 2 + 2))
        assertContentEquals(le16(0x7F00), frame.copyOfRange(4 + 319 * 2, 644))
        assertContentEquals(payload, frame.copyOfRange(4, 644))
    }

    @Test
    fun encodeRequiresExactly640PayloadBytes() {
        listOf(0, 639, 641, 644).forEach { size ->
            val error = assertFailsWith<PcmFrameException.PayloadSize> {
                PcmFrameCodec.encode(ByteArray(size))
            }
            assertEquals(size, error.size)
        }
    }

    @Test
    fun decoderSplitsHeaderAndPayloadAcrossPushes() {
        val payload = signedPayload()
        val frame = wire(payload)
        val decoder = PcmFrameCodec.decoder()

        assertTrue(decoder.push(frame.copyOfRange(0, 2)).isEmpty())
        assertTrue(decoder.push(frame.copyOfRange(2, 4)).isEmpty())
        assertTrue(decoder.push(frame.copyOfRange(4, 200)).isEmpty())
        val frames = decoder.push(frame.copyOfRange(200, 644))

        assertEquals(1, frames.size)
        assertContentEquals(payload, frames[0])
        decoder.finish()
    }

    @Test
    fun decoderEmitsConcatenatedFramesFromOnePush() {
        val first = payloadWith(0 to 0x1234.toShort())
        val second = payloadWith(0 to (-1).toShort(), 319 to 0x7F00.toShort())
        val decoder = PcmFrameCodec.decoder()

        val frames = decoder.push(wire(first) + wire(second))

        assertEquals(2, frames.size)
        assertContentEquals(first, frames[0])
        assertContentEquals(second, frames[1])
        decoder.finish()
    }

    @Test
    fun decoderRejectsTotalLength643() {
        assertRejectedLength(643)
    }

    @Test
    fun decoderRejectsTotalLength645() {
        assertRejectedLength(645)
    }

    @Test
    fun decoderRejectsTotalLength0() {
        assertRejectedLength(0)
    }

    @Test
    fun finishRejectsTruncatedLeftoverBytes() {
        val frame = wire(signedPayload())
        listOf(2, 4, 104, 643).forEach { size ->
            val decoder = PcmFrameCodec.decoder()
            assertTrue(decoder.push(frame.copyOfRange(0, size)).isEmpty())
            val error = assertFailsWith<PcmFrameException.Truncated> {
                decoder.finish()
            }
            assertEquals(size, error.leftoverBytes)
        }
    }

    @Test
    fun finishAcceptsExactFrameBoundary() {
        val decoder = PcmFrameCodec.decoder()
        val frames = decoder.push(wire(signedPayload()))

        assertEquals(1, frames.size)
        decoder.finish()
    }

    @Test
    fun captureWaitsForExactly640BytesBeforeEncode() {
        val payload = signedPayload()
        val capture = PcmFrameCodec.Capture()

        assertTrue(capture.push(payload.copyOfRange(0, 200)).isEmpty())
        assertTrue(capture.push(payload, 200, 439).isEmpty())
        val frames = capture.push(payload, 639, 1)

        assertEquals(1, frames.size)
        assertContentEquals(PcmFrameCodec.encode(payload), frames[0])
        assertTrue(capture.push(payload.copyOfRange(0, 100)).isEmpty())
    }

    @Test
    fun captureEmitsConcatenatedFramesFromOnePush() {
        val first = payloadWith(0 to 0x1234.toShort())
        val second = payloadWith(0 to (-1).toShort(), 319 to 0x7F00.toShort())
        val capture = PcmFrameCodec.Capture()

        val frames = capture.push(first + second.copyOfRange(0, 100) + ByteArray(0))
        assertEquals(1, frames.size)
        assertContentEquals(PcmFrameCodec.encode(first), frames[0])

        val rest = capture.push(second.copyOfRange(100, 640))
        assertEquals(1, rest.size)
        assertContentEquals(PcmFrameCodec.encode(second), rest[0])
    }

    @Test
    fun writeFullyConsumesPartialWrites() {
        val payload = signedPayload()
        val seen = ArrayList<Int>()

        val ok = PcmFrameCodec.writeFully(payload) { _, _, length ->
            val n = minOf(length, 200)
            seen += n
            n
        }

        assertTrue(ok)
        assertEquals(listOf(200, 200, 200, 40), seen)
    }

    @Test
    fun writeFullyStopsOnNonPositiveWrite() {
        val payload = signedPayload()
        assertFalse(PcmFrameCodec.writeFully(payload) { _, _, _ -> 0 })
        assertFalse(PcmFrameCodec.writeFully(payload) { _, _, _ -> -1 })
    }
}

private fun assertRejectedLength(length: Int) {
    val decoder = PcmFrameCodec.decoder()
    val error = assertFailsWith<PcmFrameException.FrameLength> {
        decoder.push(be32(length))
    }
    assertEquals(length.toLong(), error.length)
}

private fun signedPayload(): ByteArray = payloadWith(
    0 to 0x1234.toShort(),
    1 to (-1).toShort(),
    2 to 32767.toShort(),
    3 to (-32768).toShort(),
    160 to 0x0102.toShort(),
    319 to 0x7F00.toShort(),
)

private fun payloadWith(vararg samples: Pair<Int, Short>): ByteArray {
    val payload = ByteArray(640)
    samples.forEach { (index, sample) ->
        val value = sample.toInt()
        payload[index * 2] = value.toByte()
        payload[index * 2 + 1] = (value shr 8).toByte()
    }
    return payload
}

private fun wire(payload: ByteArray): ByteArray = be32(644) + payload

private fun be32(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(),
    (value ushr 16).toByte(),
    (value ushr 8).toByte(),
    value.toByte(),
)

private fun le16(sample: Int): ByteArray = byteArrayOf(
    sample.toByte(),
    (sample shr 8).toByte(),
)
