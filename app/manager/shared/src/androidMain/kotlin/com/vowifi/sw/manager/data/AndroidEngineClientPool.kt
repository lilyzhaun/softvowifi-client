package com.vowifi.sw.manager.data

import android.net.LocalSocket
import com.vowifi.sw.manager.model.ClientCapabilitiesFailure
import com.vowifi.sw.manager.model.ClientCapabilitiesResult
import com.vowifi.sw.manager.model.resolvedSmsc
import com.vowifi.sw.manager.model.ConfigurationRuntimeFacts
import com.vowifi.sw.manager.model.ConfigurationFormatException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

class AndroidEngineClientPool(private val socketFactory: (String) -> LocalSocket = { com.vowifi.sw.root.RootBridge.openSocket(it) }) : AutoCloseable {
    private val lock = Any()
    private val clients = mutableMapOf<String, LocalSocketEngineClient>()
    private var closed = false
    @Volatile internal var onRuntimeFactsChange: ((String, EngineRuntimeFactsChange) -> Unit)? = null

    internal fun clientFor(iccid: String): LocalSocketEngineClient = synchronized(lock) {
        check(!closed) { "engine client pool is closed" }
        clients.getOrPut(iccid) { createClient(iccid) }
    }

    private fun createClient(iccid: String): LocalSocketEngineClient {
        val client = LocalSocketEngineClient(socketPath(iccid), socketFactory)
        client.onRuntimeFactsChange = { change ->
            val owned = EngineRuntimeFactsChange(change.sequence, change.connection) {
                change.isCurrent() && synchronized(lock) { !closed && clients[iccid] === client }
            }
            if (owned.isCurrent()) onRuntimeFactsChange?.invoke(iccid, owned)
        }
        return client
    }

    internal fun connectionProgress(iccid: String) = clientFor(iccid).connectionProgress

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
        val connection = client.existingConnection() ?: return null
        val response = try {
            withTimeoutOrNull(1_500) { connection.request("getConfig", buildJsonObject {}, 1_000) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: IOException) { null
        } catch (_: EngineRequestTimeoutException) { null
        } catch (_: ProtocolVersionMismatchException) { null
        } catch (_: IllegalStateException) { null } // The shared client may close during a refresh.
        val raw = if (response?.error == null) response?.result?.get("smsc") as? JsonPrimitive else null
        val value = raw?.takeIf { it.isString }?.content?.let { resolvedSmsc(it, "").takeIf(String::isNotEmpty) }
        return synchronized(lock) { value.takeIf { !closed && clients[iccid] === client && connection.isCurrent() } }
    }

    internal suspend fun configurationFacts(iccid: String): ConfigurationRuntimeFacts? = configurationObservation(iccid)?.facts

    internal suspend fun configurationObservation(iccid: String): ConfigurationRuntimeObservation? {
        val client = synchronized(lock) { clients[iccid] } ?: return null
        val connection = client.existingConnection() ?: return null
        val received = try {
            withTimeoutOrNull(1_500) { connection.requestWithReceipt("getStatus", buildJsonObject {}, 1_000) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: IOException) { null
        } catch (_: EngineRequestTimeoutException) { null
        } catch (_: IllegalStateException) { null }
        if (received == null) return null
        val response = received.frame
        val facts = if (response.error == null) response.result?.let {
            try { ConfigurationFactsCodec.runtime(it) } catch (_: ConfigurationFormatException) { null }
        } else null
        val observation = ConfigurationRuntimeObservation(facts, connection.owner, received.receipt) {
            connection.isCurrent() && synchronized(lock) { !closed && clients[iccid] === client }
        }
        return observation.takeIf { it.isCurrent() }
    }

    /** One captured connection and one write attempt; no connect, handshake or fallback restart. */
    internal suspend fun applyCallPolicy(iccid: String, payload: String, allowed: () -> Boolean): ConfigurationNextCallResult {
        val unconfirmed = ConfigurationNextCallResult(ConfigurationCommandApplyResult.Unconfirmed)
        val client = synchronized(lock) { clients[iccid] } ?: return unconfirmed
        val connection = client.existingConnection() ?: return unconfirmed
        fun current() = allowed() && connection.isCurrent() && synchronized(lock) { !closed && clients[iccid] === client }
        if (!current()) return unconfirmed
        return try {
            // The source supplies the already validated codec payload. Retain its
            // exact atomic group to correlate the acknowledgment, not defaults.
            val params = Json.parseToJsonElement(payload) as? JsonObject ?: return unconfirmed
            val expected = (params["expectedRevision"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf(::validConfigRevision) ?: return unconfirmed
            val overrides = params["overrides"] as? JsonObject ?: return unconfirmed
            if (params.keys != setOf("expectedRevision", "overrides") ||
                overrides.isEmpty() || overrides.keys.any { it !in setOf("callSignaling", "callMedia") } ||
                overrides.values.any { it !is JsonObject && it != JsonNull }
            ) return unconfirmed
            val required = buildSet {
                add("config.metadata.v1"); add("rpc.setConfig")
                if ("callSignaling" in overrides) add("config.call-signaling.next-call.v1")
                if ("callMedia" in overrides) add("config.call-media.next-call.v1")
                if ((overrides["callMedia"] as? JsonObject)?.containsKey("packetization") == true) add("config.call-media.packetization.v1")
                if ((overrides["callMedia"] as? JsonObject)?.containsKey("inactivity") == true) add("config.call-media.inactivity.v1")
                if ((overrides["callMedia"] as? JsonObject)?.containsKey("security") == true) add("config.call-media.sdes.v1")
                val formats = (overrides["callMedia"] as? JsonObject)?.get("speechFormats") as? JsonArray
                if (formats?.any { ((it as? JsonObject)?.get("codec") as? JsonPrimitive)?.content == "pcmu" } == true) add("config.call-media.pcmu.v1")
            }
            withTimeoutOrNull(3_500) {
                val capabilityReply = connection.request("getClientCapabilities", buildJsonObject {
                    put("schema", 1); put("required", JsonArray(required.sorted().map(::JsonPrimitive)))
                }, EngineClientCapabilitiesSource.TIMEOUT_MILLIS)
                if (!current()) return@withTimeoutOrNull unconfirmed
                val capabilities = EngineClientCapabilitiesCodec.decode(capabilityReply, required)
                if (capabilities !is ClientCapabilitiesResult.Ready) {
                    return@withTimeoutOrNull if (capabilities == ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported))
                        ConfigurationNextCallResult(ConfigurationCommandApplyResult.Failed) else unconfirmed
                }
                if (!current()) return@withTimeoutOrNull unconfirmed
                val response = connection.request("setConfig", params, 2_000)
                if (!current()) return@withTimeoutOrNull unconfirmed
                configurationNextCallResult(response, expected, overrides)
            } ?: unconfirmed
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: IOException) { unconfirmed
        } catch (_: EngineRequestTimeoutException) { unconfirmed
        } catch (_: IllegalStateException) { unconfirmed // The captured client may retire during either request.
        } catch (_: SerializationException) { unconfirmed
        } catch (_: ConfigurationFormatException) { unconfirmed }
    }

    internal fun remove(iccid: String): LocalSocketEngineClient? = synchronized(lock) {
        clients.remove(iccid)
    }

    override fun close() {
        val active = synchronized(lock) {
            closed = true
            onRuntimeFactsChange = null
            clients.values.toList().also { clients.clear() }
        }
        active.forEach(LocalSocketEngineClient::close)
    }

    private fun socketPath(iccid: String): String = "/data/local/tmp/softvowifi/$iccid.sock"
}
