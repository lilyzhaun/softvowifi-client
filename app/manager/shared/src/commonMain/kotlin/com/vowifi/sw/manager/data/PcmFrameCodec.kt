package com.vowifi.sw.manager.data

sealed class PcmFrameException(message: String) : IllegalArgumentException(message) {
    class PayloadSize(val size: Int) : PcmFrameException("pcm payload size: $size")
    class FrameLength(val length: Long) : PcmFrameException("pcm frame length: $length")
    class Truncated(val leftoverBytes: Int) : PcmFrameException("pcm truncated frame: $leftoverBytes")
}

object PcmFrameCodec {
    const val FRAME_BYTES = 644
    const val PAYLOAD_BYTES = 640
    const val HEADER_BYTES = 4
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNEL_COUNT = 1
    const val SAMPLES_PER_FRAME = PAYLOAD_BYTES / 2

    fun encode(payload: ByteArray): ByteArray {
        if (payload.size != PAYLOAD_BYTES) {
            throw PcmFrameException.PayloadSize(payload.size)
        }
        val frame = ByteArray(FRAME_BYTES)
        writeBe32(frame, FRAME_BYTES)
        payload.copyInto(frame, HEADER_BYTES)
        return frame
    }

    fun writeFully(payload: ByteArray, write: (ByteArray, Int, Int) -> Int): Boolean {
        var offset = 0
        while (offset < payload.size) {
            val written = write(payload, offset, payload.size - offset)
            if (written <= 0) return false
            offset += written
        }
        return true
    }

    fun decoder(): Decoder = Decoder()

    class Capture {
        private val pending = ByteArray(PAYLOAD_BYTES)
        private var filled = 0

        fun push(data: ByteArray, offset: Int = 0, length: Int = data.size): List<ByteArray> {
            val frames = ArrayList<ByteArray>()
            var pos = offset
            var remaining = length
            while (remaining > 0) {
                val take = minOf(PAYLOAD_BYTES - filled, remaining)
                data.copyInto(pending, filled, pos, pos + take)
                filled += take
                pos += take
                remaining -= take
                if (filled == PAYLOAD_BYTES) {
                    frames += encode(pending)
                    filled = 0
                }
            }
            return frames
        }
    }

    class Decoder {
        private var buffer = ByteArray(0)

        fun push(chunk: ByteArray): List<ByteArray> {
            if (chunk.isNotEmpty()) {
                buffer += chunk
            }
            val payloads = ArrayList<ByteArray>()
            while (buffer.size >= HEADER_BYTES) {
                val length = readBe32(buffer)
                if (length != FRAME_BYTES.toLong()) {
                    throw PcmFrameException.FrameLength(length)
                }
                if (buffer.size < FRAME_BYTES) {
                    break
                }
                payloads += buffer.copyOfRange(HEADER_BYTES, FRAME_BYTES)
                buffer = drop(buffer, FRAME_BYTES)
            }
            return payloads
        }

        fun finish() {
            if (buffer.isNotEmpty()) {
                throw PcmFrameException.Truncated(buffer.size)
            }
        }
    }
}

private fun writeBe32(target: ByteArray, value: Int) {
    target[0] = (value ushr 24).toByte()
    target[1] = (value ushr 16).toByte()
    target[2] = (value ushr 8).toByte()
    target[3] = value.toByte()
}

private fun readBe32(source: ByteArray): Long {
    return ((source[0].toLong() and 0xFFL) shl 24) or
        ((source[1].toLong() and 0xFFL) shl 16) or
        ((source[2].toLong() and 0xFFL) shl 8) or
        (source[3].toLong() and 0xFFL)
}

private fun drop(source: ByteArray, count: Int): ByteArray {
    return if (count == source.size) ByteArray(0) else source.copyOfRange(count, source.size)
}
