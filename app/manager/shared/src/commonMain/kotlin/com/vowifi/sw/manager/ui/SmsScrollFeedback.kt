package com.vowifi.sw.manager.ui

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal class SmsScrollFeedback(val modifier: Modifier, val effect: OverscrollEffect?)

/** Use the scrollable's effect for real scroll ranges, including accessibility scroll actions.
 * Foundation skips that effect on empty ranges; only those short pages need the nested modifier.
 */
@Composable internal fun rememberSmsScrollFeedback(canScroll: Boolean): SmsScrollFeedback {
    val effect = rememberOverscrollEffect()
    val enabled = LocalAppRouteActive.current && smsInnerMotionMillis() > 0
    return if (canScroll) SmsScrollFeedback(Modifier, effect.takeIf { enabled })
        else SmsScrollFeedback(Modifier.phoneOverscroll(), null)
}
