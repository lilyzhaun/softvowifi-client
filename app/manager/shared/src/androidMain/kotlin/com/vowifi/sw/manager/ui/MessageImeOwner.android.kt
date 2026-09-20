package com.vowifi.sw.manager.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

@Composable
internal actual fun rememberMessageImeOwnerRequest(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        {
            val enforce = {
                view.context.activity()?.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                )
                Unit
            }
            enforce()
            view.post(enforce)
            view.postDelayed(enforce, 100L)
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
