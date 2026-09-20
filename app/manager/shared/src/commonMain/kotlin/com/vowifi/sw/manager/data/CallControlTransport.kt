package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonObject

fun interface CallControlTransport {
    suspend fun request(owner: String, method: String, params: JsonObject, timeout: Long): EngineProtocolFrame.Response
}
interface CallEventTransport : EngineMessageTransport {
    val disconnected: SharedFlow<Unit>
    fun close()
}
