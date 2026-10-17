package com.vowifi.sw.manager.ui

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

internal sealed interface ManagerAction {
    data object Refresh : ManagerAction
    data object Navigate : ManagerAction
    data object StartEngine : ManagerAction
    data object StopEngine : ManagerAction
    data object SaveConfiguration : ManagerAction
    data object ProvisionConfiguration : ManagerAction
    data object ExportLogs : ManagerAction
    data object ClearCallHistory : ManagerAction
    data object SendMessage : ManagerAction
    data object ToggleOn : ManagerAction
    data object ToggleOff : ManagerAction
    data object SegmentSelection : ManagerAction
    data object ContextAction : ManagerAction
    data object DialKey : ManagerAction
    data object LongPress : ManagerAction
    data object Rejected : ManagerAction
    data object Decorative : ManagerAction
}

internal data class ManagerActionFeedback(
    val hapticType: HapticFeedbackType?,
    val pressScale: Float?,
) {
    val accepted: Boolean
        get() = pressScale != null

    companion object {
        const val PressScale = 0.96f
        val None = ManagerActionFeedback(hapticType = null, pressScale = null)
    }
}

internal fun managerActionFeedback(
    action: ManagerAction,
    enabled: Boolean = true,
): ManagerActionFeedback {
    if (!enabled) return ManagerActionFeedback.None
    val hapticType = when (action) {
        ManagerAction.Refresh,
        ManagerAction.StartEngine,
        ManagerAction.StopEngine,
        ManagerAction.SaveConfiguration,
        ManagerAction.ProvisionConfiguration,
        ManagerAction.ExportLogs,
        ManagerAction.ClearCallHistory,
        ManagerAction.SendMessage,
        -> HapticFeedbackType.Confirm
        ManagerAction.Navigate,
        ManagerAction.ContextAction,
        -> HapticFeedbackType.ContextClick
        ManagerAction.ToggleOn -> HapticFeedbackType.ToggleOn
        ManagerAction.DialKey -> HapticFeedbackType.VirtualKey
        ManagerAction.LongPress -> HapticFeedbackType.LongPress
        ManagerAction.ToggleOff -> HapticFeedbackType.ToggleOff
        ManagerAction.SegmentSelection -> HapticFeedbackType.SegmentTick
        ManagerAction.Rejected -> HapticFeedbackType.Reject
        ManagerAction.Decorative -> return ManagerActionFeedback.None
    }
    return ManagerActionFeedback(hapticType, ManagerActionFeedback.PressScale)
}

internal fun toggleActionFeedback(newValue: Boolean, enabled: Boolean = true): ManagerActionFeedback =
    managerActionFeedback(if (newValue) ManagerAction.ToggleOn else ManagerAction.ToggleOff, enabled)

internal fun ManagerActionFeedback.performHapticThenInvoke(
    hapticFeedback: HapticFeedback,
    onAccepted: () -> Unit,
    hapticsEnabled: Boolean = true,
) {
    if (!accepted) return
    val type = hapticType
    if (hapticsEnabled && type != null) {
        hapticFeedback.performHapticFeedback(type)
    }
    onAccepted()
}

internal fun managerActionHandler(
    feedback: ManagerActionFeedback,
    hapticFeedback: HapticFeedback,
    hapticsEnabled: Boolean = true,
    onAccepted: () -> Unit,
): () -> Unit = {
    feedback.performHapticThenInvoke(hapticFeedback, onAccepted, hapticsEnabled)
}

@Composable
internal fun rememberManagerActionHandler(
    feedback: ManagerActionFeedback,
    onAccepted: () -> Unit,
): () -> Unit {
    val hapticFeedback = LocalHapticFeedback.current
    val hapticsEnabled = LocalSoftVoWiFiChromeEnvironment.current.spec.hapticsEnabled
    val currentCallback = rememberUpdatedState(onAccepted)
    return remember(feedback, hapticFeedback, hapticsEnabled) {
        managerActionHandler(feedback, hapticFeedback, hapticsEnabled) { currentCallback.value() }
    }
}

internal fun performToggleHapticThenInvoke(
    newValue: Boolean,
    enabled: Boolean,
    hapticFeedback: HapticFeedback,
    hapticsEnabled: Boolean = true,
    onAccepted: (Boolean) -> Unit,
) {
    toggleActionFeedback(newValue, enabled).performHapticThenInvoke(hapticFeedback, { onAccepted(newValue) }, hapticsEnabled)
}

internal fun managerToggleHandler(
    enabled: Boolean,
    hapticFeedback: HapticFeedback,
    hapticsEnabled: Boolean = true,
    onAccepted: (Boolean) -> Unit,
): (Boolean) -> Unit = { newValue ->
    performToggleHapticThenInvoke(newValue, enabled, hapticFeedback, hapticsEnabled, onAccepted)
}

@Composable
internal fun rememberManagerToggleHandler(
    enabled: Boolean,
    onAccepted: (Boolean) -> Unit,
): (Boolean) -> Unit {
    val hapticFeedback = LocalHapticFeedback.current
    val hapticsEnabled = LocalSoftVoWiFiChromeEnvironment.current.spec.hapticsEnabled
    val currentCallback = rememberUpdatedState(onAccepted)
    return remember(enabled, hapticFeedback, hapticsEnabled) {
        managerToggleHandler(enabled, hapticFeedback, hapticsEnabled) { currentCallback.value(it) }
    }
}

@Composable
internal fun Modifier.managerPressScale(feedback: ManagerActionFeedback, interactionSource: InteractionSource): Modifier {
    return managerPressDrawing(rememberManagerPressMotion(interactionSource, feedback.accepted, ManagerPressStyle.CardRow))
}
