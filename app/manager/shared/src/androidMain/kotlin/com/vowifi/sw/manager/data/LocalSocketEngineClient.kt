package com.vowifi.sw.manager.data

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.vowifi.sw.manager.model.EngineProtocolFrame
import java.io.BufferedReader
import java.io.EOFException
import java.io.IOException
import java.io.InputStreamReader
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

internal class LocalSocketEngineClient(
    private val socketPath: String,
    private val socketFactory: (String) -> LocalSocket = { com.vowifi.sw.root.RootBridge.openSocket(it) },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionLock = Any()
    private val writeLock = Any()
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<EngineProtocolFrame.Response>>()
    private val mutableEvents = MutableSharedFlow<EngineProtocolFrame.Event>(extraBufferCapacity = 16)
    val events: SharedFlow<EngineProtocolFrame.Event> = mutableEvents.asSharedFlow()
    private val mutableDisconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val disconnected: SharedFlow<Unit> = mutableDisconnected.asSharedFlow()
    private val mutableConnectionProgress = MutableSharedFlow<EngineConnectionProgressEvent>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val connectionProgress: SharedFlow<EngineConnectionProgressEvent> = mutableConnectionProgress.asSharedFlow()

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

        internal fun isCurrent(): Boolean = synchronized(connectionLock) {
            active && !closed && handshaken && socket === connected
        }

        fun retire() {
            val interrupt = synchronized(connectionLock) { active = false; writing }
            if (interrupt) disconnect(connected, EOFException("message connection retired"), shutdownInput = true)
        }

        suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): EngineProtocolFrame.Response {
            val id = nextId.getAndIncrement()
            val deferred = CompletableDeferred<EngineProtocolFrame.Response>()
            try {
                return withEngineRequestTimeout(timeoutMillis) {
                    withContext(Dispatchers.IO) {
                        val context = currentCoroutineContext()
                        suspendCancellableCoroutine<Unit> { continuation ->
                            continuation.invokeOnCancellation { cause ->
                                disconnect(connected, IOException("message write cancelled", cause), shutdownInput = true)
                            }
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
                                    } finally { synchronized(connectionLock) { writing = false } }
                                }
                                continuation.resumeWith(Result.success(Unit))
                            } catch (error: Exception) {
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
        val deferred = CompletableDeferred<EngineProtocolFrame.Response>()
        pending[id] = deferred
        try {
            withContext(Dispatchers.IO) {
                synchronized(writeLock) {
                    val activeSocket = checkNotNull(socket) { "engine socket disconnected" }
                    activeSocket.outputStream.write(
                        EngineProtocolCodec.request(id, method, params).encodeToByteArray(),
                    )
                    activeSocket.outputStream.flush()
                }
            }
            return withEngineRequestTimeout(timeoutMillis) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun handshake() {
        if (handshaken) return
        handshakeMutex.withLock {
            if (handshaken) return
            val id = nextId.getAndIncrement()
            val deferred = CompletableDeferred<EngineProtocolFrame.Response>()
            pending[id] = deferred
            try {
                withContext(Dispatchers.IO) {
                    synchronized(writeLock) {
                        val activeSocket = checkNotNull(socket) { "engine socket disconnected" }
                        activeSocket.outputStream.write(
                            EngineProtocolCodec.request(id, "hello", EngineProtocolCodec.helloParams()).encodeToByteArray(),
                        )
                        activeSocket.outputStream.flush()
                    }
                }
                val response = withEngineRequestTimeout(HANDSHAKE_TIMEOUT_MILLIS) { deferred.await() }
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
                handshaken = true
            } finally {
                pending.remove(id)
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
            } catch (error: IOException) {
                try { connected.close() } catch (failure: IOException) { error.addSuppressed(failure) }
                throw error
            }
            socket = connected
            handshaken = false
            scope.launch { readLoop(connected) }
        }
    }

    private suspend fun readLoop(connected: LocalSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(connected.inputStream, Charsets.UTF_8))
            while (scope.isActive) {
                val line = reader.readLine() ?: throw EOFException("engine socket closed")
                when (val frame = EngineProtocolCodec.decode(line)) {
                    is EngineProtocolFrame.Response -> pending.remove(frame.id)?.complete(frame)
                    is EngineProtocolFrame.Event -> {
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
        val connected = synchronized(connectionLock) {
            closed = true
            socket.also {
                socket = null
                handshaken = false
            }
        }
        try { connected?.let { closeSocket(it, shutdownInput = true) } } finally {
            val error = EOFException("engine client closed")
            pending.values.forEach { deferred -> deferred.completeExceptionally(error) }
            pending.clear()
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

private const val HANDSHAKE_TIMEOUT_MILLIS = 5_000L
