package com.vowifi.sw.manager.ui

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagerActionFeedbackTest {
    @Test
    fun acceptedActionsMapToTheTypedComposeHapticSemantics() {
        // Given
        val actions = listOf(
            ManagerAction.Refresh to HapticFeedbackType.Confirm,
            ManagerAction.Navigate to HapticFeedbackType.ContextClick,
            ManagerAction.StartEngine to HapticFeedbackType.Confirm,
            ManagerAction.StopEngine to HapticFeedbackType.Confirm,
            ManagerAction.SaveConfiguration to HapticFeedbackType.Confirm,
            ManagerAction.ProvisionConfiguration to HapticFeedbackType.Confirm,
            ManagerAction.ExportLogs to HapticFeedbackType.Confirm,
            ManagerAction.ToggleOn to HapticFeedbackType.ToggleOn,
            ManagerAction.ToggleOff to HapticFeedbackType.ToggleOff,
            ManagerAction.SegmentSelection to HapticFeedbackType.SegmentTick,
            ManagerAction.ContextAction to HapticFeedbackType.ContextClick,
            ManagerAction.Rejected to HapticFeedbackType.Reject,
        )

        // When
        val feedback = actions.map { (action, _) -> managerActionFeedback(action) }

        // Then
        assertEquals(actions.map { it.second }, feedback.map { it.hapticType })
        assertTrue(feedback.all { it.pressScale == ManagerActionFeedback.PressScale })
    }

    @Test
    fun disabledAndDecorativeActionsDoNotProvideFeedbackOrInvokeCallbacks() {
        // Given
        val disabled = managerActionFeedback(ManagerAction.SaveConfiguration, enabled = false)
        val decorative = managerActionFeedback(ManagerAction.Decorative)
        val haptics = RecordingHaptics(mutableListOf())
        var callbackCount = 0

        // When
        managerActionHandler(disabled, haptics) { callbackCount += 1 }()
        managerActionHandler(decorative, haptics) { callbackCount += 1 }()

        // Then
        assertNull(disabled.hapticType)
        assertNull(disabled.pressScale)
        assertNull(decorative.hapticType)
        assertNull(decorative.pressScale)
        assertFalse(disabled.accepted)
        assertFalse(decorative.accepted)
        assertEquals(0, callbackCount)
        assertTrue(haptics.types.isEmpty())
    }

    @Test
    fun toggleFeedbackUsesTheNewBooleanValueAndPressScaleIsExactlyPointNinetySix() {
        // Given
        val enabledFeedback = toggleActionFeedback(newValue = true)
        val disabledFeedback = toggleActionFeedback(newValue = false)

        // When
        val scales = listOf(enabledFeedback.pressScale, disabledFeedback.pressScale)

        // Then
        assertEquals(HapticFeedbackType.ToggleOn, enabledFeedback.hapticType)
        assertEquals(HapticFeedbackType.ToggleOff, disabledFeedback.hapticType)
        assertEquals(listOf(0.96f, 0.96f), scales)
    }

    @Test
    fun handlersPerformHapticBeforeAcceptedCallbacksAndIgnoreDisabledActions() {
        // Given
        val events = mutableListOf<String>()
        val haptics = RecordingHaptics(events)
        val accepted = managerActionHandler(
            managerActionFeedback(ManagerAction.SaveConfiguration),
            haptics,
            hapticsEnabled = true,
        ) {
            events += "callback"
        }
        val hapticsDisabled = managerActionHandler(
            managerActionFeedback(ManagerAction.SaveConfiguration),
            haptics,
            hapticsEnabled = false,
        ) {
            events += "callback-nohaptic"
        }
        val disabled = managerActionHandler(
            managerActionFeedback(ManagerAction.SaveConfiguration, enabled = false),
            haptics,
            hapticsEnabled = true,
        ) {
            events += "disabled"
        }
        val toggle = managerToggleHandler(enabled = true, hapticFeedback = haptics, hapticsEnabled = true) { value ->
            events += "toggle=$value"
        }
        val toggleHapticsDisabled = managerToggleHandler(
            enabled = true,
            hapticFeedback = haptics,
            hapticsEnabled = false,
        ) { value ->
            events += "toggle-nohaptic=$value"
        }

        // When
        accepted()
        hapticsDisabled()
        disabled()
        toggle(true)
        toggleHapticsDisabled(false)

        // Then
        assertEquals(
            listOf("haptic", "callback", "callback-nohaptic", "haptic", "toggle=true", "toggle-nohaptic=false"),
            events,
        )
        assertEquals(listOf(HapticFeedbackType.Confirm, HapticFeedbackType.ToggleOn), haptics.types)
    }

    private class RecordingHaptics(private val events: MutableList<String>) : HapticFeedback {
        val types = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            types += hapticFeedbackType
            events += "haptic"
        }
    }
}
