package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimHandle
import kotlinx.coroutines.flow.StateFlow

interface ConfigurationSource {
    val state: StateFlow<ConfigurationUiState>
    fun refresh()
    fun submit(
        instanceId: ConfigurationInstanceId,
        overrides: ConfigurationOverrides,
        onComplete: (ConfigurationApplyOutcome) -> Unit,
    ): ConfigurationApplyAcceptance
    fun provision(handle: SimHandle) = Unit
    fun close() = Unit
}
