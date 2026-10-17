package com.vowifi.sw.manager.model

data class SimHandle(val value: String)

enum class SimPresence {
    ACTIVE_MATCHED,
    ACTIVE_UNCONFIGURED,
    REMOVED_CONFIG_RETAINED,
}

data class SimControlInstance(
    val handle: SimHandle,
    val displayLabel: String,
    val presence: SimPresence,
    val slotIndex: Int?,
    val carrierLabel: String?,
    val mcc: String?,
    val mnc: String?,
    val lifecycle: EngineLifecycle,
    val pid: Long? = null,
    val runtime: EngineRuntimeStatus? = null,
    val message: String? = null,
    val canStart: Boolean,
    val canStop: Boolean,
    val canApply: Boolean,
    val canProvision: Boolean = false,
    val homeCarrierLabel: String? = null,
    val operationPending: Boolean = false,
    val controlCompletion: EngineControlCompletion? = null,
)

sealed interface SimControlUiState {
    data object Loading : SimControlUiState

    data class Ready(
        val instances: List<SimControlInstance>,
        val message: String? = null,
    ) : SimControlUiState

    data class PermissionDenied(
        val message: String,
    ) : SimControlUiState

    data class Failure(
        val message: String,
    ) : SimControlUiState
}

internal data class SimActiveJoinInput(
    val key: String,
    val slotIndex: Int,
    val carrierName: String? = null,
    val displayName: String? = null,
    val mcc: String? = null,
    val mnc: String? = null,
    val lastFour: String? = null,
    val homeCarrierLabel: String? = null,
)

internal data class SimEngineJoinInput(
    val key: String,
    val lifecycle: EngineLifecycle,
    val displayLabel: String = "",
    val pid: Long? = null,
    val runtime: EngineRuntimeStatus? = null,
    val message: String? = null,
    val operationPending: Boolean = false,
    val controlCompletion: EngineControlCompletion? = null,
)
