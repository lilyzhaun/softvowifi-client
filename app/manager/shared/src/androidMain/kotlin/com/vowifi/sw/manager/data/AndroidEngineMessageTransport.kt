package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonObject

internal class AndroidEngineMessageTransport(
    pool: AndroidEngineClientPool,
    iccid: String,
) : EngineMessageTransport {
    private val client = pool.clientFor(iccid)
    @Volatile private var connection: LocalSocketEngineClient.MessageConnection? = null
    override val events: SharedFlow<EngineProtocolFrame.Event>
        get() = client.events
    override val messageDisconnects get() = client.disconnected

    override suspend fun request(
        method: String,
        params: JsonObject,
        timeoutMillis: Long,
    ): EngineProtocolFrame.Response = checkNotNull(connection) { "message connection not ready" }.request(method, params, timeoutMillis)

    override suspend fun connect() {
        if (connection == null) connection = client.connectForMessages()
    }

    override fun retireMessages() { connection?.retire() }
}
