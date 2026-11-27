package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationSnapshot
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.ConfigurationNetworkOutlets
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationRuntimeFacts
import com.vowifi.sw.manager.model.SubscriptionDiscovery
import com.vowifi.sw.manager.model.SimHandle

internal class AndroidConfigurationCatalog(
    private val commands: AndroidConfigurationCommands,
    private val discovery: AndroidSubscriptionDiscovery,
    private val handles: OpaqueIccidHandleStore,
    private val ownerFor: (String) -> SimOwnerId? = { null },
    private val networkOutlets: () -> ConfigurationNetworkOutlets = { ConfigurationNetworkOutlets() },
    private val readAutomaticSmsc: suspend (String) -> String? = { null },
    private val readRuntimeFacts: suspend (String) -> ConfigurationRuntimeObservation? = { null },
) {
    private val lock = Any()
    private val iccidByHandle = linkedMapOf<String, String>()

    fun iccidFor(instanceId: ConfigurationInstanceId): String? =
        synchronized(lock) { iccidByHandle[instanceId.value]?.takeIf { handles.keyFor(SimHandle(instanceId.value)) == it } }

    fun existingKeys(): Set<String> = synchronized(lock) { iccidByHandle.values.toSet() }

    fun clear() {
        synchronized(lock) { iccidByHandle.clear() }
    }

    suspend fun load(): ConfigurationCatalogSnapshot {
        val listed = when (val listed = commands.list()) {
            is CommandRead.Mapped -> {
                replaceHandles(emptyList())
                return ConfigurationCatalogSnapshot(listed.state, emptySet())
            }
            is CommandRead.Snapshot, is CommandRead.Created -> {
                replaceHandles(emptyList())
                return ConfigurationCatalogSnapshot(ConfigurationEvaluator.failure(), emptySet())
            }
            is CommandRead.Listed -> listed.iccids
        }
        if (listed.isEmpty()) {
            discovery.discover(emptyList())
            replaceHandles(emptyList())
            return ConfigurationCatalogSnapshot(ConfigurationUiState.Empty, emptySet())
        }
        val discoveries = discovery.discover(listed.map { it.iccid })
        // Only a positively absent subscription can be skipped; unavailable
        // inventory must not silently classify an unreadable card as inactive.
        val included = listed.indices.filter { discoveries[it] !is SubscriptionDiscovery.Missing }
        val snapshots = ArrayList<ConfigurationSnapshot>(included.size)
        included.forEach { index ->
            val item = listed[index]
            if (item.missing || !ConfigurationControlCommand.digitsOnly(item.iccid)) {
                replaceHandles(emptyList())
                return ConfigurationCatalogSnapshot(ConfigurationEvaluator.failure(), emptySet())
            }
            when (val shown = commands.show(item.iccid)) {
                is CommandRead.Mapped -> {
                    replaceHandles(emptyList())
                    return ConfigurationCatalogSnapshot(shown.state, emptySet())
                }
                is CommandRead.Listed, is CommandRead.Created -> {
                    replaceHandles(emptyList())
                    return ConfigurationCatalogSnapshot(ConfigurationEvaluator.failure(), emptySet())
                }
                is CommandRead.Snapshot -> snapshots += shown.snapshot
            }
        }
        val instanceIds = replaceHandles(listed)
        val observations = mutableMapOf<ConfigurationInstanceId, ConfigurationRuntimeObservation>()
        val instances = snapshots.mapIndexed { selectedIndex, snapshot ->
            val index = included[selectedIndex]
            val key = listed[index].iccid
            val owner = ownerFor(key)
            val running = snapshot.lifecycle == ConfigurationLifecycle.RUNNING && discoveries[index] is SubscriptionDiscovery.Matched
            val observation = if (running) readRuntimeFacts(key)?.takeIf { it.isCurrent() } else null
            val runtime = observation?.facts
            val automaticSmsc = if (snapshot.smsc.isEmpty() && running) {
                runtime?.simReportedSmsc() ?: readAutomaticSmsc(key).orEmpty()
            } else ""
            val stillMatched = !running || owner == ownerFor(key) && observation?.isCurrent() != false
            if (observation != null && stillMatched) observations[instanceIds[index]] = observation
            ConfigurationEvaluator.instance(instanceIds[index], snapshot, discoveries[index])
                .copy(draftOwnerId = owner, resolvedSmsc = automaticSmsc.takeIf { stillMatched }.orEmpty(),
                    runtimeFacts = runtime.takeIf { stillMatched })
        }
        val currentDiscoveries = discovery.discover(listed.map { it.iccid })
        if (included.any { index ->
            currentDiscoveries[index] != discoveries[index] ||
                handles.keyFor(SimHandle(instanceIds[index].value)) != listed[index].iccid
        }) {
            clear()
            return ConfigurationCatalogSnapshot(ConfigurationEvaluator.failure("配置已变化，请刷新核对并处理原草稿"), listed.map { it.iccid }.toSet())
        }
        return ConfigurationCatalogSnapshot(
            state = ConfigurationEvaluator.ready(instances, networkOutlets = networkOutlets()),
            existingKeys = listed.map { it.iccid }.toSet(),
            runtimeObservations = observations,
        )
    }

    private fun replaceHandles(listed: List<ListedIccid>): List<ConfigurationInstanceId> =
        synchronized(lock) {
            iccidByHandle.clear()
            listed.map { item ->
                ConfigurationInstanceId(handles.handleFor(item.iccid).value).also { instanceId ->
                    iccidByHandle[instanceId.value] = item.iccid
                }
            }
        }
}

internal fun ConfigurationRuntimeFacts.simReportedSmsc(): String =
    loaded.fields.firstOrNull { it.field == "smsc" && it.source == "sim" }?.value.orEmpty()

internal class ConfigurationCatalogSnapshot(
    val state: ConfigurationUiState,
    val existingKeys: Set<String>,
    val runtimeObservations: Map<ConfigurationInstanceId, ConfigurationRuntimeObservation> = emptyMap(),
) {
    override fun toString(): String = "ConfigurationCatalogSnapshot"
}
