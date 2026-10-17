package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimActiveJoinInput

internal sealed interface DnsEndpointDecision {
    data object NotNeeded : DnsEndpointDecision
    data object Unavailable : DnsEndpointDecision
    class Resolved(val endpoint: DnsEndpoint) : DnsEndpointDecision {
        override fun toString(): String = "Resolved"
    }
}

internal sealed interface DnsRepairResult {
    data object NoWork : DnsRepairResult
    data object Applied : DnsRepairResult
    class Failed(val state: ConfigurationUiState, val unconfirmed: Boolean = false) : DnsRepairResult {
        override fun toString(): String = "Failed"
    }
}

internal class AutoDnsPrepare(
    val endpoint: DnsEndpoint?,
    val existingRepair: DnsRepairResult,
) {
    override fun toString(): String = "AutoDnsPrepare"
}

internal class AndroidConfigurationDns(
    private val lookup: NetworkDnsLookup,
    private val catalog: AndroidConfigurationCatalog,
    private val commands: AndroidConfigurationCommands,
) {
    fun resolve(): DnsEndpoint? = lookup.resolve()

    fun unavailable(listing: ConfigurationUiState): ConfigurationUiState =
        ConfigurationEvaluator.preserveAfterAutoFailure(
            listing,
            ConfigurationEvaluator.failure(DNS_UNAVAILABLE),
        )

    suspend fun prepareAuto(
        active: List<SimActiveJoinInput>,
        existingKeys: Set<String>,
        state: ConfigurationUiState,
    ): AutoDnsPrepare? {
        val endpoint = when (val decision = endpointIfNeeded(active, existingKeys, state)) {
            DnsEndpointDecision.NotNeeded -> null
            DnsEndpointDecision.Unavailable -> return null
            is DnsEndpointDecision.Resolved -> decision.endpoint
        }
        val existingRepair = if (endpoint == null) {
            DnsRepairResult.NoWork
        } else {
            repairActive(active, state, endpoint)
        }
        return AutoDnsPrepare(endpoint, existingRepair)
    }

    suspend fun afterAutoBatch(
        active: List<SimActiveJoinInput>,
        prepared: AutoDnsPrepare,
        batch: AutoProvisionBatch,
        listing: ConfigurationUiState,
        onCreated: () -> Unit,
        onFailure: (ConfigurationUiState) -> Unit = {},
    ): ConfigurationUiState {
        batch.failure?.let(onFailure)
        if (batch.created) {
            runCatching { onCreated() }
            return finishAutoProvision(active, prepared.endpoint, created = true, onFailure = onFailure)
        }
        if (batch.existed) {
            val finished = finishAutoProvision(active, prepared.endpoint, created = false, onFailure = onFailure)
            val existedFailure = batch.failure
            return if (existedFailure != null) {
                ConfigurationEvaluator.preserveAfterAutoFailure(finished, existedFailure)
            } else {
                finished
            }
        }
        val preserved = if (prepared.existingRepair is DnsRepairResult.Applied) {
            catalog.load().state
        } else {
            listing
        }
        val failure = batch.failure
        return if (failure != null) {
            ConfigurationEvaluator.preserveAfterAutoFailure(preserved, failure)
        } else {
            preserved
        }
    }

    suspend fun finishAutoProvision(
        active: List<SimActiveJoinInput>,
        endpoint: DnsEndpoint?,
        created: Boolean,
        onFailure: (ConfigurationUiState) -> Unit = {},
    ): ConfigurationUiState {
        val reloaded = catalog.load()
        val repaired = if (endpoint == null) {
            reloaded.state
        } else {
            when (val result = repairActive(active, reloaded.state, endpoint)) {
                is DnsRepairResult.Failed -> {
                    onFailure(result.state)
                    return ConfigurationEvaluator.preserveAfterAutoFailure(reloaded.state, result.state)
                }
                DnsRepairResult.Applied -> catalog.load().state
                DnsRepairResult.NoWork -> reloaded.state
            }
        }
        return if (created) {
            ConfigurationEvaluator.readyAfterAutoCreate(repaired)
        } else {
            repaired
        }
    }

    suspend fun finishManualExisting(
        current: ConfigurationUiState,
        iccid: String,
        endpoint: DnsEndpoint?,
        onRepair: (DnsRepairResult) -> Unit = {},
    ): ConfigurationUiState {
        if (endpoint != null) {
            val result = repairKey(iccid, current, endpoint)
            onRepair(result)
            when (result) {
                is DnsRepairResult.Failed ->
                    return ConfigurationEvaluator.preserveAfterAutoFailure(current, result.state)
                DnsRepairResult.Applied ->
                    return ConfigurationEvaluator.readyAfterApply(catalog.load().state)
                DnsRepairResult.NoWork -> Unit
            }
        }
        return catalog.load().state
    }

    suspend fun finishManualCreated(
        iccid: String,
        endpoint: DnsEndpoint?,
        created: Boolean = true,
        onRepair: (DnsRepairResult) -> Unit = {},
    ): ConfigurationUiState {
        val reloaded = catalog.load()
        if (endpoint == null) {
            return if (created) {
                ConfigurationEvaluator.readyAfterCreate(reloaded.state)
            } else {
                reloaded.state
            }
        }
        val result = repairKey(iccid, reloaded.state, endpoint)
        onRepair(result)
        return when (result) {
            is DnsRepairResult.Failed ->
                ConfigurationEvaluator.preserveAfterAutoFailure(reloaded.state, result.state)
            DnsRepairResult.Applied -> {
                val refreshed = catalog.load().state
                if (created) {
                    ConfigurationEvaluator.readyAfterCreate(refreshed)
                } else {
                    ConfigurationEvaluator.readyAfterApply(refreshed)
                }
            }
            DnsRepairResult.NoWork -> if (created) {
                ConfigurationEvaluator.readyAfterCreate(reloaded.state)
            } else {
                reloaded.state
            }
        }
    }

    private fun endpointIfNeeded(
        active: List<SimActiveJoinInput>,
        existingKeys: Set<String>,
        state: ConfigurationUiState,
    ): DnsEndpointDecision {
        val rows = DnsWorkPlanner.rows(state, catalog::iccidFor)
        if (!DnsWorkPlanner.needsEndpoint(active, existingKeys, rows)) {
            return DnsEndpointDecision.NotNeeded
        }
        val endpoint = lookup.resolve() ?: return DnsEndpointDecision.Unavailable
        return DnsEndpointDecision.Resolved(endpoint)
    }

    private suspend fun repairActive(
        active: List<SimActiveJoinInput>,
        state: ConfigurationUiState,
        endpoint: DnsEndpoint,
    ): DnsRepairResult = applyRepairs(
        DnsWorkPlanner.repairs(active, DnsWorkPlanner.rows(state, catalog::iccidFor), endpoint),
    )

    private suspend fun repairKey(
        iccid: String,
        state: ConfigurationUiState,
        endpoint: DnsEndpoint,
    ): DnsRepairResult {
        val row = DnsWorkPlanner.rows(state, catalog::iccidFor)
            .firstOrNull { it.key == iccid && it.dns.isBlank() }
            ?: return DnsRepairResult.NoWork
        return applyRepairs(
            listOf(
                DnsRepair(
                    instanceId = row.instanceId,
                    key = row.key,
                    overrides = DnsWorkPlanner.overridesFor(row, endpoint),
                ),
            ),
        )
    }

    private suspend fun applyRepairs(repairs: List<DnsRepair>): DnsRepairResult {
        if (repairs.isEmpty()) return DnsRepairResult.NoWork
        for (repair in repairs) {
            val payload = try {
                ConfigurationCodec.encodeChanges(
                    repair.overrides.copy(dns = ""),
                    repair.overrides,
                )
            } catch (error: ConfigurationFormatException) {
                return DnsRepairResult.Failed(
                    ConfigurationEvaluator.failure(error.message ?: ConfigurationEvaluator.failure().message),
                )
            }
            when (val applied = commands.apply(repair.key, payload)) {
                is CommandRead.Listed -> Unit
                is CommandRead.Mapped -> return DnsRepairResult.Failed(applied.state, applied.writeUnconfirmed)
                is CommandRead.Snapshot,
                CommandRead.Created,
                -> return DnsRepairResult.Failed(ConfigurationEvaluator.failure())
            }
        }
        return DnsRepairResult.Applied
    }
}

private const val DNS_UNAVAILABLE = "底层网络 DNS 不可用"
