package com.vowifi.sw.manager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

/** Overlay only: retain the exit drawing, never retain the dismissed action. */
@Composable
internal fun MessageNewMessagesHint(visible: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val actionable = visible && LocalAppRouteActive.current
    val actionableNow by rememberUpdatedState(actionable)
    val clickNow by rememberUpdatedState(onClick)
    val interaction = remember { MutableInteractionSource() }
    val indication = rememberManagerPressIndication(ManagerPressStyle.Solid, actionable)
    val colors = ButtonDefaults.buttonColors()
    val rise = with(LocalDensity.current) { MotionTokens.OffsetSmall.roundToPx() }
    val accepted = rememberManagerActionHandler(managerActionFeedback(ManagerAction.ContextAction, actionable)) { clickNow() }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(MotionTokens.DurBase, easing = MotionTokens.EaseEnter)) +
            slideInVertically(tween(MotionTokens.DurBase, easing = MotionTokens.EaseEnter)) { rise },
        exit = fadeOut(tween(MotionTokens.DurFast, easing = MotionTokens.EaseExit)) +
            slideOutVertically(tween(MotionTokens.DurFast, easing = MotionTokens.EaseExit)) { rise },
    ) {
        Button(
            onClick = { if (actionableNow) accepted() },
            enabled = actionable,
            colors = colors.copy(disabledColor = colors.color, disabledContentColor = colors.contentColor),
            interactionSource = interaction,
            indication = null,
            modifier = Modifier.padding(8.dp).heightIn(min = 48.dp)
                .managerKeyboardFocusRing(interaction, actionable)
                .managerPressInputGate(actionable).indication(interaction, indication),
        ) { Text("查看新消息") }
    }
}
