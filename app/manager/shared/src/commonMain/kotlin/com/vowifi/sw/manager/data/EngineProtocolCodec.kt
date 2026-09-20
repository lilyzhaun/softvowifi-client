package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineProtocolError
import com.vowifi.sw.manager.model.EngineProtocolFrame
import com.vowifi.sw.manager.model.EngineRuntimeStatus
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
        return json.decodeFromJsonElement(EngineRuntimeStatus.serializer(), result)
    }

    fun helloVersion(response: EngineProtocolFrame.Response): Int {
        val result = requireNotNull(response.result) { "hello response has no result" }
        val version = result["version"]?.jsonPrimitive?.intOrNull
        require(version != null) { "hello response has no valid version" }
        return version
    }
}

@Serializable
private data class WireEnvelope(
    val id: Long = 0,
    val event: String? = null,
    val result: JsonObject? = null,
    val error: EngineProtocolError? = null,
    val data: JsonObject? = null,
)
