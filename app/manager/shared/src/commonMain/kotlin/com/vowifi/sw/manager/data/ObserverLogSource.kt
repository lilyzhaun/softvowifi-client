package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.RegistrationUiState
import kotlinx.coroutines.flow.StateFlow

interface ObserverLogSource {
    val state: StateFlow<RegistrationUiState>
    fun refresh()
    suspend fun refreshAndAwait(): RefreshResult = RefreshResult.Unsupported
    fun close() = Unit
}
