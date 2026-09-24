package com.vowifi.sw.manager.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class EngineProtocolError(
    val code: Int,
    val message: String,
)

sealed interface EngineProtocolFrame {
    data class Response(
        val id: Long,
        val result: JsonObject?,
        val error: EngineProtocolError?,
    ) : EngineProtocolFrame

    data class Event(
        val name: String,
        val data: JsonObject,
    ) : EngineProtocolFrame
}

@Serializable
data class EngineRuntimeStatus(
    val tunnel: String,
    val tunnelDesc: String = "",
    val reconnects: Long,
    val ims: String,
    val call: String,
    val transport: String,
    val pcscf: List<String>,
)

enum class EngineLifecycle {
    CONFIG_MISSING,
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
}

data class EngineInstanceState(
    val iccid: String,
    val lifecycle: EngineLifecycle,
    val pid: Long? = null,
    val runtime: EngineRuntimeStatus? = null,
    val message: String? = null,
    val clientCapabilities: ClientCapabilitiesResult? = null,
)

sealed interface EngineControlUiState {
    data object Loading : EngineControlUiState

    data class Ready(
        val instances: List<EngineInstanceState>,
    ) : EngineControlUiState

    data class PermissionDenied(
        val message: String,
    ) : EngineControlUiState

    data class Failure(
        val message: String,
    ) : EngineControlUiState
}
