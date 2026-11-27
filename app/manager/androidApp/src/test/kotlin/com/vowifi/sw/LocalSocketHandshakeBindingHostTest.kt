@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.LocalSocketEngineClient
import java.io.EOFException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
class LocalSocketHandshakeBindingHostTest {
    @Test fun closeAfterHelloReplyBeforeHandshakeCommitRejectsConnectAndKeepsHandshakenFalse() = runBlocking {
        lateinit var client: LocalSocketEngineClient
        val helloDelivered = CountDownLatch(1)
        client = LocalSocketEngineClient("/synthetic/close-during-hello.sock") {
            CallTestSocket { socket, line ->
                val req = Json.parseToJsonElement(line).jsonObject
                val id = req.getValue("id").jsonPrimitive.long
                val method = req.getValue("method").jsonPrimitive.content
                socket.methods += method
                if (method == "hello") {
                    // Reply while write() is still returning so readLoop completes deferred
                    // before handshake() reaches its post-await commit.
                    socket.respond(id, buildJsonObject { put("version", 1) })
                    awaitPendingDrained(client)
                    client.close()
                    helloDelivered.countDown()
                }
            }
        }
        try {
            var failure: Throwable? = null
            try {
                withTimeout(5000) { client.connect() }
            } catch (error: Throwable) {
                failure = error
            }
            assertTrue(helloDelivered.await(2, TimeUnit.SECONDS))
            assertTrue(
                "connect() must fail with EOFException when closed during handshake, got $failure",
                failure is EOFException,
            )
            assertFalse("closed client must never flip handshaken=true", isHandshaken(client))
            assertNull(client.existingConnection())
        } finally {
            client.close()
        }
    }

    @Test fun replacementSocketOpenedDuringStaleHelloIsNeverMarkedHandshakenWithoutOwnHello() = runBlocking {
        lateinit var client: LocalSocketEngineClient
        val socketsCreated = AtomicInteger(0)
        val allSockets = CopyOnWriteArrayList<CallTestSocket>()
        val disconnectedSignal = CompletableDeferred<Unit>()

        client = LocalSocketEngineClient("/synthetic/swap-during-hello.sock") {
            val idx = socketsCreated.incrementAndGet()
            CallTestSocket { socket, line ->
                val req = Json.parseToJsonElement(line).jsonObject
                val id = req.getValue("id").jsonPrimitive.long
                val method = req.getValue("method").jsonPrimitive.content
                socket.methods += method
                if (idx == 1 && method == "hello") {
                    socket.respond(id, buildJsonObject { put("version", 1) })
                    awaitPendingDrained(client)
                    // Retire S1 and open a brand-new un-handshaken S2 before S1's handshake()
                    // resumes past deferred.await().
                    socket.endInput()
                    runBlocking { withTimeout(3000) { disconnectedSignal.await() } }
                    invokeEnsureConnected(client)
                } else if (method == "hello") {
                    socket.respond(id, buildJsonObject { put("version", 1) })
                } else if (method == "ping") {
                    socket.respond(id, buildJsonObject { put("pong", true) })
                }
            }.also { allSockets += it }
        }
        val disconnectCollector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            client.disconnected.first()
            disconnectedSignal.complete(Unit)
        }
        try {
            var failure: Throwable? = null
            try {
                withTimeout(5000) { client.connect() }
            } catch (error: Throwable) {
                failure = error
            }
            assertEquals("expected S1 and replacement S2 to be created", 2, allSockets.size)
            val s2 = allSockets[1]
            assertTrue(
                "stale S1 handshake must fail with EOFException after S2 replaced socket, got $failure",
                failure is EOFException,
            )
            assertTrue("S2 should not have received any hello yet", s2.methods.isEmpty())
            assertFalse("S2 must not be marked handshaken by S1's stale hello reply", isHandshaken(client))
            assertNull("existingConnection() must not expose un-handshaken S2", client.existingConnection())

            // A subsequent normal request must perform S2's own hello before sending ping.
            val reply = withTimeout(5000) { client.request("ping") }
            assertNull(reply.error)
            assertEquals(listOf("hello", "ping"), s2.methods.toList())
        } finally {
            disconnectCollector.cancel()
            client.close()
        }
    }

    @Test fun normalHandshakeAndRequestRemainsBoundAndReusable() = runBlocking {
        val sockets = CopyOnWriteArrayList<CallTestSocket>()
        val client = LocalSocketEngineClient("/synthetic/normal-hello.sock") {
            CallTestSocket { socket, line ->
                val req = Json.parseToJsonElement(line).jsonObject
                val id = req.getValue("id").jsonPrimitive.long
                val method = req.getValue("method").jsonPrimitive.content
                socket.methods += method
                when (method) {
                    "hello" -> socket.respond(id, buildJsonObject { put("version", 1) })
                    "ping" -> socket.respond(id, buildJsonObject { put("pong", true) })
                    else -> error("unexpected method: $method")
                }
            }.also { sockets += it }
        }
        try {
            val reply = withTimeout(5000) { client.request("ping") }
            assertNull(reply.error)
            assertEquals(1, sockets.size)
            assertEquals(listOf("hello", "ping"), sockets[0].methods.toList())
            assertNotNull(client.existingConnection())
        } finally {
            client.close()
        }
    }

    private fun awaitPendingDrained(client: LocalSocketEngineClient) {
        val field = client.javaClass.getDeclaredField("pending").apply { isAccessible = true }
        val pending = field.get(client) as ConcurrentHashMap<*, *>
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (pending.isNotEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(2)
        }
        assertTrue("pending hello response did not drain in time", pending.isEmpty())
    }

    private fun isHandshaken(client: LocalSocketEngineClient): Boolean {
        val field = client.javaClass.getDeclaredField("handshaken").apply { isAccessible = true }
        return field.getBoolean(client)
    }

    private fun invokeEnsureConnected(client: LocalSocketEngineClient) {
        val method = client.javaClass.getDeclaredMethod("ensureConnected").apply { isAccessible = true }
        method.invoke(client)
    }
}
