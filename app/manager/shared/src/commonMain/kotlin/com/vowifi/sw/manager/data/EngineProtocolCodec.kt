package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineProtocolError
import com.vowifi.sw.manager.model.EngineProtocolFrame
import com.vowifi.sw.manager.model.EngineRuntimeStatus
import com.vowifi.sw.manager.model.EngineConnectionProgress
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object EngineProtocolCodec {
    const val PROTOCOL_VERSION = 1
    const val ERROR_VERSION_MISMATCH = 1000

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun request(id: Long, method: String, params: JsonObject = buildJsonObject {}): String {
        val message = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }
        return json.encodeToString(message) + '\n'
    }

    fun helloParams(): JsonObject = buildJsonObject {
        put("version", PROTOCOL_VERSION)
    }

    fun decode(line: String): EngineProtocolFrame {
        val envelope = json.decodeFromString<WireEnvelope>(line)
        return if (envelope.event != null) {
            EngineProtocolFrame.Event(envelope.event, envelope.data ?: buildJsonObject {})
        } else {
            EngineProtocolFrame.Response(envelope.id, envelope.result, envelope.error)
        }
    }

    fun status(response: EngineProtocolFrame.Response): EngineRuntimeStatus {
        val result = requireNotNull(response.result) { "getStatus response has no result" }
        val runtime = json.decodeFromJsonElement(EngineRuntimeStatus.serializer(), result)
        return runtime.copy(
            tunnelProgress = runtime.tunnelProgress.takeIf { it in TUNNEL_PROGRESS }.orEmpty(),
            imsProgress = runtime.imsProgress.takeIf { it in IMS_PROGRESS }.orEmpty(),
        )
    }

    fun helloVersion(response: EngineProtocolFrame.Response): Int {
        val result = requireNotNull(response.result) { "hello response has no result" }
        val version = result["version"]?.jsonPrimitive?.intOrNull
        require(version != null) { "hello response has no valid version" }
        return version
    }

    fun connectionProgress(event: EngineProtocolFrame.Event): EngineConnectionProgress? {
        if (event.name != "connectionProgress") return null
        val snapshot = try {
            json.decodeFromJsonElement(EngineConnectionProgress.serializer(), event.data)
        } catch (_: SerializationException) { return null }
        if (snapshot.sequence <= 0 || snapshot.tunnel !in setOf("DISCONNECTED", "CONNECTING", "CONNECTED", "BACKOFF", "FATAL") ||
            snapshot.ims !in setOf("UNREGISTERED", "REGISTERING", "REGISTERED")) return null
        return snapshot.copy(
            tunnelProgress = snapshot.tunnelProgress.takeIf { it in TUNNEL_PROGRESS }.orEmpty(),
            imsProgress = snapshot.imsProgress.takeIf { it in IMS_PROGRESS }.orEmpty(),
        )
    }
}

private val TUNNEL_PROGRESS = setOf("RESOLVING_EPDG", "IKE_NEGOTIATION", "EAP_AKA_AUTHENTICATION",
    "IKE_SA_INIT_RESPONSE", "EAP_CHALLENGE_RECEIVED", "SIM_AKA_COMPLETE", "SENDING_EAP_RESPONSE",
    "EAP_SUCCESS_RECEIVED", "CHILD_SA_NEGOTIATED", "DATAPLANE_READY", "ROUTING_READY")
private val IMS_PROGRESS = setOf("PREPARING_TRANSPORT", "SENDING_REGISTER", "AKA_AUTHENTICATION", "INSTALLING_SECURITY", "SENDING_AUTHENTICATED_REGISTER",
    "BUILDING_REGISTER", "REGISTER_CHALLENGE_RECEIVED", "BUILDING_AUTHORIZATION", "REGISTER_ACCEPTED")

@Serializable
private data class WireEnvelope(
    val id: Long = 0,
    val event: String? = null,
    val result: JsonObject? = null,
    val error: EngineProtocolError? = null,
    val data: JsonObject? = null,
)
