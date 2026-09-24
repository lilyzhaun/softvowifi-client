package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidCallControlTransport
import com.vowifi.sw.manager.data.AndroidEngineClientPool
import com.vowifi.sw.manager.data.EngineMessageTransport
import java.io.EOFException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
class LocalSocketReaderCloseHostTest {
    @Test fun closeTerminatesBlockedReaderBeforePeerClosesAndIsIdempotent() = runBlocking {
        BlockingSocketClient().use { fixture ->
            fixture.transport.connect()
            val reader = fixture.reader
            assertEquals(1, fixture.socket.activeReads.get())

            fixture.transport.close()

            assertFalse(fixture.socket.descriptorOpen)
            withTimeout(1_000) { reader.join() }
            assertTrue(reader.isCompleted)
            assertEquals(0, fixture.socket.activeReads.get())
            assertEquals(0, fixture.pendingCount)
            fixture.transport.close()
            assertEquals(1, fixture.socket.closeCalls.get())
        }
    }

    @Test fun sequentialControlRequestsDoNotAccumulateBlockedReaders() = runBlocking {
        val sockets = mutableListOf<BlockingEngineSocket>()
        val control = AndroidCallControlTransport { BlockingEngineSocket().also(sockets::add) }
        try {
            repeat(3) {
                val response = control.request("synthetic", "getCallSnapshot", buildJsonObject {}, 5_000)
                assertNull(response.error)
                val socket = sockets.last()
                withTimeout(1_000) { socket.eof.await() }
                assertEquals(0, sockets.sumOf { it.activeReads.get() })
                assertTrue(sockets.none { it.descriptorOpen })
            }
            assertEquals(3, sockets.size)
        } finally { sockets.forEach { it.peerEof() }; sockets.forEach { withTimeout(5_000) { it.eof.await() } } }
    }

    @Test fun failedHandshakeStillReleasesControlReader() = runBlocking {
        val socket = BlockingEngineSocket().apply { helloVersion = 2 }
        val control = AndroidCallControlTransport { socket }
        try {
            val failure = runCatching { control.request("synthetic", "getCallSnapshot", buildJsonObject {}, 5_000) }.exceptionOrNull()
            assertEquals("ProtocolVersionMismatchException", failure?.javaClass?.simpleName)
            assertFalse(socket.descriptorOpen)
            withTimeout(1_000) { socket.eof.await() }
            assertEquals(0, socket.activeReads.get())
            assertFalse(socket.requestReceived.isCompleted)
        } finally { socket.peerEof(); withTimeout(5_000) { socket.eof.await() } }
    }

    @Test fun failedConnectClosesAcquiredSocketAndKeepsOriginalError() = runBlocking {
        val original = SocketBoundaryFailure("connect")
        val socket = BlockingEngineSocket().apply { connectFailure = original }
        try {
            val failure = runCatching {
                AndroidCallControlTransport { socket }.request("synthetic", "getCallSnapshot", buildJsonObject {}, 5_000)
            }.exceptionOrNull()
            assertSame(original, failure)
            assertFalse("failed connection must release its acquired descriptor", socket.descriptorOpen)
            assertEquals(1, socket.closeCalls.get())
            assertEquals(0, socket.activeReads.get())
        } finally { socket.close() }
    }

    @Test fun peerEofReleasesReaderAndCompletesPendingRequest() = runBlocking {
        BlockingSocketClient().use { fixture ->
            fixture.transport.connect()
            val reader = fixture.reader
            fixture.socket.holdResponse = true
            val pending = async(Dispatchers.Default) { runCatching { fixture.transport.request("getStatus") }.exceptionOrNull() }
            withTimeout(5_000) { fixture.socket.requestReceived.await() }

            fixture.socket.peerEof()

            assertTrue(withTimeout(1_000) { pending.await() } is EOFException)
            withTimeout(1_000) { reader.join() }
            assertFalse(fixture.socket.descriptorOpen)
            assertEquals(0, fixture.pendingCount)
            assertEquals(0, fixture.socket.shutdownCalls.get())
        }
    }

    @Test fun poolCloseReleasesRetiredMessageReaderWithoutReplacement() = runBlocking {
        BlockingSocketClient().use { fixture ->
            val pool = AndroidEngineClientPool()
            val clients = pool.javaClass.getDeclaredField("clients").apply { isAccessible = true }.get(pool)
            Map::class.java.getMethod("put", Any::class.java, Any::class.java).invoke(clients, "synthetic", fixture.client)
            val transport = Class.forName("com.vowifi.sw.manager.data.AndroidEngineMessageTransport")
                .getConstructor(AndroidEngineClientPool::class.java, String::class.java).newInstance(pool, "synthetic") as EngineMessageTransport
            try {
                transport.connect()
                val reader = fixture.reader
                transport.retireMessages()
                assertEquals(1, fixture.socket.activeReads.get())

                pool.close()

                withTimeout(1_000) { reader.join() }
                assertEquals(0, fixture.socket.activeReads.get())
                assertTrue(runCatching { transport.request("sendSms") }.exceptionOrNull() is EOFException)
                assertFalse(fixture.socket.requestReceived.isCompleted)
            } finally { pool.close() }
        }
    }
}
