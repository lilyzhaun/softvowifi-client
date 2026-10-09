package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConfigurationCarrierAlignmentTest {
    @Test fun settingsConfiguredAndUnconfiguredNamesMatchHomeRatherThanLegacyRoamingLabel() {
        val matched = sim(SimPresence.ACTIVE_MATCHED)
        val configured = ConfigurationInstance(ConfigurationInstanceId("sim-name"), "SIM 2 · Legacy", ConfigurationLifecycle.STOPPED,
            ConfigurationTransport.AUTO, "", "", "", "", "", "", "310", "260",
            systemSubscription = SubscriptionDiscovery.Matched("Roaming network", 1, "310", "260"))
        val row = configurationPresentation(ConfigurationUiState.Ready(listOf(configured)), SimControlUiState.Ready(listOf(matched))).rows.single()
        assertEquals("SIM 2 · 🇺🇸 T-Mobile", configurationHeader(row.label).title)
        assertEquals(configurationHeader(row.label).title, homeTitle(matched))
        assertEquals("T-Mobile", (row.instance?.systemSubscription as SubscriptionDiscovery.Matched).carrierLabel)
        val unconfigured = matched.copy(presence = SimPresence.ACTIVE_UNCONFIGURED)
        val empty = configurationPresentation(ConfigurationUiState.Empty, SimControlUiState.Ready(listOf(unconfigured))).rows.single()
        assertEquals(homeTitle(unconfigured), configurationHeader(empty.label).title)
        assertFalse(row.label.contains("Roaming network"))
    }

    @Test fun unreadableHomeCarrierDoesNotFallBackToLegacyCarrier() {
        val sim = sim(SimPresence.ACTIVE_UNCONFIGURED).copy(homeCarrierLabel = null)
        val row = configurationPresentation(ConfigurationUiState.Empty, SimControlUiState.Ready(listOf(sim))).rows.single()
        assertEquals(homeTitle(sim), configurationHeader(row.label).title)
        assertFalse(row.label.contains("Roaming network"))
    }

    private fun sim(presence: SimPresence) = SimControlInstance(SimHandle("sim-name"), "SIM 2 · Roaming network · 310/260 · •••• 1234",
        presence, 1, "Roaming network", "310", "260", EngineLifecycle.STOPPED,
        canStart = false, canStop = false, canApply = false, homeCarrierLabel = "T-Mobile")

    private fun homeTitle(sim: SimControlInstance) = homeDashboardPresentation(RegistrationUiState.Loading,
        SimControlUiState.Ready(listOf(sim)), EnvironmentDiagnosticsUiState.Loading, 0).actions.single().title
}
