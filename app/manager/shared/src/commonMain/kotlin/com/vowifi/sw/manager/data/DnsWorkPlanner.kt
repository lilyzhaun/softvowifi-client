package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimActiveJoinInput
import com.vowifi.sw.manager.model.StandardProvision

internal class DnsConfigRow(
    val instanceId: ConfigurationInstanceId,
    val key: String,
    val dns: String,
    val transport: ConfigurationTransport,
    val smsc: String,
    val epdgFqdn: String,
    val imsRealm: String,
) {
    override fun toString(): String = "DnsConfigRow"
}

internal class DnsRepair(
    val instanceId: ConfigurationInstanceId,
    val key: String,
    val overrides: ConfigurationOverrides,
) {
    override fun toString(): String = "DnsRepair"
}

internal object DnsWorkPlanner {
    fun needsEndpoint(
        active: List<SimActiveJoinInput>,
        existingKeys: Set<String>,
        rows: List<DnsConfigRow>,
    ): Boolean = hasMissingEligible(active, existingKeys) || repairableRows(active, rows).isNotEmpty()

    fun repairs(
        active: List<SimActiveJoinInput>,
        rows: List<DnsConfigRow>,
        endpoint: DnsEndpoint,
    ): List<DnsRepair> = repairableRows(active, rows).map { row ->
        DnsRepair(
            instanceId = row.instanceId,
            key = row.key,
            overrides = overridesFor(row, endpoint),
        )
    }

    fun overridesFor(row: DnsConfigRow, endpoint: DnsEndpoint): ConfigurationOverrides =
        ConfigurationOverrides(
            transport = row.transport,
            smsc = row.smsc,
            epdgFqdn = row.epdgFqdn,
            imsRealm = row.imsRealm,
            dns = endpoint.render(),
        )

    fun rows(
        state: ConfigurationUiState,
        iccidFor: (ConfigurationInstanceId) -> String?,
    ): List<DnsConfigRow> {
        val instances = (state as? ConfigurationUiState.Ready)?.instances.orEmpty()
        return instances.mapNotNull { instance -> rowFor(instance, iccidFor) }
    }
}

private fun hasMissingEligible(
    active: List<SimActiveJoinInput>,
    existingKeys: Set<String>,
): Boolean {
    val seen = HashSet<String>()
    for (candidate in active) {
        val key = candidate.key
        if (key.isBlank() || !seen.add(key)) continue
        if (key in existingKeys) continue
        if (StandardProvision.automaticProfileFor(candidate.mcc, candidate.mnc) == null) continue
        return true
    }
    return false
}

private fun repairableRows(
    active: List<SimActiveJoinInput>,
    rows: List<DnsConfigRow>,
): List<DnsConfigRow> {
    val activeKeys = HashSet<String>()
    for (candidate in active) {
        if (candidate.key.isNotBlank()) activeKeys += candidate.key
    }
    return rows.filter { row -> row.key in activeKeys && row.dns.isBlank() }
}

private fun rowFor(
    instance: ConfigurationInstance,
    iccidFor: (ConfigurationInstanceId) -> String?,
): DnsConfigRow? {
    val key = iccidFor(instance.instanceId)?.takeIf(String::isNotBlank) ?: return null
    return DnsConfigRow(
        instanceId = instance.instanceId,
        key = key,
        dns = instance.dns,
        transport = instance.transport,
        smsc = instance.smsc,
        epdgFqdn = instance.epdgFqdn,
        imsRealm = instance.imsRealm,
    )
}
