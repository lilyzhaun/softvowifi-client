package com.vowifi.sw.manager

import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineLifecycle

internal data class EngineCallAttachmentSignature(
    val attachmentCount: Int,
    val singleInstanceKey: String?,
)

internal fun engineCallAttachmentSignature(state: EngineControlUiState): EngineCallAttachmentSignature? {
    if (state !is EngineControlUiState.Ready) return null
    val available = state.instances.filter { instance ->
        instance.lifecycle == EngineLifecycle.RUNNING || instance.lifecycle == EngineLifecycle.STOPPING
    }
    return EngineCallAttachmentSignature(
        attachmentCount = available.size,
        singleInstanceKey = available.singleOrNull()?.iccid,
    )
}

internal fun shouldRefreshCallsForEngineAvailability(
    previous: EngineCallAttachmentSignature?,
    current: EngineCallAttachmentSignature?,
): Boolean = current != null && current != previous
