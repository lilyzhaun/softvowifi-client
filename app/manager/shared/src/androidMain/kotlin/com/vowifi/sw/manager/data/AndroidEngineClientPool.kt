package com.vowifi.sw.manager.data

import android.net.LocalSocket
import com.vowifi.sw.manager.model.ClientCapabilitiesFailure
import com.vowifi.sw.manager.model.ClientCapabilitiesResult

class AndroidEngineClientPool(private val socketFactory: () -> LocalSocket = { LocalSocket() }) : AutoCloseable {
    private val lock = Any()
    private val clients = mutableMapOf<String, LocalSocketEngineClient>()
    private var closed = false

    internal fun clientFor(iccid: String): LocalSocketEngineClient = synchronized(lock) {
        check(!closed) { "engine client pool is closed" }
        clients.getOrPut(iccid) { LocalSocketEngineClient(socketPath(iccid), socketFactory) }
    }

    internal suspend fun clientCapabilities(iccid: String): ClientCapabilitiesResult {
        val client = synchronized(lock) { clients[iccid] }
            ?: return ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Transport)
        val result = EngineClientCapabilitiesSource { method, params ->
            client.request(method, params, EngineClientCapabilitiesSource.TIMEOUT_MILLIS)
        }.query()
        return synchronized(lock) {
            if (!closed && clients[iccid] === client) result
            else ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Transport)
        }
    }

    internal fun remove(iccid: String): LocalSocketEngineClient? = synchronized(lock) {
        clients.remove(iccid)
    }

    override fun close() {
        val active = synchronized(lock) {
            closed = true
            clients.values.toList().also { clients.clear() }
        }
        active.forEach(LocalSocketEngineClient::close)
    }

    private fun socketPath(iccid: String): String = "/data/local/tmp/vowifi/$iccid.sock"
}
