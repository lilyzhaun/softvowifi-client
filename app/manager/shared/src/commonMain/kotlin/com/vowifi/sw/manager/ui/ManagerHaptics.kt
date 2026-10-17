package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** App preference gates UI feedback; the original platform delegate keeps system policy. */
@Composable
internal fun ManagerHaptics(enabled: Boolean, content: @Composable () -> Unit) {
    val platformHaptics = LocalHapticFeedback.current
    val currentEnabled = rememberUpdatedState(enabled)
    val gatedHaptics = remember(platformHaptics) {
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                if (currentEnabled.value) platformHaptics.performHapticFeedback(hapticFeedbackType)
            }
        }
    }
    CompositionLocalProvider(LocalHapticFeedback provides gatedHaptics, content = content)
}

/**
 * Suppress only a library component's duplicate feedback. Create the application's
 * action handler outside this scope so it retains the preference-gated delegate.
 */
@Composable
internal fun WithoutComponentHaptics(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHapticFeedback provides SilentComponentHaptics, content = content)
}

private object SilentComponentHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}
