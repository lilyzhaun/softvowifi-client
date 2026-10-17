package com.vowifi.sw.manager.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.LocalContentColor

/** Business tone changes animate; a new palette/font environment starts at its current truth. */
@Composable
internal fun rememberManagerStateColor(target: Color): State<Color> =
    key(LocalSoftVoWiFiColors.current, LocalDensity.current) {
        animateColorAsState(target, tween(MotionTokens.DurBase, easing = MotionTokens.EaseStandard), label = "manager-state-color")
    }

/** Only this text leaf is retained during exit. Its old content has no accessible semantics. */
@Composable
internal fun ManagerStateText(
    text: String,
    color: ColorProducer,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight? = null,
    style: TextStyle = MiuixTheme.textStyles.main,
    softWrap: Boolean = true,
    numericRoll: Boolean = false,
    animateWidth: Boolean = false,
) {
    val density = LocalDensity.current
    key(LocalSoftVoWiFiColors.current, density, style, fontSize, lineHeight, fontWeight) {
        val currentText = rememberUpdatedState(text)
        AnimatedContent(
            targetState = text,
            contentAlignment = Alignment.TopStart,
            transitionSpec = {
                val oldNumber = initialState.toLongOrNull()
                val newNumber = targetState.toLongOrNull()
                val roll = MotionTokens.NumericRoll
                val increasing = oldNumber != null && newNumber != null && newNumber > oldNumber
                val rolling = numericRoll && oldNumber != null && newNumber != null && oldNumber != newNumber
                val alphaSpec = if (rolling) roll.animationSpec else MotionTokens.Crossfade.alphaSpec
                val enter = fadeIn(alphaSpec)
                val exit = fadeOut(alphaSpec)
                val transform = if (rolling) {
                    val offsetSpec = tween<IntOffset>(
                        durationMillis = roll.animationSpec.durationMillis,
                        delayMillis = roll.animationSpec.delay,
                        easing = roll.animationSpec.easing,
                    )
                    (enter + slideInVertically(offsetSpec) { with(density) { roll.incomingOffset(increasing).roundToPx() } }) togetherWith
                        (exit + slideOutVertically(offsetSpec) { with(density) { roll.outgoingOffset(increasing).roundToPx() } })
                } else enter togetherWith exit
                transform using if (animateWidth) SizeTransform(clip = false) { _, _ ->
                    val token = MotionTokens.SpringSpatialFast
                    spring<IntSize>(dampingRatio = token.dampingRatio, stiffness = token.stiffness)
                } else null
            },
            label = "manager-state-text",
        ) { visibleText ->
            Text(
                text = visibleText,
                modifier = if (visibleText == currentText.value) Modifier else Modifier.clearAndSetSemantics { },
                color = color,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontWeight = fontWeight,
                style = style,
                softWrap = softWrap,
            )
        }
    }
}

private data class ManagerActionContent(val label: String, val busy: Boolean)

/** The button remains one click owner; only its two content leaves survive a crossfade. */
@Composable
internal fun ManagerActionStateContent(label: String, busy: Boolean) {
    val style = MiuixTheme.textStyles.main
    val color = LocalContentColor.current
    key(LocalSoftVoWiFiColors.current, LocalDensity.current, style) {
        val target = ManagerActionContent(label, busy)
        val current = rememberUpdatedState(target)
        AnimatedContent(target, contentAlignment = Alignment.Center,
            transitionSpec = { (EnterTransition.None togetherWith ExitTransition.None) using null },
            label = "manager-action-content",
        ) { shown ->
            val textAlpha = transition.animateFloat(transitionSpec = { MotionTokens.Crossfade.alphaSpec },
                label = "manager-action-text-alpha") { if (it == EnterExitState.Visible) 1f else 0f }
            val spinnerAlpha = transition.animateFloat(
                transitionSpec = { tween(MotionTokens.DurFast, easing = MotionTokens.EaseStandard) },
                label = "manager-action-spinner-alpha") { if (it == EnterExitState.Visible) 1f else 0f }
            Row(if (shown == current.value) Modifier else Modifier.clearAndSetSemantics { },
                verticalAlignment = Alignment.CenterVertically) {
                if (shown.busy) Box(Modifier.size(20.dp), contentAlignment = Alignment.CenterStart) {
                    // Original allocation is 20dp including a 4dp tail gap: the actual circle is 16dp.
                    // Match the public drawing size and layout; never draw a default 30dp arc into 16x20.
                    CircularProgressIndicator(size = 16.dp,
                        modifier = Modifier.size(16.dp).graphicsLayer { alpha = spinnerAlpha.value })
                }
                Text(shown.label, color = color, fontSize = 14.sp, lineHeight = 20.sp,
                    style = style, modifier = Modifier.graphicsLayer { alpha = textAlpha.value })
            }
        }
    }
}
