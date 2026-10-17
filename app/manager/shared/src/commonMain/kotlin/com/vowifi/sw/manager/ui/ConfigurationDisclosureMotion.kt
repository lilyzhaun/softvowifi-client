package com.vowifi.sw.manager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** C7: the card owns size; this retained leaf owns only opacity and revokes input on exit. */
@Composable
internal fun ConfigurationDisclosureContent(expanded: Boolean, content: @Composable () -> Unit) {
    val interactive = expanded && LocalAppRouteActive.current
    AnimatedVisibility(expanded, enter = EnterTransition.None, exit = ExitTransition.None,
        label = "configuration-disclosure") {
        val alpha = transition.animateFloat(
            transitionSpec = {
                if (targetState == EnterExitState.Visible) tween(
                    durationMillis = MotionTokens.DurFast,
                    delayMillis = if (initialState == EnterExitState.PreEnter) MotionTokens.DisclosureContentDelayMillis else MotionTokens.DurInstant,
                    easing = MotionTokens.EaseStandard,
                ) else tween(durationMillis = MotionTokens.DurMicro, easing = MotionTokens.EaseStandard)
            },
            label = "configuration-disclosure-alpha",
        ) { if (it == EnterExitState.Visible) 1f else 0f }
        CompositionLocalProvider(LocalAppRouteActive provides interactive) {
            Column(
                Modifier.graphicsLayer { this.alpha = alpha.value }
                    .managerPressInputGate(interactive)
                    .focusProperties { canFocus = interactive }
                    .then(if (interactive) Modifier else Modifier.clearAndSetSemantics { }),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { content() }
        }
    }
}
