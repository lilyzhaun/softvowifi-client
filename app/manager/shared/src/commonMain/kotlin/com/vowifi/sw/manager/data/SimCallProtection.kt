package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle

/** Only this data boundary compares transport owners; UI receives opaque handles and public slot indices. */
internal fun activeCallSimsFor(state: SimControlUiState, currentCall: CallUiState? = null,
    rawKeyFor: (SimHandle) -> String? = { null }): Map<SimHandle, Int?> {
    val instances = (state as? SimControlUiState.Ready)?.instances.orEmpty()
    val owner = (currentCall as? CallUiState.Ready)?.takeIf { it.phase != CallPhase.Idle }?.binding?.owner
    return instances.filter { instance ->
        val runtimeBusy = when (instance.runtime?.call) {
            "DIALING", "RINGING", "IN_CALL" -> true
            else -> false
        }
        runtimeBusy || owner != null && rawKeyFor(instance.handle) == owner
    }.associate { it.handle to it.slotIndex }
}
