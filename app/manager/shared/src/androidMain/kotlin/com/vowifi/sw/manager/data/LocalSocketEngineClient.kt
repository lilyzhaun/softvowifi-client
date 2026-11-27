package com.vowifi.sw.manager.data

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.vowifi.sw.manager.model.EngineProtocolFrame
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal class LocalSocketEngineClient(
    private val socketPath: String,
    private val socketFactory: (String) -> LocalSocket = { com.vowifi.sw.root.RootBridge.openSocket(it) },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionLock = Any()
    private val writeLock = Any()
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<ReceivedEngineResponse>>()
    private val mutableEvents = MutableSharedFlow<EngineProtocolFrame.Event>(extraBufferCapacity = 16)
    val events: SharedFlow<EngineProtocolFrame.Event> = mutableEvents.asSharedFlow()
    private val mutableDisconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val disconnected: SharedFlow<Unit> = mutableDisconnected.asSharedFlow()
    private val mutableConnectionProgress = MutableSharedFlow<EngineConnectionProgressEvent>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val connectionProgress: SharedFlow<EngineConnectionProgressEvent> = mutableConnectionProgress.asSharedFlow()
    @Volatile internal var onRuntimeFactsChange: ((EngineRuntimeFactsChange) -> Unit)? = null

    @Volatile
    private var socket: LocalSocket? = null

    @Volatile
    private var handshaken = false
    private var closed = false

    private val handshakeMutex = Mutex()

    suspend fun connect() {
        withContext(Dispatchers.IO) { ensureConnected() }
        handshake()
    }

    suspend fun connectForMessages(): MessageConnection {
        connect()
        return synchronized(connectionLock) {
            val connected = socket ?: throw EOFException("message connection disconnected")
            if (closed || !handshaken) throw EOFException("message connection not ready")
            MessageConnection(connected)
        }
    }

    /** No connection or handshake side effect for optional read-only facts. */
    internal fun existingConnection(): MessageConnection? = synchronized(connectionLock) {
        socket?.takeIf { !closed && handshaken }?.let { MessageConnection(it) }
    }

    internal inner class MessageConnection internal constructor(private val connected: LocalSocket) {
        private var active = true
        private var writing = false
        internal val owner: Any get() = connected

        internal fun isCurrent(): Boolean = synchronized(connectionLock) {
            active && !closed && handshaken && socket === connected
        }

        fun retire() {
            val interrupt = synchronized(connectionLock) { active = false; writing }
            if (interrupt) disconnect(connected, EOFException("message connection retired"), shutdownInput = true)
        }

        suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): EngineProtocolFrame.Response =
            requestWithReceipt(method, params, timeoutMillis).frame

        internal suspend fun requestWithReceipt(method: String, params: JsonObject, timeoutMillis: Long): ReceivedEngineResponse {
            val id = nextId.getAndIncrement()
            val deferred = CompletableDeferred<ReceivedEngineResponse>()
            try {
                return withEngineRequestTimeout(timeoutMillis) {
                    withContext(Dispatchers.IO) {
                        val context = currentCoroutineContext()
                        suspendCancellableCoroutine<Unit> { continuation ->
                            continuation.invokeOnCancellation { cause ->
                                disconnect(connected, IOException("message write cancelled", cause), shutdownInput = true)
                            }
                            var writeError: IOException? = null
                            try {
                                synchronized(writeLock) {
                                    synchronized(connectionLock) {
                                        context.ensureActive()
                                        if (!active || closed || socket !== connected || !handshaken) {
                                            throw EOFException("message connection retired")
                                        }
                                        pending[id] = deferred
                                        writing = true
                                    }
                                    try {
                                        connected.outputStream.write(EngineProtocolCodec.request(id, method, params).encodeToByteArray())
                                        connected.outputStream.flush()
                                    } catch (error: IOException) {
                                        writeError = error
                                        throw error
                                    } finally { synchronized(connectionLock) { writing = false } }
                                }
                                continuation.resumeWith(Result.success(Unit))
                            } catch (error: Exception) {
                                writeError?.let { disconnect(connected, it, shutdownInput = true) }
                                continuation.resumeWith(Result.failure(error))
                            }
                        }
                    }
                    deferred.await()
                }
            } finally {
                pending.remove(id)
            }
        }
    }

    suspend fun request(
        method: String,
        params: JsonObject = buildJsonObject {},
        timeoutMillis: Long = 5_000L,
    ): EngineProtocolFrame.Response {
        connect()
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<ReceivedEngineResponse>()
        try {
            withContext(Dispatchers.IO) {
                var activeSocket: LocalSocket? = null
                try {
                    synchronized(writeLock) {
                        val current = synchronized(connectionLock) {
                            if (closed) throw EOFException("engine client closed")
                            val s = socket ?: throw EOFException("engine socket disconnected")
                            if (!handshaken) throw EOFException("engine socket disconnected")
                            pending[id] = deferred
                            s
                        }
                        activeSocket = current
                        current.outputStream.write(
                            EngineProtocolCodec.request(id, method, params).encodeToByteArray(),
                        )
                        current.outputStream.flush()
                    }
                } catch (error: IOException) {
                    activeSocket?.let { disconnect(it, error, shutdownInput = true) }
                    throw error
                }
            }
            return withEngineRequestTimeout(timeoutMillis) { deferred.await().frame }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun handshake() {
        if (synchronized(connectionLock) { !closed && socket != null && handshaken }) return
        handshakeMutex.withLock {
            if (synchronized(connectionLock) { !closed && socket != null && handshaken }) return
            val id = nextId.getAndIncrement()
            val deferred = CompletableDeferred<ReceivedEngineResponse>()
            var activeSocket: LocalSocket? = null
            var committed = false
            try {
                withContext(Dispatchers.IO) {
                    synchronized(writeLock) {
                        val current = synchronized(connectionLock) {
                            if (closed) throw EOFException("engine client closed")
                            val s = socket ?: throw EOFException("engine socket disconnected")
                            pending[id] = deferred
                            s
                        }
                        activeSocket = current
                        current.outputStream.write(
                            EngineProtocolCodec.request(id, "hello", EngineProtocolCodec.helloParams()).encodeToByteArray(),
                        )
                        current.outputStream.flush()
                    }
                }
                val response = withEngineRequestTimeout(HANDSHAKE_TIMEOUT_MILLIS) { deferred.await().frame }
                val error = response.error
                if (error != null) {
                    if (error.code == EngineProtocolCodec.ERROR_VERSION_MISMATCH) {
                        throw ProtocolVersionMismatchException()
                    }
                    throw IllegalStateException(error.message)
                }
                val version = EngineProtocolCodec.helloVersion(response)
                if (version != EngineProtocolCodec.PROTOCOL_VERSION) {
                    throw ProtocolVersionMismatchException()
                }
                synchronized(connectionLock) {
                    if (closed || socket !== activeSocket) {
                        throw EOFException("engine socket disconnected")
                    }
                    handshaken = true
                    committed = true
                }
            } catch (error: Exception) {
                activeSocket?.let { disconnect(it, error, shutdownInput = true) }
                throw error
            } finally {
                pending.remove(id)
                if (!committed) {
                    activeSocket?.let { disconnect(it, IOException("engine handshake aborted"), shutdownInput = true) }
                }
            }
        }
    }

    private fun ensureConnected() {
        synchronized(connectionLock) {
            check(!closed) { "engine client closed" }
            if (socket != null) return
            val connected = socketFactory(socketPath)
            try {
                connected.setSoTimeout(0)
            } catch (error: Throwable) {
                try { connected.close() } catch (failure: Throwable) { error.addSuppressed(failure) }
                throw error
            }
            socket = connected
            handshaken = false
            scope.launch { readLoop(connected) }
        }
    }

    private suspend fun readLoop(connected: LocalSocket) {
        try {
            val reader = BoundedLineReader(connected.inputStream, MAX_RESPONSE_LINE_BYTES)
            var receipt = 0L
            while (scope.isActive) {
                val line = reader.readLine() ?: throw EOFException("engine socket closed")
                when (val frame = EngineProtocolCodec.decode(line)) {
                    is EngineProtocolFrame.Response -> pending.remove(frame.id)?.complete(ReceivedEngineResponse(frame, ++receipt))
                    is EngineProtocolFrame.Event -> {
                        if (frame.name == "callMediaChanged" && frame.data.keys == setOf("sequence")) {
                            val sequence = (frame.data["sequence"] as? JsonPrimitive)?.takeUnless { it.isString }
                                ?.longOrNull?.takeIf { it > 0 }
                            if (sequence != null) onRuntimeFactsChange?.invoke(EngineRuntimeFactsChange(sequence, connected) {
                                synchronized(connectionLock) { !closed && socket === connected && handshaken }
                            })
                        }
                        if (frame.name == "connectionProgress") {
                            mutableConnectionProgress.tryEmit(EngineConnectionProgressEvent(frame, connected) {
                                synchronized(connectionLock) { !closed && socket === connected && handshaken }
                            })
                        }
                        mutableEvents.emit(frame)
                    }
                }
            }
        } catch (error: Exception) {
            disconnect(connected, error)
        }
    }

    private fun disconnect(connected: LocalSocket, error: Exception, shutdownInput: Boolean = false) {
        val disconnectedPending = synchronized(connectionLock) {
            if (socket !== connected) return
            socket = null
            handshaken = false
            pending.values.toList().also { pending.clear(); mutableDisconnected.tryEmit(Unit) }
        }
        try { closeSocket(connected, shutdownInput) } catch (failure: IOException) { error.addSuppressed(failure) }
        finally {
            disconnectedPending.forEach { deferred -> deferred.completeExceptionally(error) }
        }
    }

    override fun close() {
        scope.cancel()
        val (connected, pendingRequests) = synchronized(connectionLock) {
            closed = true
            val current = socket
            socket = null
            handshaken = false
            current to pending.values.toList().also { pending.clear() }
        }
        try { connected?.let { closeSocket(it, shutdownInput = true) } } finally {
            val error = EOFException("engine client closed")
            pendingRequests.forEach { deferred -> deferred.completeExceptionally(error) }
        }
    }

    private fun closeSocket(connected: LocalSocket, shutdownInput: Boolean) {
        connected.use {
            var failure: IOException? = null
            if (shutdownInput) try { it.shutdownInput() } catch (error: IOException) { failure = error }
            try { it.shutdownOutput() } catch (error: IOException) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            failure?.let { error -> throw error }
        }
    }
}

internal data class EngineConnectionProgressEvent(
    val frame: EngineProtocolFrame.Event,
    val connectionToken: Any,
    val isCurrent: () -> Boolean,
)

internal class ProtocolVersionMismatchException : Exception("协议版本不兼容，请更新应用")

private class BoundedLineReader(
    private val input: InputStream,
    private val maxBytes: Int,
) {
    private val chunk = ByteArray(8 * 1024)
    private var offset = 0
    private var length = 0
    private val line = ByteArrayOutputStream()

    fun readLine(): String? {
        line.reset()
        while (true) {
            if (offset >= length) {
                val read = input.read(chunk)
                if (read < 0) {
                    if (line.size() == 0) return null
                    return decodeLine()
                }
                offset = 0
                length = read
            }
            var newline = -1
            for (i in offset until length) {
                if (chunk[i] == NEWLINE_BYTE) {
                    newline = i
                    break
                }
            }
            if (newline >= 0) {
                val partLen = newline - offset
                if (line.size() + partLen > maxBytes) {
                    throw IOException("engine protocol frame exceeds size limit")
                }
                if (partLen > 0) line.write(chunk, offset, partLen)
                offset = newline + 1
                return decodeLine()
            }
            val remaining = length - offset
            if (line.size() + remaining > maxBytes) {
                throw IOException("engine protocol frame exceeds size limit")
            }
            line.write(chunk, offset, remaining)
            offset = length
        }
    }

    private fun decodeLine(): String {
        val bytes = line.toByteArray()
        val end = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == CR_BYTE) bytes.size - 1 else bytes.size
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    companion object {
        private const val NEWLINE_BYTE: Byte = 10
        private const val CR_BYTE: Byte = 13
    }
}

private const val HANDSHAKE_TIMEOUT_MILLIS = 5_000L
private const val MAX_RESPONSE_LINE_BYTES = 512 * 1024
