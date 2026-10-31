package com.vowifi.sw.manager.data

import android.net.LocalSocket
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonObject

class AndroidCallControlTransport(
    private val socketFactory: (String) -> LocalSocket = { com.vowifi.sw.root.RootBridge.openSocket(it) },
) : CallControlTransport {
    override suspend fun request(owner: String, method: String, params: JsonObject, timeout: Long): EngineProtocolFrame.Response {
        val client = LocalSocketEngineClient("/data/local/tmp/softvowifi/$owner.sock", socketFactory)
        return try { withEngineRequestTimeout(timeout) { client.request(method, params, timeout) } } finally { client.close() }
    }
}

class AndroidCallEventTransport(
    owner: String,
    socketFactory: (String) -> LocalSocket = { com.vowifi.sw.root.RootBridge.openSocket(it) },
) : CallEventTransport {
    private val client = LocalSocketEngineClient("/data/local/tmp/softvowifi/$owner.sock", socketFactory)
    override val events: SharedFlow<EngineProtocolFrame.Event> get() = client.events
    override val disconnected: SharedFlow<Unit> get() = client.disconnected
    override suspend fun connect() = client.connect()
    override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long) =
        client.request(method, params, timeoutMillis)
    override fun close() = client.close()
}
