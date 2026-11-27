@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.vowifi.sw.manager.data.LocalSocketEngineClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalSocketFrameLimitHostTest {

    private class PipeTestSocket(
        private val setSoTimeoutFailure: Throwable? = null,
        private val onRequest: (PipeTestSocket, String) -> Unit,
    ) : LocalSocket() {
        private val pipeIn = PipedInputStream(1024 * 1024)
        private val pipeOut = PipedOutputStream(pipeIn)
        @Volatile var closed = false
        private val out = object : OutputStream() {
            private val buf = ByteArrayOutputStream()
            override fun write(b: Int) {
                if (b == 10) {
                    val line = buf.toString(Charsets.UTF_8)
                    buf.reset()
                    onRequest(this@PipeTestSocket, line)
                } else {
                    buf.write(b)
                }
            }
        }

        override fun connect(endpoint: LocalSocketAddress) = Unit
        override fun setSoTimeout(timeout: Int) { setSoTimeoutFailure?.let { throw it } }
        override fun getInputStream(): InputStream = pipeIn
        override fun getOutputStream(): OutputStream = out
        override fun shutdownInput() { runCatching { pipeOut.close() } }
        override fun shutdownOutput() = Unit
        override fun close() {
            closed = true
            runCatching { pipeOut.close() }
            runCatching { pipeIn.close() }
        }

        fun writeRaw(bytes: ByteArray) {
            pipeOut.write(bytes)
            pipeOut.flush()
        }
    }

    @Test
    fun oversizedTerminatedResponseLineDisconnectsAndFailsRequest() = runBlocking {
        val sockets = CopyOnWriteArrayList<PipeTestSocket>()
        val client = LocalSocketEngineClient("/synthetic/oversized-line.sock") {
            PipeTestSocket { socket, line ->
                val req = Json.parseToJsonElement(line).jsonObject
                val id = req.getValue("id").jsonPrimitive.long
                val method = req.getValue("method").jsonPrimitive.content
                when (method) {
                    "hello" -> socket.writeRaw("""{"id":$id,"result":{"version":1}}""" .toByteArray() + byteArrayOf(10))
                    "big" -> {
                        val payload = "a".repeat(600 * 1024)
                        socket.writeRaw("""{"id":$id,"result":{"blob":"$payload"}}""".toByteArray() + byteArrayOf(10))
                    }
                }
            }.also { sockets += it }
        }
        try {
            var failure: Throwable? = null
            try {
                withTimeout(5_000) { client.request("big", timeoutMillis = 3_000) }
            } catch (error: Throwable) {
                failure = error
            }
            assertTrue("expected IOException for oversized response line, got $failure", failure is IOException)
            assertEquals(1, sockets.size)
            assertTrue("oversized response line left socket open", sockets[0].closed)
            assertNull(client.existingConnection())
        } finally {
            client.close()
        }
    }

    @Test
    fun oversizedUnterminatedResponseStreamDisconnectsPromptlyWithoutWaitingForTimeout() = runBlocking {
        val sockets = CopyOnWriteArrayList<PipeTestSocket>()
        val client = LocalSocketEngineClient("/synthetic/unterminated-flood.sock") {
            PipeTestSocket { socket, line ->
                val req = Json.parseToJsonElement(line).jsonObject
                val id = req.getValue("id").jsonPrimitive.long
                val method = req.getValue("method").jsonPrimitive.content
                when (method) {
                    "hello" -> socket.writeRaw("""{"id":$id,"result":{"version":1}}""".toByteArray() + byteArrayOf(10))
                    "flood" -> {
                        socket.writeRaw(ByteArray(600 * 1024) { 'x'.code.toByte() })
                    }
                }
            }.also { sockets += it }
        }
        try {
            val started = System.nanoTime()
            var failure: Throwable? = null
            try {
                withTimeout(5_000) { client.request("flood", timeoutMillis = 3_000) }
            } catch (error: Throwable) {
                failure = error
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("expected IOException immediately at frame cap, got $failure in ${elapsedMs}ms", failure is IOException)
            assertTrue("unterminated oversized stream waited ${elapsedMs}ms instead of failing promptly", elapsedMs < 1_500)
            assertTrue("oversized unterminated stream left socket open", sockets[0].closed)
            assertNull(client.existingConnection())
        } finally {
            client.close()
        }
    }

    @Test
    fun setSoTimeoutRuntimeFailureClosesSocketWithoutLeaking() = runBlocking {
        val sockets = CopyOnWriteArrayList<PipeTestSocket>()
        val client = LocalSocketEngineClient("/synthetic/set-timeout-fail.sock") {
            PipeTestSocket(setSoTimeoutFailure = IllegalArgumentException("synthetic invalid timeout")) { _, _ -> }
                .also { sockets += it }
        }
        try {
            var failure: Throwable? = null
            try {
                withTimeout(5_000) { client.connect() }
            } catch (error: Throwable) {
                failure = error
            }
            assertTrue("expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
            assertEquals(1, sockets.size)
            assertTrue("runtime setSoTimeout failure leaked open socket", sockets[0].closed)
        } finally {
            client.close()
        }
    }

    @Test
    fun normalSizedResponseUnderLimitStillSucceeds() = runBlocking {
        val client = LocalSocketEngineClient("/synthetic/normal-32k.sock") {
            PipeTestSocket { socket, line ->
                val req = Json.parseToJsonElement(line).jsonObject
                val id = req.getValue("id").jsonPrimitive.long
                val method = req.getValue("method").jsonPrimitive.content
                when (method) {
                    "hello" -> socket.writeRaw("""{"id":$id,"result":{"version":1}}""" .toByteArray() + byteArrayOf(13, 10))
                    "normal" -> {
                        val payload = "z".repeat(32 * 1024)
                        socket.writeRaw("""{"id":$id,"result":{"blob":"$payload"}}""".toByteArray() + byteArrayOf(13, 10))
                    }
                }
            }
        }
        try {
            val reply = withTimeout(5_000) { client.request("normal", timeoutMillis = 3_000) }
            assertNull(reply.error)
            assertEquals(32 * 1024, reply.result?.get("blob")?.jsonPrimitive?.content?.length)
        } finally {
            client.close()
        }
    }
}
