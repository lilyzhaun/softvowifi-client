package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import com.vowifi.sw.manager.ui.navigation.AppNavigationState
import com.vowifi.sw.manager.ui.navigation.AppRoute

internal data class FocusLifecycleState(
    val focused: Boolean,
    val imeShownSinceFocus: Boolean,
)

internal enum class FocusClearReason {
    ROUTE_INACTIVE,
    IME_HIDDEN_AFTER_SHOW,
}

internal fun focusClearReason(
    state: FocusLifecycleState,
    routeActive: Boolean,
    imeVisible: Boolean,
): FocusClearReason? {
    if (!state.focused) return null
    if (!routeActive) return FocusClearReason.ROUTE_INACTIVE
    if (state.imeShownSinceFocus && !imeVisible) return FocusClearReason.IME_HIDDEN_AFTER_SHOW
    return null
}

internal fun isRouteActive(state: AppNavigationState, route: AppRoute): Boolean = when (route) {
    is AppRoute.Root -> state.topRoute is AppRoute.Root && state.currentRoot == route
    is AppRoute.Subpage -> state.topRoute == route
}

internal val LocalAppRouteActive = compositionLocalOf { true }

// IME-hide mechanism adapted from Mihon presentation-core/.../Modifier.kt commit 056d9baeeba6b7f53c0638b3d30da72e71c159fa, Apache-2.0; route-inactive branch is local.
@Composable
internal fun Modifier.clearFocusOnInactiveRouteAndImeHide(): Modifier {
    val focusManager = LocalFocusManager.current
    val routeActive = LocalAppRouteActive.current
    val imeVisible = currentImeVisible()
    var focused by remember { mutableStateOf(false) }
    var imeShownSinceFocus by remember { mutableStateOf(false) }

    LaunchedEffect(focused, routeActive, imeVisible) {
        if (!focused) {
            imeShownSinceFocus = false
            return@LaunchedEffect
        }
        if (imeVisible) {
            imeShownSinceFocus = true
        }
        val reason = focusClearReason(
            state = FocusLifecycleState(focused = focused, imeShownSinceFocus = imeShownSinceFocus),
            routeActive = routeActive,
            imeVisible = imeVisible,
        )
        if (reason != null) {
            focusManager.clearFocus()
        }
    }

    return this.onFocusChanged { focusState ->
        val hasFocus = focusState.hasFocus
        focused = hasFocus
        if (!hasFocus) {
            imeShownSinceFocus = false
        }
    }
}
