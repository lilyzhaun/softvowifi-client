package com.vowifi.sw.manager.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/** Only a gesture starting at the latest-message edge owns IME dismissal. */
@Composable
internal fun Modifier.managerImeScroll(state: LazyListState, enabled: Boolean = true): Modifier {
    val active = enabled && LocalAppRouteActive.current
    val activeNow by rememberUpdatedState(active)
    val platformControlsIme = managerImeScrollIsControllable()
    val platformControlsImeNow by rememberUpdatedState(platformControlsIme)
    val imeVisibleNow by rememberUpdatedState(currentImeVisible())
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismiss by rememberUpdatedState({ keyboard?.hide(); focus.clearFocus() })
    var eligible by remember(state) { mutableStateOf(false) }
    var nativeEligible by remember(state) { mutableStateOf(false) }
    val gesture = remember(state) { ImeScrollGesture() }
    val connection = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f && !gesture.started) {
                    gesture.started = true
                    if (activeNow && eligible && available.y > 0f && !platformControlsImeNow) dismiss()
                }
                return Offset.Zero
            }
        }
    }
    return managerPlatformImeScroll(active && nativeEligible && platformControlsIme)
        .nestedScroll(connection)
        .pointerInput(state) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                // Observe without claiming the pointer; Foundation still owns slop, axis and drag.
                eligible = activeNow && !state.canScrollForward
                // Do not let an upward drag reopen an IME that was hidden before DOWN.
                // Keep this snapshot through reversal while the native gesture is in progress.
                nativeEligible = eligible && imeVisibleNow
                gesture.started = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                } while (event.changes.any { it.pressed })
                // Keep native control attached through its release animation. The next DOWN
                // re-evaluates the edge; detaching on UP would interrupt platform settlement.
            }
        }
}

private class ImeScrollGesture { var started = false }

@Composable
internal expect fun managerImeScrollIsControllable(): Boolean

@Composable
internal expect fun Modifier.managerPlatformImeScroll(enabled: Boolean): Modifier
