package com.vowifi.sw.manager.data

import android.net.LocalSocket

class AndroidEngineClientPool(private val socketFactory: () -> LocalSocket = { LocalSocket() }) : AutoCloseable {
    private val lock = Any()
    private val clients = mutableMapOf<String, LocalSocketEngineClient>()
    private var closed = false

    internal fun clientFor(iccid: String): LocalSocketEngineClient = synchronized(lock) {
        check(!closed) { "engine client pool is closed" }
        clients.getOrPut(iccid) { LocalSocketEngineClient(socketPath(iccid), socketFactory) }
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
