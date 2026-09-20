package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.ConfigurationWriteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConfigurationFixtureSubmissionTest {
    @Test fun fixtureUsesSameTypedContractAndReadbackWithoutChangingOtherSim() {
        val source = FixtureConfigurationSource(ConfigurationFixtureMode.READY)
        val initial = assertIs<ConfigurationUiState.Ready>(source.state.value)
        val row = initial.instances.first()
        val overrides = ConfigurationOverrides(row.transport, row.smsc, row.epdgFqdn, row.imsRealm, row.dns)
        var completions = 0
        assertEquals(ConfigurationApplyAcceptance.Accepted, source.submit(row.instanceId, overrides) {
            assertEquals(ConfigurationWriteResult.Unchanged, assertIs<ConfigurationApplyOutcome.Confirmed>(it).result)
            completions++
        })
        source.submit(row.instanceId, overrides.copy(dns = "synthetic.invalid")) {
            val confirmed = assertIs<ConfigurationApplyOutcome.Confirmed>(it)
            assertEquals(ConfigurationWriteResult.Applied, confirmed.result)
            assertEquals("synthetic.invalid", confirmed.instance.dns)
            completions++
        }
        val after = assertIs<ConfigurationUiState.Ready>(source.state.value)
        assertTrue(after.isFixture)
        assertEquals(initial.instances.last(), after.instances.last())
        assertEquals(2, completions)
    }

    @Test fun busyFixtureRejectsWithoutCallbackOrMutation() {
        val source = FixtureConfigurationSource(ConfigurationFixtureMode.BUSY)
        val initial = assertIs<ConfigurationUiState.Ready>(source.state.value)
        val row = initial.instances.first()
        var callbacks = 0
        assertEquals(ConfigurationApplyAcceptance.Busy, source.submit(row.instanceId,
            ConfigurationOverrides(row.transport, "", "", "", "synthetic.invalid")) { callbacks++ })
        assertEquals(initial, source.state.value)
        assertEquals(0, callbacks)
    }
}
