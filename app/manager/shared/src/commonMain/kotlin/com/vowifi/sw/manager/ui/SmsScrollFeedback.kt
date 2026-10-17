package com.vowifi.sw.manager.ui

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal class SmsScrollFeedback(val modifier: Modifier, val effect: OverscrollEffect?)

/** Keep the native long-range effect: an extra modifier clip truncates constrained sheet actions.
 * Empty ranges need the explicit modifier because Foundation skips their native effect.
 * Both paths use the pinned miuix spring, but exactly one is attached at a time.
 */
@Composable internal fun rememberSmsScrollFeedback(canScroll: Boolean): SmsScrollFeedback {
    val enabled = LocalAppRouteActive.current && smsInnerMotionMillis() > 0
    return if (canScroll) {
        val effect = rememberOverscrollEffect()
        SmsScrollFeedback(Modifier.managerScrollEndFeedback(), effect.takeIf { enabled })
    } else SmsScrollFeedback(Modifier.managerVerticalScrollFeedback(), null)
}
