package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.SimActiveJoinInput
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.CallUiState
import kotlinx.coroutines.flow.StateFlow

interface SimControlSource {
    val state: StateFlow<SimControlUiState>
    fun refresh()
    suspend fun refreshAndAwait(): RefreshResult = RefreshResult.Unsupported
    fun start(handle: SimHandle)
    fun stop(handle: SimHandle)
    fun activeCallSims(currentCall: CallUiState): Map<SimHandle, Int?> = activeCallSimsFor(state.value)
    fun close() = Unit
}

internal fun interface SubscriptionWatch {
    fun stop()
}

internal interface SubscriptionInventory {
    fun listActive(): SubscriptionInventoryResult
    fun startWatching(onChanged: () -> Unit): SubscriptionWatch
}

internal sealed interface SubscriptionInventoryResult {
    data class Active(
        val subscriptions: List<SimActiveJoinInput>,
    ) : SubscriptionInventoryResult

    data class PermissionDenied(
        val message: String,
    ) : SubscriptionInventoryResult

    data class Failure(
        val message: String,
    ) : SubscriptionInventoryResult
}
