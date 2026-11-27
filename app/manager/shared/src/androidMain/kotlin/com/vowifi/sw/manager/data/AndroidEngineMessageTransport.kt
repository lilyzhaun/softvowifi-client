package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineProtocolFrame
import java.io.EOFException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonObject

internal class AndroidEngineMessageTransport(
    pool: AndroidEngineClientPool,
    iccid: String,
) : EngineMessageTransport {
    private val client = pool.clientFor(iccid)
    private val lock = Any()
    private var retired = false
    private var connection: LocalSocketEngineClient.MessageConnection? = null
    override val events: SharedFlow<EngineProtocolFrame.Event>
        get() = client.events
    override val messageDisconnects get() = client.disconnected

    override suspend fun request(
        method: String,
        params: JsonObject,
        timeoutMillis: Long,
    ): EngineProtocolFrame.Response {
        val active = synchronized(lock) {
            if (retired) throw EOFException("message connection retired")
            checkNotNull(connection) { "message connection not ready" }
        }
        return active.request(method, params, timeoutMillis)
    }

    override suspend fun connect() {
        synchronized(lock) {
            if (retired) throw EOFException("message connection retired")
            if (connection != null) return
        }
        val opened = client.connectForMessages()
        var closeOpened = false
        var retiredNow = false
        synchronized(lock) {
            when {
                retired -> {
                    retiredNow = true
                    closeOpened = true
                }
                connection == null -> connection = opened
                else -> closeOpened = true
            }
        }
        if (closeOpened) opened.retire()
        if (retiredNow) throw EOFException("message connection retired")
    }

    override fun retireMessages() {
        val current = synchronized(lock) {
            retired = true
            connection
        }
        current?.retire()
    }
}
