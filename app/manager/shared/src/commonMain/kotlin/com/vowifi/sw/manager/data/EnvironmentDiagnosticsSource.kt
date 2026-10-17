package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import kotlinx.coroutines.flow.StateFlow

interface EnvironmentDiagnosticsSource {
    val state: StateFlow<EnvironmentDiagnosticsUiState>
    fun refresh()
    suspend fun refreshAndAwait(): RefreshResult = RefreshResult.Unsupported
    fun close() = Unit
}
