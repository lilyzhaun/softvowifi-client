package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonObject

internal class AndroidEngineMessageTransport(
    private val pool: AndroidEngineClientPool,
    private val iccid: String,
) : EngineMessageTransport {
    override val events: SharedFlow<EngineProtocolFrame.Event>
        get() = pool.clientFor(iccid).events

    override suspend fun request(
        method: String,
        params: JsonObject,
        timeoutMillis: Long,
    ): EngineProtocolFrame.Response = pool.clientFor(iccid).request(method, params, timeoutMillis)

    override suspend fun connect() {
        pool.clientFor(iccid).connect()
    }
}
