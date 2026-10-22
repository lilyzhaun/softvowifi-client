package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationSnapshot
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.ConfigurationNetworkOutlets
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.SubscriptionDiscovery

internal class AndroidConfigurationCatalog(
    private val commands: AndroidConfigurationCommands,
    private val discovery: AndroidSubscriptionDiscovery,
    private val handles: OpaqueIccidHandleStore,
    private val ownerFor: (String) -> SimOwnerId? = { null },
    private val networkOutlets: () -> ConfigurationNetworkOutlets = { ConfigurationNetworkOutlets() },
    private val readAutomaticSmsc: suspend (String) -> String? = { null },
) {
    private val lock = Any()
    private val iccidByHandle = linkedMapOf<String, String>()

    fun iccidFor(instanceId: ConfigurationInstanceId): String? =
        synchronized(lock) { iccidByHandle[instanceId.value] }

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
            replaceHandles(emptyList())
            return ConfigurationCatalogSnapshot(ConfigurationUiState.Empty, emptySet())
        }
        val snapshots = ArrayList<ConfigurationSnapshot>(listed.size)
        listed.forEach { item ->
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
        val discoveries = discovery.discover(listed.map { it.iccid })
        val instanceIds = replaceHandles(listed)
        val instances = snapshots.mapIndexed { index, snapshot ->
            val automaticSmsc = if (snapshot.smsc.isEmpty() && snapshot.lifecycle == ConfigurationLifecycle.RUNNING &&
                discoveries[index] is SubscriptionDiscovery.Matched) readAutomaticSmsc(listed[index].iccid).orEmpty() else ""
            ConfigurationEvaluator.instance(instanceIds[index], snapshot, discoveries[index])
                .copy(draftOwnerId = ownerFor(listed[index].iccid), resolvedSmsc = automaticSmsc)
        }
        return ConfigurationCatalogSnapshot(
            state = ConfigurationEvaluator.ready(instances, networkOutlets = networkOutlets()),
            existingKeys = listed.map { it.iccid }.toSet(),
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

internal class ConfigurationCatalogSnapshot(
    val state: ConfigurationUiState,
    val existingKeys: Set<String>,
) {
    override fun toString(): String = "ConfigurationCatalogSnapshot"
}
