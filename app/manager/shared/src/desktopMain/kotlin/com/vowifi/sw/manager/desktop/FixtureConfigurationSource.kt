package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.data.ConfigurationEvaluator
import com.vowifi.sw.manager.data.ConfigurationSource
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import com.vowifi.sw.manager.model.ConfigurationWriteResult
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationProvisionCompletion
import com.vowifi.sw.manager.model.ConfigurationProvisionOutcome
import com.vowifi.sw.manager.model.ConfigurationProvisionState
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConfigurationFixtureMode(val wireValue: String) {
    READY("ready"),
    SIM_1("sim-1"),
    SIM_2("sim-2"),
    LOADING("loading"),
    EMPTY("empty"),
    FAILURE("failure"),
    PERMISSION("permission"),
    UNAVAILABLE("unavailable"),
    BUSY("busy");

    companion object {
        fun fromWire(value: String): ConfigurationFixtureMode =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("unknown configuration fixture: $value")
    }
}

class FixtureConfigurationSource(
    private val mode: ConfigurationFixtureMode,
) : ConfigurationSource {
    constructor(mode: String) : this(ConfigurationFixtureMode.fromWire(mode))

    private val mutableState = MutableStateFlow(fixture(mode))
    override val state: StateFlow<ConfigurationUiState> = mutableState.asStateFlow()
    private val mutableProvisioning = MutableStateFlow(ConfigurationProvisionState())
    override val provisioning: StateFlow<ConfigurationProvisionState> = mutableProvisioning.asStateFlow()
    private var provisionSequence = 0L
    private var closed = false

    override fun refresh() { readSnapshot() }
    override suspend fun refreshAndAwait(): RefreshResult = readSnapshot()
    override suspend fun refreshIdentitiesAndAwait(): RefreshResult = readSnapshot()
    override fun close() {
        if (closed) return
        closed = true
        val current = mutableProvisioning.value
        mutableProvisioning.value = current.copy(pending = emptySet(), completions = current.completions +
            current.pending.associateWith { ConfigurationProvisionCompletion(++provisionSequence, ConfigurationProvisionOutcome.Cancelled) })
    }

    private fun readSnapshot(): RefreshResult {
        if (closed) return RefreshResult.Unsupported
        if (mutableProvisioning.value.pending.isNotEmpty()) return RefreshResult.Busy
        val snapshot = when (mode) {
            ConfigurationFixtureMode.SIM_1 -> ready(listOf(FIRST_ID))
            ConfigurationFixtureMode.SIM_2 -> ready(listOf(SECOND_ID))
            ConfigurationFixtureMode.EMPTY -> ConfigurationUiState.Empty
            else -> ready()
        }
        mutableState.value = snapshot
        return when (snapshot) {
            is ConfigurationUiState.Ready, ConfigurationUiState.Empty -> RefreshResult.Completed
            is ConfigurationUiState.Failure -> RefreshResult.Failed(snapshot.message)
            is ConfigurationUiState.PermissionDenied -> RefreshResult.Failed(snapshot.message)
            is ConfigurationUiState.Unavailable, ConfigurationUiState.Loading -> RefreshResult.Unsupported
        }
    }

    override fun submit(
        instanceId: ConfigurationInstanceId,
        overrides: ConfigurationOverrides,
        onComplete: (ConfigurationApplyOutcome) -> Unit,
    ): ConfigurationApplyAcceptance {
        if (closed) return ConfigurationApplyAcceptance.Unavailable
        if (mutableProvisioning.value.pending.isNotEmpty()) return ConfigurationApplyAcceptance.Busy
        val current = mutableState.value as? ConfigurationUiState.Ready ?: return ConfigurationApplyAcceptance.Unavailable
        if (current.applyingInstanceId != null) return ConfigurationApplyAcceptance.Busy
        val previous = current.instances.firstOrNull { it.instanceId == instanceId } ?: return ConfigurationApplyAcceptance.Unavailable
        try {
            com.vowifi.sw.manager.data.ConfigurationCodec.encode(overrides)
        } catch (_: com.vowifi.sw.manager.model.ConfigurationFormatException) {
            onComplete(ConfigurationApplyOutcome.Failed)
            return ConfigurationApplyAcceptance.Accepted
        }
        val updated = current.instances.map { instance ->
            if (instance.instanceId == instanceId) {
                instance.copy(
                    transport = overrides.transport,
                    smsc = overrides.smsc,
                    epdgFqdn = overrides.epdgFqdn,
                    imsRealm = overrides.imsRealm,
                    dns = com.vowifi.sw.manager.data.dnsServerValue(overrides.dns),
                    dnsOverrides = com.vowifi.sw.manager.data.dnsOverridesFor(overrides),
                )
            } else {
                instance
            }
        }
        mutableState.value = ConfigurationEvaluator.ready(
            instances = updated,
            message = "配置已保存",
            isFixture = true,
        )
        val readback = updated.first { it.instanceId == instanceId }
        onComplete(ConfigurationApplyOutcome.Confirmed(readback,
            if (readback == previous) ConfigurationWriteResult.Unchanged else ConfigurationWriteResult.Applied))
        return ConfigurationApplyAcceptance.Accepted
    }

    override fun provision(handle: SimHandle) {
        if (closed) return
        if (mutableProvisioning.value.pending.isNotEmpty()) return
        val id = when (handle.value) {
            FIRST_ID.value -> FIRST_ID
            SECOND_ID.value -> SECOND_ID
            else -> return
        }
        val current = (mutableState.value as? ConfigurationUiState.Ready)?.instances.orEmpty()
        val previous = current.firstOrNull { it.instanceId == id }
        mutableProvisioning.value = mutableProvisioning.value.copy(pending = setOf(handle))
        if (closed) return
        val updated = (current.filterNot { it.instanceId == id } + instance(id))
            .sortedBy { if (it.instanceId == FIRST_ID) 0 else 1 }
        mutableState.value = ConfigurationEvaluator.ready(
            instances = updated,
            message = "配置已创建",
            isFixture = true,
        )
        val readback = (mutableState.value as? ConfigurationUiState.Ready)?.instances?.firstOrNull { it.instanceId == id }
        val outcome = when {
            readback == null -> ConfigurationProvisionOutcome.Unconfirmed
            previous == null -> ConfigurationProvisionOutcome.Created
            readback == previous -> ConfigurationProvisionOutcome.Unchanged
            else -> ConfigurationProvisionOutcome.Updated
        }
        if (!closed) mutableProvisioning.value = mutableProvisioning.value.copy(
            pending = emptySet(),
            completions = mutableProvisioning.value.completions + (handle to ConfigurationProvisionCompletion(++provisionSequence, outcome)),
        )
    }

    private fun fixture(mode: ConfigurationFixtureMode): ConfigurationUiState = when (mode) {
        ConfigurationFixtureMode.READY -> ready()
        ConfigurationFixtureMode.SIM_1 -> ready(listOf(FIRST_ID))
        ConfigurationFixtureMode.SIM_2 -> ready(listOf(SECOND_ID))
        ConfigurationFixtureMode.LOADING -> ConfigurationUiState.Loading
        ConfigurationFixtureMode.EMPTY -> ConfigurationUiState.Empty
        ConfigurationFixtureMode.FAILURE -> ConfigurationEvaluator.failure()
        ConfigurationFixtureMode.PERMISSION -> ConfigurationEvaluator.permissionDenied()
        ConfigurationFixtureMode.UNAVAILABLE -> ConfigurationEvaluator.unavailable()
        ConfigurationFixtureMode.BUSY -> ready(applyingInstanceId = FIRST_ID)
    }

    private fun ready(
        instanceIds: List<ConfigurationInstanceId> = listOf(FIRST_ID, SECOND_ID),
        applyingInstanceId: ConfigurationInstanceId? = null,
    ): ConfigurationUiState = ConfigurationEvaluator.ready(
        instances = instanceIds.map(::instance),
        applyingInstanceId = applyingInstanceId,
        isFixture = true,
    )

    private fun instance(id: ConfigurationInstanceId): ConfigurationInstance = ConfigurationInstance(
        instanceId = id,
        displayLabel = if (id == FIRST_ID) "实例 •••• 1001" else "实例 •••• 2002",
        lifecycle = ConfigurationLifecycle.STOPPED,
        transport = if (id == FIRST_ID) ConfigurationTransport.AUTO else ConfigurationTransport.WIFI,
        smsc = "",
        epdgFqdn = "epdg.example.invalid",
        imsRealm = "ims.example.invalid",
        dns = FIXTURE_DNS,
        resolvedEpdgFqdn = "resolved-epdg.example.invalid",
        resolvedImsRealm = "resolved-ims.example.invalid",
        mcc = if (id == FIRST_ID) "460" else "505",
        mnc = if (id == FIRST_ID) "09" else "03",
        dnsOverrides = mapOf((if (id == FIRST_ID) "auto" else "wifi") to FIXTURE_DNS),
    )

    private companion object {
        val FIRST_ID = ConfigurationInstanceId("sim-1")
        val SECOND_ID = ConfigurationInstanceId("sim-2")
        const val FIXTURE_DNS = "192.0.2.53:53"
    }
}
