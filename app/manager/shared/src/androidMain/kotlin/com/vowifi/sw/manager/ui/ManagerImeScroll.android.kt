package com.vowifi.sw.manager.ui

import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView

@Composable
internal actual fun managerImeScrollIsControllable(): Boolean {
    val view = LocalView.current
    var controllable by remember(view) { mutableStateOf(false) }
    DisposableEffect(view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = view.windowInsetsController
            val listener = WindowInsetsController.OnControllableInsetsChangedListener { _, types ->
                controllable = types and WindowInsets.Type.ime() != 0
            }
            controller?.addOnControllableInsetsChangedListener(listener)
            onDispose { controller?.removeOnControllableInsetsChangedListener(listener) }
        } else onDispose { }
    }
    return controllable
}

/** Uses the public foundation-layout API; no copied AndroidX controller/animation code. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal actual fun Modifier.managerPlatformImeScroll(enabled: Boolean): Modifier {
    // Bottom-side imeNestedScroll pre-consumes positive Y (downward) to hide, before
    // the non-reversed timeline can consume it. Reverse motion restores the native IME.
    // MessageComposer's existing clearFocusOnInactiveRouteAndImeHide owns final focus cleanup.
    return if (enabled) imeNestedScroll() else this
}
