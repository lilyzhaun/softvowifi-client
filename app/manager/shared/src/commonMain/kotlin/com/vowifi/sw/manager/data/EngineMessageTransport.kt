package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

interface EngineMessageTransport {
    val events: SharedFlow<EngineProtocolFrame.Event>
    suspend fun request(
        method: String,
        params: JsonObject = buildJsonObject {},
        timeoutMillis: Long = 5_000L,
    ): EngineProtocolFrame.Response
    suspend fun connect() {}
}

class EngineMessageRuntime(
    val listInstances: suspend () -> List<EngineInstanceState>,
    val transportFor: (String) -> EngineMessageTransport,
    val clock: () -> Long,
)

internal class MessagePermissionException(message: String) : Exception(message)

internal class EngineRequestTimeoutException : Exception()

internal suspend fun <T : Any> withEngineRequestTimeout(timeoutMillis: Long, block: suspend () -> T): T =
    withTimeoutOrNull(timeoutMillis) { block() } ?: throw EngineRequestTimeoutException()
