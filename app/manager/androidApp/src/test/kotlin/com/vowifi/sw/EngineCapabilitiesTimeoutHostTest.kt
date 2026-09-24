package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidCallEventTransport
import com.vowifi.sw.manager.data.EngineClientCapabilitiesSource
import com.vowifi.sw.manager.model.ClientCapabilitiesFailure
import com.vowifi.sw.manager.model.ClientCapabilitiesResult
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class EngineCapabilitiesTimeoutHostTest {
    @Test
    fun innerLocalSocketTimeoutIsClassifiedAsTimeout() = runBlocking {
        // Given
        withClient(Reply.Silent) { client ->
            val source = EngineClientCapabilitiesSource { method, params -> client.request(method, params, 10) }
            // When
            val result = source.query()
            // Then
            assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Timeout), result)
        }
    }

    @Test
    fun productionEqualTimeoutBudgetsAreClassifiedAsTimeout() = runBlocking {
        // Given
        withClient(Reply.Silent) { client ->
            val source = EngineClientCapabilitiesSource { method, params ->
                client.request(method, params, EngineClientCapabilitiesSource.TIMEOUT_MILLIS)
            }
            // When
            val result = source.query()
            // Then
            assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Timeout), result)
        }
    }

    @Test
    fun parentTimeoutPropagatesThroughActualClient() {
        // Given / When / Then
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withClient(Reply.Silent) { client ->
                    val source = EngineClientCapabilitiesSource { method, params -> client.request(method, params, 250) }
                    withTimeout(50) { source.query() }
                }
            }
        }
    }

    @Test
    fun oldCoreRemainsUnsupported() = runBlocking {
        // Given
        withClient(Reply.OldCore) { client ->
            val source = EngineClientCapabilitiesSource { method, params -> client.request(method, params, 250) }
            // When
            val result = source.query()
            // Then
            assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported), result)
        }
    }

    @Test
    fun ioFailureRemainsTransportWithoutEcho() = runBlocking {
        // Given
        withClient(Reply.IoFailure) { client ->
            val source = EngineClientCapabilitiesSource { method, params -> client.request(method, params, 250) }
            // When
            val result = source.query()
            // Then
            assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Transport), result)
        }
    }

    private enum class Reply { Silent, OldCore, IoFailure }

    private suspend fun withClient(reply: Reply, block: suspend (AndroidCallEventTransport) -> Unit) {
        val client = AndroidCallEventTransport("synthetic-owner") {
            CallTestSocket { socket, line ->
                val request = Json.parseToJsonElement(line).jsonObject
                val id = request.getValue("id").jsonPrimitive.long
                when (request.getValue("method").jsonPrimitive.content) {
                    "hello" -> socket.respond(id, buildJsonObject { put("version", 1) })
                    "getClientCapabilities" -> when (reply) {
                        Reply.Silent -> Unit
                        Reply.OldCore -> socket.send(buildJsonObject {
                            put("id", id)
                            put("error", buildJsonObject { put("code", 1001); put("message", "private-canary") })
                        })
                        Reply.IoFailure -> throw IOException("private-canary")
                    }
                    else -> error("unexpected RPC")
                }
            }
        }
        try { client.connect(); block(client) } finally { client.close() }
    }
}
