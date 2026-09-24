package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidEngineClientPool
import com.vowifi.sw.manager.data.AndroidEngineControlSource
import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineInstanceState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

internal class EngineCapabilitiesHostFixture : AutoCloseable {
    enum class Reply { Valid, Old, Silent }
    val methods = CopyOnWriteArrayList<String>()
    val commands = CopyOnWriteArrayList<String>()
    val sockets = CopyOnWriteArrayList<CallTestSocket>()
    private val statuses = AtomicInteger()
    private val sources = CopyOnWriteArrayList<AndroidEngineControlSource>()
    val pool = AndroidEngineClientPool(::socket)
    @Volatile var running = true
    @Volatile var reply = Reply.Valid
    @Volatile var key = KEY
    @Volatile var params: JsonElement? = null
    @Volatile var pending = CompletableDeferred<Pair<CallTestSocket, Long>>()

    fun source(): AndroidEngineControlSource = AndroidEngineControlSource(pool, ::control).also { sources += it }

    suspend fun ready(source: AndroidEngineControlSource, after: Long = 0): EngineInstanceState = withTimeout(8_000) {
        val state = source.state.first {
            it is EngineControlUiState.Ready && (it.instances.singleOrNull()?.runtime?.reconnects ?: 0) > after
        } as EngineControlUiState.Ready
        state.instances.single()
    }

    private suspend fun control(command: String, owner: String?): String {
        commands += command
        check(command == "list" && owner == null)
        return if (running) """[{"ok":true,"iccid":"synthetic-owner","state":"running","pid":42}]"""
        else """[{"ok":true,"iccid":"stopped","state":"stopped"},{"ok":true,"iccid":"missing","state":"config_missing"}]"""
    }

    private fun socket() = CallTestSocket { socket, line ->
        val request = Json.parseToJsonElement(line).jsonObject
        val id = request.getValue("id").jsonPrimitive.long
        val method = request.getValue("method").jsonPrimitive.content
        methods += method
        when (method) {
            "hello" -> socket.respond(id, buildJsonObject { put("version", 1) })
            "getStatus" -> socket.respond(id, buildJsonObject {
                put("tunnel", "CONNECTED"); put("reconnects", statuses.incrementAndGet())
                put("ims", "REGISTERED"); put("call", "IDLE"); put("transport", "tcp")
                put("pcscf", JsonArray(emptyList()))
            })
            "getClientCapabilities" -> {
                params = request.getValue("params")
                when (reply) {
                    Reply.Valid -> socket.respond(id, metadata(key))
                    Reply.Old -> socket.send(buildJsonObject {
                        put("id", id); put("error", buildJsonObject { put("code", 1001); put("message", "private-canary") })
                    })
                    Reply.Silent -> pending.complete(socket to id)
                }
            }
            else -> error("unexpected RPC")
        }
    }.also { sockets += it }

    override fun close() { sources.forEach { it.close() }; pool.close() }

    companion object {
        const val KEY = "00647499b77a2163531b4fa05e1014fb"
        const val OTHER_KEY = "abcdefabcdefabcdefabcdefabcdefab"
        fun metadata(key: String = KEY): JsonObject = JsonObject(
            Json.parseToJsonElement(CANONICAL).jsonObject + ("instance" to buildJsonObject { put("key", key) }))
        private const val CANONICAL = """{"schema":1,"protocol":{"version":1,"engineVersion":"0.1.0"},"instance":{"key":"00647499b77a2163531b4fa05e1014fb"},"capabilities":["config.metadata.v1","rpc.getConfig","rpc.getStatus","rpc.ping","rpc.stop"],"configMetadata":[{"field":"imsSipSecurity","value":"tcp_ipsec_3gpp","source":"default","decision":"inherited"},{"field":"paniInitial","value":"absent","source":"default","decision":"inherited"},{"field":"paniCountry","value":"","source":"home_mcc","decision":"inherited"},{"field":"imsDigestRealm","value":"","source":"identity","decision":"inherited"}]}"""
    }
}
