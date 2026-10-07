package com.vowifi.sw

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.vowifi.sw.manager.data.AndroidEngineClientPool
import com.vowifi.sw.manager.data.AndroidCallEventTransport
import com.vowifi.sw.manager.data.EngineMessageTransport
import com.vowifi.sw.manager.model.EngineProtocolFrame
import java.io.IOException
import java.io.EOFException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class BlockedMessageWriterHostTest {
    @Test fun poolCloseInterruptsBlockedWrite() = closesWriter(blockFlush = false)
    @Test fun poolCloseInterruptsBlockedFlush() = closesWriter(blockFlush = true)

    private fun closesWriter(blockFlush: Boolean) = runBlocking {
        BlockedMessageFixture(blockFlush).use { fixture ->
            fixture.transport.connect()
            val pending = async(Dispatchers.Default) { runCatching { fixture.request(5_000) } }
            val closer = Executors.newSingleThreadExecutor()
            try {
                assertTrue(fixture.socket.entered.await(2, TimeUnit.SECONDS))
                val completed = CountDownLatch(1)
                val closing = closer.submit { try { fixture.pool.close() } finally { completed.countDown() } }
                val beforeManualRelease = completed.await(500, TimeUnit.MILLISECONDS)
                fixture.socket.release()
                closing.get(2, TimeUnit.SECONDS)
                val result = withTimeout(2_000) { pending.await() }
                assertTrue("pool close waited for the writer's connectionLock", beforeManualRelease)
                assertTrue(result.isFailure)
                assertFalse(fixture.socket.descriptorOpen)
                assertEquals(0, fixture.socket.activeWriters.get())
            } finally {
                fixture.socket.release()
                withTimeout(2_000) { pending.join() }
                closer.shutdownNow()
            }
        }
    }

    @Test fun timeoutCoversBlockedWriteBeforeResponseWait() = runBlocking {
        BlockedMessageFixture().use { fixture ->
            fixture.transport.connect()
            val pending = async(Dispatchers.Default) { runCatching { fixture.request(100) } }
            try {
                assertTrue(fixture.socket.entered.await(2, TimeUnit.SECONDS))
                val timely = withTimeoutOrNull(500) { pending.await() }
                fixture.socket.release()
                withTimeout(2_000) { pending.join() }
                assertTrue("request deadline starts only after blocked write", timely != null)
                assertEquals("EngineRequestTimeoutException", timely?.exceptionOrNull()?.javaClass?.simpleName)
                assertFalse(fixture.socket.descriptorOpen)
                assertEquals(0, fixture.socket.activeWriters.get())
            } finally { fixture.socket.release(); withTimeout(2_000) { pending.join() } }
        }
    }

    @Test fun callerCancellationInterruptsOldWriter() = runBlocking {
        BlockedMessageFixture().use { fixture ->
            fixture.transport.connect()
            val pending = async(Dispatchers.Default) { runCatching { fixture.request(5_000) } }
            try {
                assertTrue(fixture.socket.entered.await(2, TimeUnit.SECONDS))
                pending.cancel()
                val stopped = withTimeoutOrNull(500) { pending.join(); true } == true
                fixture.socket.release()
                withTimeout(2_000) { pending.join() }
                assertTrue("cancellation did not close the old blocked writer", stopped)
                assertFalse(fixture.socket.descriptorOpen)
                assertEquals(0, fixture.socket.activeWriters.get())
            } finally { fixture.socket.release(); withTimeout(2_000) { pending.join() } }
        }
    }

    @Test fun queuedDeadlineInterruptsOlderWriterAndNeverWritesQueuedSms() = runBlocking {
        BlockedMessageFixture().use { fixture ->
            fixture.transport.connect()
            val first = async(Dispatchers.Default) { runCatching { fixture.request(5_000) } }
            var queued: Deferred<Result<EngineProtocolFrame.Response>>? = null
            try {
                assertTrue(fixture.socket.entered.await(2, TimeUnit.SECONDS))
                val next = async(Dispatchers.Default) { runCatching { fixture.request(100) } }
                queued = next
                val timely = withTimeoutOrNull(1_000) { next.await() }
                fixture.socket.release()
                withTimeout(2_000) { first.join(); next.join() }
                assertTrue("queued request missed its total deadline", timely != null)
                assertEquals("EngineRequestTimeoutException", timely?.exceptionOrNull()?.javaClass?.simpleName)
                assertTrue(first.await().isFailure)
                assertEquals("cancelled queued SMS was first written", 1, fixture.socket.smsWriteAttempts.get())
                assertFalse(fixture.socket.descriptorOpen)
                assertEquals(0, fixture.socket.activeWriters.get())
            } finally {
                fixture.socket.release()
                withTimeout(2_000) { first.join(); queued?.join() }
            }
        }
    }

    @Test fun writeDeadlineReclaimsReaderWithoutPeerEof() = runBlocking {
        BlockedMessageFixture().use { fixture ->
            fixture.transport.connect()
            val reader = fixture.reader
            val pending = async(Dispatchers.Default) { runCatching { fixture.request(100) } }
            try {
                assertTrue(fixture.socket.entered.await(2, TimeUnit.SECONDS))
                assertEquals("EngineRequestTimeoutException", withTimeout(1_000) { pending.await() }.exceptionOrNull()?.javaClass?.simpleName)
                val joined = withTimeoutOrNull(500) { reader.join(); true } == true
                assertTrue("write deadline closed output but left the old reader blocked", joined)
                assertEquals(0, fixture.socket.activeReads)
            } finally {
                fixture.socket.release(); fixture.socket.peerEof()
                withTimeout(2_000) { pending.join(); reader.join() }
            }
        }
    }

    @Test fun retiringActiveMessageConnectionInterruptsItsWriter() = runBlocking {
        BlockedMessageFixture().use { fixture ->
            fixture.transport.connect()
            val pending = async(Dispatchers.Default) { runCatching { fixture.request(5_000) } }
            val closer = Executors.newSingleThreadExecutor()
            try {
                assertTrue(fixture.socket.entered.await(2, TimeUnit.SECONDS))
                val completed = CountDownLatch(1)
                val retiring = closer.submit { try { fixture.transport.retireMessages() } finally { completed.countDown() } }
                val timely = completed.await(500, TimeUnit.MILLISECONDS)
                fixture.socket.release()
                retiring.get(2, TimeUnit.SECONDS)
                assertTrue("retirement waited for the writer's connectionLock", timely)
                assertTrue(withTimeout(2_000) { pending.await() }.isFailure)
                assertFalse(fixture.socket.descriptorOpen)
                assertEquals(0, fixture.socket.activeWriters.get())
            } finally {
                fixture.socket.release()
                withTimeout(2_000) { pending.join() }
                closer.shutdownNow()
            }
        }
    }

    @Test fun oldDisconnectCompletionDoesNotClearReplacementPending() = runBlocking {
        val old = BlockingEngineSocket().apply { holdResponse = true }
        val fresh = BlockingEngineSocket().apply { holdResponse = true }
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gatedOld = object : LocalSocket() {
            override fun connect(endpoint: LocalSocketAddress) = old.connect(endpoint)
            override fun setSoTimeout(timeout: Int) = Unit
            override fun getInputStream() = old.inputStream
            override fun getOutputStream() = old.outputStream
            override fun shutdownInput() = old.shutdownInput()
            override fun shutdownOutput() = old.shutdownOutput()
            override fun close() { closing.countDown(); check(release.await(5, TimeUnit.SECONDS)); old.close() }
        }
        val created = AtomicInteger()
        val transport = AndroidCallEventTransport("synthetic") { if (created.getAndIncrement() == 0) gatedOld else fresh }
        val original = async(Dispatchers.Default) { runCatching { transport.request("getStatus") }.exceptionOrNull() }
        var replacement: Deferred<Throwable?>? = null
        try {
            withTimeout(5_000) { old.requestReceived.await() }
            old.peerEof()
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            val next = async(Dispatchers.Default) { runCatching { transport.request("getStatus") }.exceptionOrNull() }
            replacement = next
            withTimeout(5_000) { fresh.requestReceived.await() }
            release.countDown()
            assertTrue(withTimeout(2_000) { original.await() } is EOFException)
            assertFalse("old cleanup failed the replacement pending request", next.isCompleted)
            fresh.peerEof()
            assertTrue(withTimeout(2_000) { next.await() } is EOFException)
        } finally {
            release.countDown(); old.peerEof(); fresh.peerEof(); transport.close()
            withTimeout(2_000) { original.join(); replacement?.join() }
        }
    }
}

