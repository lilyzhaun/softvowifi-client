package com.vowifi.sw

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.vowifi.sw.manager.data.AndroidCallEventTransport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal class SocketBoundaryFailure(val operation: String) : IOException("synthetic $operation")

internal class BlockingEngineSocket : LocalSocket() {
    private class Packet(val bytes: ByteArray) {
        val followingRead = CountDownLatch(1)
    }
    private val incoming = LinkedBlockingQueue<Packet>()
    val activeReads = AtomicInteger()
    val closeCalls = AtomicInteger()
    val shutdownCalls = AtomicInteger()
    val eof = CompletableDeferred<Unit>()
    val requestReceived = CompletableDeferred<Unit>()
    @Volatile var descriptorOpen = true
        private set
    var connectFailure: IOException? = null
    var shutdownFailure: IOException? = null
    var closeFailure: IOException? = null
    var helloVersion = 1
    var holdResponse = false

    private val input = object : InputStream() {
        private var current = ByteArrayInputStream(byteArrayOf())
        private var previous: Packet? = null
        override fun read(): Int {
            val buffer = ByteArray(1)
            return if (read(buffer, 0, 1) < 0) -1 else buffer[0].toInt() and 255
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (current.available() == 0) {
                activeReads.incrementAndGet()
                previous?.followingRead?.countDown()
                val packet = try { incoming.take() } finally { activeReads.decrementAndGet() }
                if (packet.bytes.isEmpty()) { eof.complete(Unit); return -1 }
                current = ByteArrayInputStream(packet.bytes)
                previous = packet
            }
            return current.read(buffer, offset, length)
        }
    }
    private val output = object : OutputStream() {
        private val line = ByteArrayOutputStream()
        override fun write(value: Int) {
            check(descriptorOpen)
            if (value != 10) { line.write(value); return }
            val request = Json.parseToJsonElement(line.toString(Charsets.UTF_8)).jsonObject
            line.reset()
            val method = request.getValue("method").jsonPrimitive.content
            val id = request.getValue("id").jsonPrimitive.long
            if (method != "hello") {
                requestReceived.complete(Unit)
                if (holdResponse) return
            }
            val response = buildJsonObject {
                put("id", id)
                put("result", buildJsonObject {
                    if (method == "hello") put("version", helloVersion) else put("accepted", true)
                })
            }
            val packet = Packet((response.toString() + "\n").encodeToByteArray())
            incoming.put(packet)
            check(packet.followingRead.await(5, TimeUnit.SECONDS)) { "reader did not enter next read" }
        }
    }
    override fun connect(endpoint: LocalSocketAddress) { connectFailure?.let { throw it } }
    override fun setSoTimeout(timeout: Int) = Unit
    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun shutdownInput() {
        shutdownCalls.incrementAndGet()
        shutdownFailure?.let { throw it }
        peerEof()
    }
    override fun close() {
        closeCalls.incrementAndGet()
        descriptorOpen = false
        closeFailure?.let { throw it }
    }
    fun peerEof() { incoming.offer(Packet(byteArrayOf())) }
}

internal class BlockingSocketClient : AutoCloseable {
    val socket = BlockingEngineSocket()
    val transport = AndroidCallEventTransport("synthetic") { socket }
    val client: Any = requireNotNull(transport.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(transport))
    private val scope = client.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(client) as CoroutineScope
    val lifetime: Job = requireNotNull(scope.coroutineContext[Job])
    val reader: Job get() = lifetime.children.single()
    val pendingCount: Int get() = (client.javaClass.getDeclaredField("pending").apply { isAccessible = true }.get(client) as Map<*, *>).size
    val connectedSocket: Any? get() = client.javaClass.getDeclaredField("socket").apply { isAccessible = true }.get(client)

    override fun close() {
        socket.peerEof()
        socket.shutdownFailure = null
        socket.closeFailure = null
        try { transport.close() } finally { runBlocking { withTimeout(5_000) { lifetime.join() } } }
    }
}
