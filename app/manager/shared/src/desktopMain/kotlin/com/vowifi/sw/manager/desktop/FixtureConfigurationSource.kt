package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.data.ConfigurationEvaluator
import com.vowifi.sw.manager.data.ConfigurationSource
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import com.vowifi.sw.manager.model.ConfigurationWriteResult
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationOverrides
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

    override fun refresh() {
        mutableState.value = when (mode) {
            ConfigurationFixtureMode.SIM_1 -> ready(listOf(FIRST_ID))
            ConfigurationFixtureMode.SIM_2 -> ready(listOf(SECOND_ID))
            ConfigurationFixtureMode.EMPTY -> ConfigurationUiState.Empty
            else -> ready()
        }
    }

    override fun submit(
        instanceId: ConfigurationInstanceId,
        overrides: ConfigurationOverrides,
        onComplete: (ConfigurationApplyOutcome) -> Unit,
    ): ConfigurationApplyAcceptance {
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
                    dns = overrides.dns,
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
        val id = when (handle.value) {
            FIRST_ID.value -> FIRST_ID
            SECOND_ID.value -> SECOND_ID
            else -> return
        }
        val current = (mutableState.value as? ConfigurationUiState.Ready)?.instances.orEmpty()
        val updated = (current.filterNot { it.instanceId == id } + instance(id))
            .sortedBy { if (it.instanceId == FIRST_ID) 0 else 1 }
        mutableState.value = ConfigurationEvaluator.ready(
            instances = updated,
            message = "配置已创建",
            isFixture = true,
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
        dns = "dns.example.invalid",
        resolvedEpdgFqdn = "resolved-epdg.example.invalid",
        resolvedImsRealm = "resolved-ims.example.invalid",
        mcc = if (id == FIRST_ID) "460" else "505",
        mnc = if (id == FIRST_ID) "09" else "03",
    )

    private companion object {
        val FIRST_ID = ConfigurationInstanceId("sim-1")
        val SECOND_ID = ConfigurationInstanceId("sim-2")
    }
}
