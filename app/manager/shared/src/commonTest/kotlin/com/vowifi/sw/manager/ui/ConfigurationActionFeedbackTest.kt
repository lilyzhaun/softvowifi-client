package com.vowifi.sw.manager.ui

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigurationActionFeedbackTest {
    private val testInstance = ConfigurationInstance(
        instanceId = ConfigurationInstanceId("sim1"),
        displayLabel = "SIM 1",
        transport = ConfigurationTransport.WIFI,
        smsc = "+1234567890",
        epdgFqdn = "epdg.example.com",
        imsRealm = "ims.example.com",
        dns = "1.1.1.1",
        lifecycle = ConfigurationLifecycle.RUNNING,
        mcc = "310",
        mnc = "260",
        systemSubscription = com.vowifi.sw.manager.model.SubscriptionDiscovery.NotAttempted,
        resolvedEpdgFqdn = "epdg.example.com",
        resolvedImsRealm = "ims.example.com",
    )

    @Test
    fun configurationSaveActionProvidesConfirmHapticAndExactPointNinetySixPressScaleWhenEnabled() {
        // Given
        val state = ConfigurationUiState.Ready(
            instances = listOf(testInstance),
            applyingInstanceId = null,
        )
        val id = ConfigurationInstanceId("sim1")

        // When
        val feedback = configurationSaveFeedback(state, id)
        val isBusy = configurationSaveIsBusy(state, id)

        // Then
        assertTrue(feedback.accepted)
        assertEquals(HapticFeedbackType.Confirm, feedback.hapticType)
        assertEquals(0.96f, feedback.pressScale)
        assertFalse(isBusy)
    }

    @Test
    fun configurationSaveActionIsDisabledAndProvidesNoHapticsWhenBusyOrCannotApply() {
        // Given
        val id = ConfigurationInstanceId("sim1")
        val busyState = ConfigurationUiState.Ready(
            instances = listOf(testInstance),
            applyingInstanceId = id,
        )
        val otherId = ConfigurationInstanceId("unknown")

        // When
        val busyFeedback = configurationSaveFeedback(busyState, id)
        val isBusy = configurationSaveIsBusy(busyState, id)
        val otherFeedback = configurationSaveFeedback(busyState, otherId)

        // Then
        assertFalse(busyFeedback.accepted)
        assertNull(busyFeedback.hapticType)
        assertNull(busyFeedback.pressScale)
        assertTrue(isBusy)

        assertFalse(otherFeedback.accepted)
        assertNull(otherFeedback.hapticType)
        assertNull(otherFeedback.pressScale)
    }

    @Test
    fun configurationProvisionActionProvidesConfirmHapticWhenProvisionableAndNoneWhenDisabled() {
        // Given
        val handle = SimHandle("sim2")
        val provisionableRow = ConfigurationRowPresentation(
            key = handle.value,
            label = "SIM 2",
            kind = ConfigurationRowKind.ACTIVE_UNCONFIGURED,
            status = "未配置/未支持",
            handle = handle,
            canProvision = true,
        )
        val nonProvisionableRow = provisionableRow.copy(canProvision = false)
        val readyState = ConfigurationUiState.Ready(
            instances = listOf(testInstance),
            applyingInstanceId = null,
        )

        // When
        val enabledFeedback = configurationProvisionFeedback(readyState, provisionableRow)
        val disabledFeedback = configurationProvisionFeedback(readyState, nonProvisionableRow)

        // Then
        assertTrue(enabledFeedback.accepted)
        assertEquals(HapticFeedbackType.Confirm, enabledFeedback.hapticType)
        assertEquals(0.96f, enabledFeedback.pressScale)

        assertFalse(disabledFeedback.accepted)
        assertNull(disabledFeedback.hapticType)
        assertNull(disabledFeedback.pressScale)
    }
}