private class BlockedMessageFixture(blockFlush: Boolean = false) : AutoCloseable {
    val pool = AndroidEngineClientPool()
    val socket = BlockedMessageSocket(blockFlush)
    private val client = Class.forName("com.vowifi.sw.manager.data.LocalSocketEngineClient")
        .getConstructor(String::class.java, Function0::class.java).newInstance("synthetic", { socket })
    private val lifetime = (client.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(client) as CoroutineScope)
        .coroutineContext[Job]!!
    val reader: Job get() = lifetime.children.single()
    val transport: EngineMessageTransport

    init {
        val clients = pool.javaClass.getDeclaredField("clients").apply { isAccessible = true }.get(pool)
        Map::class.java.getMethod("put", Any::class.java, Any::class.java).invoke(clients, "synthetic", client)
        transport = Class.forName("com.vowifi.sw.manager.data.AndroidEngineMessageTransport")
            .getConstructor(AndroidEngineClientPool::class.java, String::class.java)
            .newInstance(pool, "synthetic") as EngineMessageTransport
    }

    suspend fun request(timeoutMillis: Long) = transport.request("sendSms", buildJsonObject {
        put("number", "155501"); put("text", "synthetic blocked writer")
    }, timeoutMillis)

    override fun close() {
        socket.release(); socket.peerEof()
        try { pool.close() } finally { socket.close(); runBlocking { withTimeout(2_000) { lifetime.join() } } }
    }
}

