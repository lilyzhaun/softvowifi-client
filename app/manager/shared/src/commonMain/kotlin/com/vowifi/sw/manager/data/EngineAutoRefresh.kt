package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val ENGINE_AUTO_REFRESH_INTERVAL_MILLIS = 3_000L

internal fun engineStateNeedsAutoRefresh(state: EngineControlUiState): Boolean = when (state) {
    EngineControlUiState.Loading -> false
    is EngineControlUiState.Failure -> false
    is EngineControlUiState.PermissionDenied -> false
    is EngineControlUiState.Ready -> state.instances.any { instance ->
        when (instance.lifecycle) {
            EngineLifecycle.CONFIG_MISSING, EngineLifecycle.STOPPED -> false
            EngineLifecycle.STARTING, EngineLifecycle.RUNNING, EngineLifecycle.STOPPING -> true
        }
    }
}

internal fun CoroutineScope.launchEngineAutoRefresh(
    state: StateFlow<EngineControlUiState>,
    intervalMillis: Long = ENGINE_AUTO_REFRESH_INTERVAL_MILLIS,
    refresh: () -> Unit,
): Job = launch {
    while (isActive) {
        delay(intervalMillis)
        if (engineStateNeedsAutoRefresh(state.value)) refresh()
    }
}
