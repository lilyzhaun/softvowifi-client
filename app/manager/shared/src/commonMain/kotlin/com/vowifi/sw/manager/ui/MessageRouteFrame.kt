package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*

private class MessageRouteFrame<T>(var value: T)

/** A leaving SMS scene keeps its own last frame, never another destination's source state. */
@Composable
internal fun <T> rememberMessageRouteFrame(value: T, key: Any = Unit,
    active: Boolean = LocalAppRouteActive.current): T {
    val lastActiveFrame = remember(key) { MessageRouteFrame(value) }
    // Remember the committed display value without scheduling another navigation recompose.
    SideEffect { if (active) lastActiveFrame.value = value }
    return if (active) value else lastActiveFrame.value
}
