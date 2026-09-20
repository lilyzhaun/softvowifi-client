package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.RegistrationUiState
import kotlinx.coroutines.flow.StateFlow

interface ObserverLogSource {
    val state: StateFlow<RegistrationUiState>
    fun refresh()
    fun close() = Unit
}
