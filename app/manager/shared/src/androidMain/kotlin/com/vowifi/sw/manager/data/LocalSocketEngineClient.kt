package com.vowifi.sw.manager.data

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.vowifi.sw.manager.model.EngineProtocolFrame
import java.io.BufferedReader
import java.io.EOFException
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

internal class LocalSocketEngineClient(
    private val socketPath: String,
    private val socketFactory: () -> LocalSocket = { LocalSocket() },
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
            val connected = socketFactory()
            connected.connect(
                LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM),
            )
            connected.setSoTimeout(0)
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
                    is EngineProtocolFrame.Event -> mutableEvents.emit(frame)
                }
            }
        } catch (error: Exception) {
            disconnect(connected, error)
        }
    }

    private fun disconnect(connected: LocalSocket, error: Exception) {
        synchronized(connectionLock) {
            if (socket !== connected) return
            socket = null
            handshaken = false
            connected.close()
        }
        pending.values.forEach { deferred -> deferred.completeExceptionally(error) }
        pending.clear()
        mutableDisconnected.tryEmit(Unit)
    }

    override fun close() {
        scope.cancel()
        synchronized(connectionLock) {
            closed = true
            socket?.close()
            socket = null
            handshaken = false
        }
        val error = EOFException("engine client closed")
        pending.values.forEach { deferred -> deferred.completeExceptionally(error) }
        pending.clear()
    }
}

internal class ProtocolVersionMismatchException : Exception("协议版本不兼容，请更新应用")

private const val HANDSHAKE_TIMEOUT_MILLIS = 5_000L
