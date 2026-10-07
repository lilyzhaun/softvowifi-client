package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/** Keeps the public miuix host mounted until its exit, then hands off at most one action. */
internal class CommunicationSheetState {
    var visible by mutableStateOf(true)
        private set
    private var afterDismiss: (() -> Unit)? = null

    fun close(afterDismiss: () -> Unit) {
        if (!visible) return
        this.afterDismiss = afterDismiss
        visible = false
    }

    fun cancelPending(onDismiss: () -> Unit) {
        afterDismiss = onDismiss
        visible = false
    }

    fun finishDismiss() {
        val action = afterDismiss
        afterDismiss = null
        action?.invoke()
    }
}

@Composable
internal fun rememberCommunicationSheetState(onDismiss: () -> Unit): CommunicationSheetState {
    val state = remember { CommunicationSheetState() }
    val active = LocalAppRouteActive.current
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(active) { if (!active) state.cancelPending { dismiss() } }
    return state
}