private class BlockedMessageSocket(private val blockFlush: Boolean) : LocalSocket() {
    val entered = CountDownLatch(1)
    private val unblocked = CountDownLatch(1)
    val activeWriters = AtomicInteger()
    val smsWriteAttempts = AtomicInteger()
    @Volatile var descriptorOpen = true
        private set
    @Volatile private var outputShutdown = false
    private var smsWritten = false
    private val delegate = BlockingEngineSocket()
    val activeReads: Int get() = delegate.activeReads.get()
    private val output = object : OutputStream() {
        override fun write(value: Int) = delegate.outputStream.write(value)
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            val method = Json.parseToJsonElement(bytes.copyOfRange(offset, offset + length).decodeToString().trim())
                .jsonObject.getValue("method").jsonPrimitive.content
            if (method == "sendSms") {
                smsWriteAttempts.incrementAndGet()
                if (!blockFlush) blockWriter()
                smsWritten = true
            }
            delegate.outputStream.write(bytes, offset, length)
        }
        override fun flush() { if (blockFlush && smsWritten) blockWriter() }
    }
    private fun blockWriter() {
        activeWriters.incrementAndGet()
        entered.countDown()
        try {
            check(unblocked.await(5, TimeUnit.SECONDS)) { "bounded synthetic writer was not released" }
            if (outputShutdown || !descriptorOpen) throw IOException("synthetic old socket closed")
        } finally { activeWriters.decrementAndGet() }
    }
    override fun connect(endpoint: LocalSocketAddress) = Unit
    override fun setSoTimeout(timeout: Int) = Unit
    override fun getInputStream() = delegate.inputStream
    override fun getOutputStream(): OutputStream = output
    override fun shutdownInput() { delegate.shutdownInput() }
    override fun shutdownOutput() { outputShutdown = true; unblocked.countDown() }
    override fun close() { descriptorOpen = false; outputShutdown = true; unblocked.countDown(); delegate.close() }
    fun peerEof() { delegate.peerEof() }
    fun release() { unblocked.countDown() }
}
