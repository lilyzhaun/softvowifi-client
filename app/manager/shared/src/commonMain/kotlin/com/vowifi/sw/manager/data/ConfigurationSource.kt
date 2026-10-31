package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.ConfigurationProvisionState
import com.vowifi.sw.manager.model.SimHandle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private val noProvisioning: StateFlow<ConfigurationProvisionState> = MutableStateFlow(ConfigurationProvisionState()).asStateFlow()

interface ConfigurationSource {
    val state: StateFlow<ConfigurationUiState>
    val provisioning: StateFlow<ConfigurationProvisionState> get() = noProvisioning
    fun refresh()
    suspend fun refreshAndAwait(): RefreshResult = RefreshResult.Unsupported
    /** Explicit user action; ordinary observation/refresh must not rebind SIM identities. */
    fun refreshIdentities() = refresh()
    suspend fun refreshIdentitiesAndAwait(): RefreshResult = RefreshResult.Unsupported
    fun submit(
        instanceId: ConfigurationInstanceId,
        overrides: ConfigurationOverrides,
        onComplete: (ConfigurationApplyOutcome) -> Unit,
    ): ConfigurationApplyAcceptance
    fun provision(handle: SimHandle) = Unit
    val supportsRebuild: Boolean get() = false
    /** Destructive explicit decision only; never called from refresh/provision. */
    fun rebuild(handle: SimHandle): Boolean = false
    fun close() = Unit
}
