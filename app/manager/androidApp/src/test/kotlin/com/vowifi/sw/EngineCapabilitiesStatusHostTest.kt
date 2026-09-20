package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidEngineClientPool
import com.vowifi.sw.manager.data.AndroidEngineControlSource
import com.vowifi.sw.manager.model.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class EngineCapabilitiesStatusHostTest {
    @Test
    fun actualAndroidStatusPathConsumesMetadataAfterStatus() = runBlocking {
        val backend = Backend()
        val pool = AndroidEngineClientPool(backend::socket)
        val source = AndroidEngineControlSource(pool, backend::control)
        try {
            val instance = ready(source).instances.single()
            assertEquals("REGISTERED", instance.runtime?.ims)
            assertEquals("", instance.capabilities?.configMetadata?.last()?.value)
            assertEquals(MetadataSource.Identity, instance.capabilities?.configMetadata?.last()?.source)
            assertEquals(listOf("hello", "getStatus", "getClientCapabilities"), backend.methods.toList())
            assertEquals(Json.parseToJsonElement("""{"schema":1,"required":["config.metadata.v1"]}"""), backend.params)
            assertEquals(listOf("list"), backend.commands.toList())
        } finally { source.close(); pool.close() }
    }

    @Test
    fun olderCoreKeepsSuccessfulStatusAndDropsCapabilities() = runBlocking {
        val backend = Backend().apply { mode = Mode.Old }
        val pool = AndroidEngineClientPool(backend::socket)
        val source = AndroidEngineControlSource(pool, backend::control)
        try {
            val instance = ready(source).instances.single()
            assertEquals(EngineLifecycle.RUNNING, instance.lifecycle)
            assertEquals("REGISTERED", instance.runtime?.ims)
            assertNull(instance.capabilities)
            assertNull(instance.message)
        } finally { source.close(); pool.close() }
    }

    @Test
    fun silentCapabilityRpcIsOptionalAndBounded() = runBlocking {
        val backend = Backend().apply { mode = Mode.Silent }
        val pool = AndroidEngineClientPool(backend::socket)
        val source = AndroidEngineControlSource(pool, backend::control)
        try {
            val instance = withTimeout(2000) { ready(source).instances.single() }
            assertEquals("REGISTERED", instance.runtime?.ims)
            assertNull(instance.capabilities)
            assertNull(instance.message)
        } finally { source.close(); pool.close() }
    }

    @Test
    fun refreshDoesNotRetainOldMetadataAfterFailureOrNewInstance() = runBlocking {
        val backend = Backend()
        val pool = AndroidEngineClientPool(backend::socket)
        val source = AndroidEngineControlSource(pool, backend::control)
        try {
            val original = ready(source).instances.single().capabilities
            assertNotNull(original)
            backend.mode = Mode.Old
            source.refresh()
            val failed = withTimeout(8000) {
                source.state.first { it is EngineControlUiState.Ready && it.instances.single().runtime?.reconnects != 1L }
            } as EngineControlUiState.Ready
            assertNull(failed.instances.single().capabilities)
            backend.key = "abcdefabcdefabcdefabcdefabcdefab"
            backend.mode = Mode.Valid
            source.refresh()
            val changed = withTimeout(8000) {
                source.state.first { it is EngineControlUiState.Ready && it.instances.single().capabilities?.instanceKey == backend.key }
            } as EngineControlUiState.Ready
            assertNotEquals(original?.instanceKey, changed.instances.single().capabilities?.instanceKey)
        } finally { source.close(); pool.close() }
    }

    @Test
    fun offlineAndMissingInstancesNeverConnectOrProvision() = runBlocking {
        val backend = Backend().apply { running = false }
        val pool = AndroidEngineClientPool(backend::socket)
        val source = AndroidEngineControlSource(pool, backend::control)
        try {
            val instances = ready(source).instances
            assertEquals(listOf(EngineLifecycle.STOPPED, EngineLifecycle.CONFIG_MISSING), instances.map { it.lifecycle })
            assertTrue(instances.all { it.capabilities == null })
            assertTrue(backend.methods.isEmpty())
            assertEquals(0, backend.connections.get())
            assertEquals(listOf("list"), backend.commands.toList())
        } finally { source.close(); pool.close() }
    }

    private suspend fun ready(source: AndroidEngineControlSource): EngineControlUiState.Ready =
        withTimeout(8000) { source.state.first { it is EngineControlUiState.Ready } } as EngineControlUiState.Ready

    private enum class Mode { Valid, Old, Silent }

    private class Backend {
        val methods = CopyOnWriteArrayList<String>()
        val commands = CopyOnWriteArrayList<String>()
        val connections = AtomicInteger()
        private val statuses = AtomicInteger()
        @Volatile var running = true
        @Volatile var mode = Mode.Valid
        @Volatile var key: String? = null
        @Volatile var params: JsonElement? = null
        private val producer = System.getenv("TASK7_FRESH_WIRE")?.let { File(it).readText() } ?: CANONICAL

        suspend fun control(command: String, owner: String?): String {
            commands += command
            check(command == "list" && owner == null)
            return if (running) """[{"ok":true,"iccid":"synthetic-owner","state":"running","pid":42}]"""
            else """[{"ok":true,"iccid":"stopped","state":"stopped"},{"ok":true,"iccid":"missing","state":"config_missing"}]"""
        }

        fun socket(): CallTestSocket {
            connections.incrementAndGet()
            return CallTestSocket { socket, line ->
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
                        when (mode) {
                            Mode.Valid -> {
                                val root = Json.parseToJsonElement(producer).jsonObject.getValue("result").jsonObject
                                socket.respond(id, JsonObject(root + (key?.let { mapOf("instance" to buildJsonObject { put("key", it) }) } ?: emptyMap())))
                            }
                            Mode.Old -> socket.send(buildJsonObject {
                                put("id", id); put("error", buildJsonObject { put("code", 1001); put("message", "private-canary") })
                            })
                            Mode.Silent -> Unit
                        }
                    }
                    else -> error("unexpected RPC")
                }
            }
        }
    }

    companion object {
        private const val CANONICAL = """{"id":41,"result":{"schema":1,"protocol":{"version":1,"engineVersion":"0.1.0"},"instance":{"key":"00647499b77a2163531b4fa05e1014fb"},"capabilities":["config.metadata.v1","rpc.getConfig","rpc.getStatus","rpc.ping","rpc.stop"],"configMetadata":[{"field":"imsSipSecurity","value":"tcp_ipsec_3gpp","source":"default","decision":"inherited"},{"field":"paniInitial","value":"absent","source":"default","decision":"inherited"},{"field":"paniCountry","value":"","source":"home_mcc","decision":"inherited"},{"field":"imsDigestRealm","value":"","source":"identity","decision":"inherited"}]}}"""
    }
}
