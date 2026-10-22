package com.vowifi.sw.manager.data

import android.net.LocalSocket
import com.vowifi.sw.manager.model.ClientCapabilitiesFailure
import com.vowifi.sw.manager.model.ClientCapabilitiesResult
import com.vowifi.sw.manager.model.resolvedSmsc
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

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

    /** Read only an existing instance; a missing/retired connection cannot provide an automatic value. */
    internal suspend fun automaticSmsc(iccid: String): String? {
        val client = synchronized(lock) { clients[iccid] } ?: return null
        val response = try {
            withTimeoutOrNull(1_500) { client.request("getConfig", timeoutMillis = 1_000) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: IOException) { null
        } catch (_: EngineRequestTimeoutException) { null
        } catch (_: ProtocolVersionMismatchException) { null
        } catch (_: IllegalStateException) { null } // The shared client may close during a refresh.
        val raw = if (response?.error == null) response?.result?.get("smsc") as? JsonPrimitive else null
        val value = raw?.takeIf { it.isString }?.content?.let { resolvedSmsc(it, "").takeIf(String::isNotEmpty) }
        return synchronized(lock) { value.takeIf { !closed && clients[iccid] === client } }
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
