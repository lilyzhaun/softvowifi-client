package com.vowifi.sw.manager.data

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.EOFException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

internal class AndroidCallAudioSocket(
    private val socket: LocalSocket,
) : AutoCloseable {
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val writeLock = Any()
    private val decoder = PcmFrameCodec.decoder()
    private val readBuffer = ByteArray(PcmFrameCodec.FRAME_BYTES)
    private val closed = AtomicBoolean(false)

    fun writeFrame(frame: ByteArray) {
        synchronized(writeLock) {
            output.write(frame)
            output.flush()
        }
    }

    fun readPayloads(): List<ByteArray> {
        val n = input.read(readBuffer)
        if (n < 0) {
            decoder.finish()
            throw EOFException()
        }
        return decoder.push(readBuffer.copyOf(n))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }

    companion object {
        fun connect(iccid: String): AndroidCallAudioSocket {
            val connected = LocalSocket()
            try {
                connected.connect(
                    LocalSocketAddress(
                        "/data/local/tmp/vowifi/$iccid.audio.sock",
                        LocalSocketAddress.Namespace.FILESYSTEM,
                    ),
                )
                connected.setSoTimeout(0)
                return AndroidCallAudioSocket(connected)
            } catch (error: CancellationException) {
                runCatching { connected.close() }
                throw error
            } catch (_: Exception) {
                runCatching { connected.close() }
                throw AndroidCallAudioException()
            }
        }
    }
}
