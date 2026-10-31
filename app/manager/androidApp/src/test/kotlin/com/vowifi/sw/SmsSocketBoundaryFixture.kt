package com.vowifi.sw

import android.net.LocalSocket
import com.vowifi.sw.manager.data.AndroidEngineClientPool
import com.vowifi.sw.manager.data.EngineMessageTransport
import java.lang.management.ManagementFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal class SmsSocketBoundaryFixture : AutoCloseable {
    val pool = AndroidEngineClientPool()
    val sockets = CopyOnWriteArrayList<CallTestSocket>()
    val writes = CopyOnWriteArrayList<Pair<CallTestSocket, String>>()
    val created = Channel<CallTestSocket>(Channel.UNLIMITED)
    val sent = Channel<Unit>(Channel.UNLIMITED)
    var deferSms = false
    private val factory: (String) -> LocalSocket = ::socket
    val client: Any = Class.forName("com.vowifi.sw.manager.data.LocalSocketEngineClient")
        .getConstructor(String::class.java, Function1::class.java).newInstance("synthetic", factory)
    val writeLock: Any = requireNotNull(client.javaClass.getDeclaredField("writeLock").apply { isAccessible = true }.get(client))

    init {
        val clients = pool.javaClass.getDeclaredField("clients").apply { isAccessible = true }.get(pool)
        Map::class.java.getMethod("put", Any::class.java, Any::class.java).invoke(clients, "a", client)
    }

    fun transport(): EngineMessageTransport = Class.forName("com.vowifi.sw.manager.data.AndroidEngineMessageTransport")
        .getConstructor(AndroidEngineClientPool::class.java, String::class.java).newInstance(pool, "a") as EngineMessageTransport

    fun socket(@Suppress("UNUSED_PARAMETER") path: String = ""): LocalSocket = CallTestSocket { socket, line ->
        val request = Json.parseToJsonElement(line).jsonObject
        val id = request.getValue("id").jsonPrimitive.long
        val method = request.getValue("method").jsonPrimitive.content
        writes += socket to method
        when (method) {
            "hello" -> socket.respond(id, buildJsonObject { put("version", 1) })
            "getStatus" -> socket.respond(id, buildJsonObject {
                put("tunnel", "CONNECTED"); put("reconnects", 0); put("ims", "REGISTERED")
                put("call", "IDLE"); put("transport", "UDP"); put("pcscf", JsonArray(emptyList()))
            })
            "sendSms" -> {
                sent.trySend(Unit)
                if (!deferSms) socket.respond(id, buildJsonObject { put("smsId", "synthetic-${writes.size}"); put("statusCode", 202); put("reference", 1) })
            }
            else -> error("Unexpected synthetic SMS method")
        }
    }.also { sockets += it; created.trySend(it) }

    suspend fun awaitBlockedWriters(count: Int) = withTimeout(5000) {
        val threads = ManagementFactory.getThreadMXBean()
        while (threads.dumpAllThreads(false, false).count {
                it.threadState == Thread.State.BLOCKED && it.lockInfo?.identityHashCode == System.identityHashCode(writeLock)
            } < count) delay(10)
    }

    override fun close() { pool.close(); sockets.forEach { it.close() }; created.close(); sent.close() }
}

internal class SmsWriterGate(lock: Any) : AutoCloseable {
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val executor = Executors.newSingleThreadExecutor()
    private val task = executor.submit {
        synchronized(lock) { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
    }
    init { check(entered.await(5, TimeUnit.SECONDS)) }
    override fun close() { release.countDown(); task.get(5, TimeUnit.SECONDS); executor.shutdown() }
}
