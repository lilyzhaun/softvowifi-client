package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

interface EngineMessageTransport {
    val events: SharedFlow<EngineProtocolFrame.Event>
    val messageDisconnects: Flow<Unit> get() = emptyFlow()
    suspend fun request(
        method: String,
        params: JsonObject = buildJsonObject {},
        timeoutMillis: Long = 5_000L,
    ): EngineProtocolFrame.Response
    suspend fun connect() {}
    fun retireMessages() {}
}

class EngineMessageRuntime(
    val listInstances: suspend () -> List<EngineInstanceState>,
    val transportFor: (String) -> EngineMessageTransport,
    val clock: () -> Long,
    val activeIccids: suspend () -> List<String?>? = { null },
    val lineLabelFor: (String) -> String? = { null },
)

internal class MessagePermissionException(message: String) : Exception(message)

internal class EngineRequestTimeoutException : Exception()

internal suspend fun <T : Any> withEngineRequestTimeout(timeoutMillis: Long, block: suspend () -> T): T =
    withTimeoutOrNull(timeoutMillis) { block() } ?: throw EngineRequestTimeoutException()
