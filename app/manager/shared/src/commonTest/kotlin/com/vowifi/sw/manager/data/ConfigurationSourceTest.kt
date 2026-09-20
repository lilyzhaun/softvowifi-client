package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SubscriptionDiscovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ConfigurationSourceTest {
    @Test
    fun contractExposesStateRefreshApplyAndDefaultClose() {
        val recorded = mutableListOf<Pair<String, ConfigurationOverrides>>()
        val source = object : ConfigurationSource {
            private val mutableState = MutableStateFlow<ConfigurationUiState>(ConfigurationUiState.Loading)
            override val state: StateFlow<ConfigurationUiState> = mutableState
            override fun refresh() {
                mutableState.value = ConfigurationEvaluator.fromShow(
                    redactedShowJson(),
                    ConfigurationInstanceId(FAKE_INSTANCE_ID),
                )
            }
            override fun submit(instanceId: ConfigurationInstanceId, overrides: ConfigurationOverrides,
                onComplete: (ConfigurationApplyOutcome) -> Unit): ConfigurationApplyAcceptance {
                recorded += instanceId.value to overrides
                onComplete(ConfigurationApplyOutcome.Unconfirmed)
                return ConfigurationApplyAcceptance.Accepted
            }
        }
        val overrides = ConfigurationOverrides(ConfigurationTransport.WIFI, "", "", "", "")

        assertIs<ConfigurationUiState.Loading>(source.state.value)
        source.refresh()
        assertEquals(ConfigurationApplyAcceptance.Accepted, source.submit(ConfigurationInstanceId(FAKE_INSTANCE_ID), overrides) {
            assertEquals(ConfigurationApplyOutcome.Unconfirmed, it)
        })
        source.provision(SimHandle("sim-1"))
        source.close()

        val ready = assertIs<ConfigurationUiState.Ready>(source.state.value)
        assertEquals(FAKE_INSTANCE_ID, ready.instances.single().instanceId.value)
        assertEquals(SubscriptionDiscovery.NotAttempted, ready.instances.single().systemSubscription)
        assertEquals(listOf(FAKE_INSTANCE_ID to overrides), recorded)
        assertPrivate(ready.instances.single().displayLabel)
    }

    @Test
    fun missingOrUnavailableDiscoveryKeepsReadyAndApplyContract() {
        val recorded = mutableListOf<Pair<String, ConfigurationOverrides>>()
        val source = object : ConfigurationSource {
            private val mutableState = MutableStateFlow<ConfigurationUiState>(ConfigurationUiState.Loading)
            override val state: StateFlow<ConfigurationUiState> = mutableState
            override fun refresh() {
                val loaded = assertIs<ConfigurationUiState.Ready>(
                    ConfigurationEvaluator.fromShow(
                        redactedShowJson(),
                        ConfigurationInstanceId(FAKE_INSTANCE_ID),
                    ),
                )
                mutableState.value = ConfigurationEvaluator.ready(
                    listOf(
                        loaded.instances.single().copy(
                            systemSubscription = SubscriptionDiscovery.Unavailable,
                        ),
                    ),
                )
            }
            override fun submit(instanceId: ConfigurationInstanceId, overrides: ConfigurationOverrides,
                onComplete: (ConfigurationApplyOutcome) -> Unit): ConfigurationApplyAcceptance {
                recorded += instanceId.value to overrides
                onComplete(ConfigurationApplyOutcome.Unconfirmed)
                return ConfigurationApplyAcceptance.Accepted
            }
        }
        val overrides = ConfigurationOverrides(ConfigurationTransport.WIFI, "", "", "", "")

        source.refresh()
        val ready = assertIs<ConfigurationUiState.Ready>(source.state.value)
        assertEquals(SubscriptionDiscovery.Unavailable, ready.instances.single().systemSubscription)
        source.submit(ConfigurationInstanceId(FAKE_INSTANCE_ID), overrides) {
            assertEquals(ConfigurationApplyOutcome.Unconfirmed, it)
        }
        assertEquals(listOf(FAKE_INSTANCE_ID to overrides), recorded)
        assertIs<ConfigurationUiState.Ready>(source.state.value)
    }

    @Test
    fun provisionDefaultLeavesStateUnchanged() {
        val source = object : ConfigurationSource {
            override val state: StateFlow<ConfigurationUiState> =
                MutableStateFlow(ConfigurationUiState.Loading)
            override fun refresh() = Unit
            override fun submit(instanceId: ConfigurationInstanceId, overrides: ConfigurationOverrides,
                onComplete: (ConfigurationApplyOutcome) -> Unit) = ConfigurationApplyAcceptance.Unavailable
        }

        source.provision(SimHandle("sim-1"))

        assertIs<ConfigurationUiState.Loading>(source.state.value)
    }
}
