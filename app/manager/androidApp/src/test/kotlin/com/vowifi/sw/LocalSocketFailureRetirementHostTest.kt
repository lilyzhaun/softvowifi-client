@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.LocalSocketEngineClient
import com.vowifi.sw.manager.data.ProtocolVersionMismatchException
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalSocketFailureRetirementHostTest {

    @Test
    fun handshakeVersionMismatchRetiresSocketAndReconnectsFreshSocket() = runBlocking {
        val sockets = CopyOnWriteArrayList<CallTestSocket>()
        val created = AtomicInteger(0)
        val client = LocalSocketEngineClient("/synthetic/version-mismatch.sock") {
            val idx = created.incrementAndGet()
            CallTestSocket { socket, line ->
                val req = Json.parseToJsonElement(line).jsonObject
                val id = req.getValue("id").jsonPrimitive.long
                val method = req.getValue("method").jsonPrimitive.content
                socket.methods += method
                when {
                    idx == 1 && method == "hello" -> socket.respond(id, buildJsonObject { put("version", 99) })
                    method == "hello" -> socket.respond(id, buildJsonObject { put("version", 1) })
                    method == "ping" -> socket.respond(id, buildJsonObject { put("pong", true) })
                }
            }.also { sockets += it }
        }
        try {
            var mismatch: Throwable? = null
            try {
                withTimeout(5_000) { client.connect() }
            } catch (error: Throwable) {
                mismatch = error
            }
            assertTrue("expected ProtocolVersionMismatchException, got $mismatch", mismatch is ProtocolVersionMismatchException)
            assertEquals(1, sockets.size)
            assertTrue("version-mismatched handshake leaked open socket", sockets[0].closed)
            assertNull(client.existingConnection())

            val reply = withTimeout(5_000) { client.request("ping") }
            assertNull(reply.error)
            assertEquals("next connect reused mismatched socket instead of opening fresh socket", 2, sockets.size)
            assertEquals(listOf("hello", "ping"), sockets[1].methods.toList())
        } finally {
            client.close()
        }
    }

    @Test
    fun handshakeCancellationRetiresSocketAndReconnectsFreshSocket() = runBlocking {
        val sockets = CopyOnWriteArrayList<CallTestSocket>()
        val created = AtomicInteger(0)
        val firstHelloReceived = CountDownLatch(1)
        val client = LocalSocketEngineClient("/synthetic/hello-cancel.sock") {
            val idx = created.incrementAndGet()
            CallTestSocket { socket, line ->
                val req = Json.parseToJsonElement(line).jsonObject
                val id = req.getValue("id").jsonPrimitive.long
                val method = req.getValue("method").jsonPrimitive.content
                socket.methods += method
                when {
                    idx == 1 && method == "hello" -> firstHelloReceived.countDown()
                    method == "hello" -> socket.respond(id, buildJsonObject { put("version", 1) })
                    method == "ping" -> socket.respond(id, buildJsonObject { put("pong", true) })
                }
            }.also { sockets += it }
        }
        try {
            val connecting = async(Dispatchers.Default) {
                try {
                    client.connect()
                    null
                } catch (error: Throwable) {
                    error
                }
            }
            assertTrue(firstHelloReceived.await(2, TimeUnit.SECONDS))
            connecting.cancel()
            val result = withTimeout(2_000) {
                try {
                    connecting.await()
                } catch (cancelled: CancellationException) {
                    cancelled
                }
            }
            assertTrue("expected CancellationException, got $result", result is CancellationException)
            assertEquals(1, sockets.size)
            assertTrue("cancelled handshake leaked open socket", sockets[0].closed)

            val reply = withTimeout(5_000) { client.request("ping") }
            assertNull(reply.error)
            assertEquals("next request reused cancelled handshake socket", 2, sockets.size)
            assertEquals(listOf("hello", "ping"), sockets[1].methods.toList())
        } finally {
            client.close()
        }
    }

    @Test
    fun requestWriteIOExceptionRetiresSocketSoNextRequestReconnects() = runBlocking {
        val sockets = CopyOnWriteArrayList<CallTestSocket>()
        val created = AtomicInteger(0)
        val client = LocalSocketEngineClient("/synthetic/request-write-fail.sock") {
            val idx = created.incrementAndGet()
            CallTestSocket { socket, line ->
                val req = Json.parseToJsonElement(line).jsonObject
                val id = req.getValue("id").jsonPrimitive.long
                val method = req.getValue("method").jsonPrimitive.content
                socket.methods += method
                when {
                    method == "hello" -> socket.respond(id, buildJsonObject { put("version", 1) })
                    idx == 1 && method == "ping" -> throw IOException("synthetic broken pipe during request write")
                    method == "ping" -> socket.respond(id, buildJsonObject { put("pong", true) })
                }
            }.also { sockets += it }
        }
        try {
            var failure: Throwable? = null
            try {
                withTimeout(5_000) { client.request("ping") }
            } catch (error: Throwable) {
                failure = error
            }
            assertTrue("expected IOException, got $failure", failure is IOException)
            assertEquals(1, sockets.size)
            assertTrue("request write IOException left corrupted socket open", sockets[0].closed)
            assertNull("existingConnection() exposed socket after write IOException", client.existingConnection())

            val reply = withTimeout(5_000) { client.request("ping") }
            assertNull(reply.error)
            assertEquals("subsequent request reused corrupted socket", 2, sockets.size)
            assertEquals(listOf("hello", "ping"), sockets[1].methods.toList())
        } finally {
            client.close()
        }
    }

    @Test
    fun messageConnectionWriteIOExceptionRetiresSocketAndInvalidatesConnection() = runBlocking {
        val sockets = CopyOnWriteArrayList<CallTestSocket>()
        val client = LocalSocketEngineClient("/synthetic/message-write-fail.sock") {
            CallTestSocket { socket, line ->
                val req = Json.parseToJsonElement(line).jsonObject
                val id = req.getValue("id").jsonPrimitive.long
                val method = req.getValue("method").jsonPrimitive.content
                socket.methods += method
                when (method) {
                    "hello" -> socket.respond(id, buildJsonObject { put("version", 1) })
                    "ping" -> throw IOException("synthetic broken pipe during message write")
                }
            }.also { sockets += it }
        }
        try {
            val connection = withTimeout(5_000) { client.connectForMessages() }
            assertTrue(connection.isCurrent())

            var failure: Throwable? = null
            try {
                withTimeout(5_000) { connection.request("ping", buildJsonObject {}, 2_000) }
            } catch (error: Throwable) {
                failure = error
            }
            assertTrue("expected IOException, got $failure", failure is IOException)
            assertEquals(1, sockets.size)
            assertTrue("MessageConnection write IOException left corrupted socket open", sockets[0].closed)
            assertFalse("MessageConnection stayed current after write IOException", connection.isCurrent())
            assertNull("existingConnection() exposed socket after MessageConnection write IOException", client.existingConnection())
        } finally {
            client.close()
        }
    }
}
