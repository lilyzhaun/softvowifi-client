package com.vowifi.sw

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*

internal class CallSocketTestServer {
    val connections = CopyOnWriteArrayList<CallTestSocket>()
    val cancels = AtomicInteger()
    val dials = AtomicInteger()
    val digits = AtomicInteger()
    val hangups = AtomicInteger()
    @Volatile var stage = "IDLE"
    @Volatile var revision = 0
    @Volatile var primary: CallTestSocket? = null
    @Volatile private var dialId = 0L
    @Volatile private var digitSocket: CallTestSocket? = null
    @Volatile var holdSnapshot = false
    @Volatile var heldSnapshot: Pair<CallTestSocket, Long>? = null
        private set
    val dialPending get() = dialId != 0L
    val primaryClosed get() = primary?.closed ?: false
    fun socket(@Suppress("UNUSED_PARAMETER") path: String = ""): LocalSocket = CallTestSocket(::request).also(connections::add)

    private fun request(socket: CallTestSocket, line: String) {
        val data = Json.parseToJsonElement(line).jsonObject
        val id = data.getValue("id").jsonPrimitive.long
        val method = data.getValue("method").jsonPrimitive.content
        socket.methods += method
        if (socket === primary && dialPending) return
        when (method) {
            "hello" -> socket.respond(id, buildJsonObject { put("version", 1) })
            "getCallSnapshot" -> if (holdSnapshot) heldSnapshot = socket to id else respondSnapshot(socket, id)
            "dial" -> {
                primary = socket; stage = "DIALING"; revision = 1
                dials.incrementAndGet()
                dialId = id
                socket.send(buildJsonObject {
                    put("event", "callState")
                    put("data", buildJsonObject {
                        put("callId", "host-call"); put("state", "DIALING"); put("epoch", "host-epoch"); put("revision", 1)
                    })
                })
            }
            "cancelCall" -> {
                check(socket !== primary)
                stage = "CANCELING"; revision = 2
                socket.respond(999999, buildJsonObject { put("state", "IDLE") })
                socket.respond(id, buildJsonObject { put("accepted", true); put("callId", "host-call") })
                cancels.incrementAndGet()
            }
            "sendDtmf" -> { digitSocket = socket; digits.incrementAndGet() }
            "hangup" -> {
                check(socket !== digitSocket)
                stage = "IDLE"; revision++
                socket.respond(id, buildJsonObject { put("state", "IDLE") })
                hangups.incrementAndGet()
            }
            else -> error("unexpected host RPC method")
        }
    }

    private fun respondSnapshot(socket: CallTestSocket, id: Long) = socket.respond(id, buildJsonObject {
        put("epoch", "host-epoch"); put("revision", revision); put("stage", stage)
        put("confirmedElapsedMs", if (stage == "IN_CALL") 12000 else 0)
        put("dtmfSupported", stage == "IN_CALL")
        if (stage != "IDLE") {
            put("callId", "host-call"); put("direction", "OUTGOING"); put("peer", "+12025550123")
        }
    })

    fun releaseSnapshot() {
        val (socket, id) = checkNotNull(heldSnapshot)
        holdSnapshot = false
        respondSnapshot(socket, id)
    }

    fun finishCanceledReply() {
        stage = "IDLE"; revision = 3
        primary!!.send(buildJsonObject {
            put("id", dialId)
            put("error", buildJsonObject { put("code", 2005); put("message", "canceled") })
        })
        dialId = 0L
    }

    fun sendCanceledEvent() {
        primary!!.send(buildJsonObject {
            put("event", "callEnded")
            put("data", buildJsonObject {
                put("callId", "host-call"); put("reason", "canceled"); put("epoch", "host-epoch"); put("revision", 3)
            })
        })
    }
    fun close() { connections.forEach { it.close() } }
}

internal class CallTestSocket(private val receive: (CallTestSocket, String) -> Unit) : LocalSocket() {
    val methods = CopyOnWriteArrayList<String>()
    private val incoming = LinkedBlockingQueue<Int>()
    @Volatile var closed = false
    private val input = object : InputStream() {
        override fun read(): Int = incoming.take()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val first = read()
            if (first < 0) return -1
            buffer[offset] = first.toByte()
            var count = 1
            while (count < length) {
                val next = incoming.poll() ?: break
                if (next < 0) { incoming.offer(-1); break }
                buffer[offset + count] = next.toByte(); count++
            }
            return count
        }
    }
    private val output = object : OutputStream() {
        private val line = ByteArrayOutputStream()
        override fun write(value: Int) {
            if (value == 10) {
                val request = line.toString(Charsets.UTF_8)
                line.reset()
                receive(this@CallTestSocket, request)
            } else line.write(value)
        }
    }
    override fun connect(endpoint: LocalSocketAddress) = Unit
    override fun setSoTimeout(timeout: Int) = Unit
    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun shutdownInput() { endInput() }
    override fun shutdownOutput() = Unit
    override fun close() { closed = true; endInput() }
    fun endInput() { incoming.offer(-1) }
    fun respond(id: Long, result: JsonObject) = send(buildJsonObject { put("id", id); put("result", result) })
    fun send(value: JsonObject) { (value.toString() + "\n").toByteArray().forEach { incoming.offer(it.toInt() and 255) } }
}
