package com.vowifi.sw.manager.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.min

/** The public factory receives a library's real source in create(), never via a capture/recompose. */
@Composable
internal fun rememberManagerPressIndication(style: ManagerPressStyle, enabled: Boolean): IndicationNodeFactory {
    val gate = rememberUpdatedState(enabled && LocalAppRouteActive.current)
    val foreground = rememberUpdatedState(LocalSoftVoWiFiColors.current.textPrimary)
    val focusColor = rememberUpdatedState(LocalSoftVoWiFiColors.current.interactive)
    val inputMode = rememberUpdatedState(LocalInputModeManager.current)
    return remember(style) { ManagerPressIndication(style, gate, foreground, focusColor, inputMode) }
}

private class ManagerPressIndication(
    private val style: ManagerPressStyle,
    private val gate: State<Boolean>,
    private val foreground: State<Color>,
    private val focusColor: State<Color>,
    private val inputMode: State<InputModeManager>,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        ManagerPressIndicationNode(style, gate, foreground, focusColor, inputMode, interactionSource)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = style.hashCode()
}

private class ManagerPressIndicationNode(
    private val style: ManagerPressStyle,
    private val gate: State<Boolean>,
    private val foreground: State<Color>,
    private val focusColor: State<Color>,
    private val inputMode: State<InputModeManager>,
    private val source: InteractionSource,
) : Modifier.Node(), DrawModifierNode {
    // Each node/source has its own state and deadlines, even when the factory is theme-scoped.
    private val core = ManagerPressMotionCore(style, gate)
    private val layerPaint = Paint()
    private val focusInteractions = mutableSetOf<FocusInteraction.Focus>()
    private val focused = mutableStateOf(false)

    override fun onAttach() {
        core.connect(coroutineScope, source)
        // Only private-source P4 rows need this; other styles already use their shape-aware helper.
        if (style == ManagerPressStyle.CardRow) coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focusInteractions.add(interaction)
                    is FocusInteraction.Unfocus -> focusInteractions.remove(interaction.focus)
                    else -> return@collect
                }
                focused.value = focusInteractions.isNotEmpty()
            }
        }
    }

    override fun onDetach() {
        core.disconnect()
        focusInteractions.clear()
        focused.value = false
    }

    override fun ContentDrawScope.draw() {
        val motion = core.motion
        val opacity = motion.contentAlpha.value
        val visualScale = motion.scale.value
        val tint = motion.tintAlpha.value
        // An external Solid indication sees the native surface in drawContent. Isolate it
        // only while tinting, so SrcAtop uses that surface's real squircle alpha, not the page.
        val surfaceTint = style == ManagerPressStyle.Solid && tint > 0f
        val isolate = opacity != 1f || surfaceTint
        if (isolate) {
            layerPaint.alpha = opacity
            drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), layerPaint)
        }
        try {
            // P1's 40dp circle is not shrunk along with its 24dp glyph.
            if (style == ManagerPressStyle.Icon && tint > 0f) {
                drawCircle(foreground.value.copy(alpha = tint), radius = 20.dp.toPx(), center = center)
            }
            withTransform({ scale(visualScale, visualScale, pivot = center) }) {
                this@draw.drawContent()
                if (tint > 0f && style != ManagerPressStyle.Icon) {
                    val color = foreground.value.copy(alpha = tint)
                    if (style == ManagerPressStyle.Round) {
                        drawCircle(color, radius = min(size.width, size.height) / 2f, center = center)
                    } else drawRect(color, topLeft = Offset.Zero, size = size,
                        blendMode = if (surfaceTint) BlendMode.SrcAtop else BlendMode.SrcOver)
                }
            }
        } finally {
            if (isolate) drawContext.canvas.restore()
        }
        if (style == ManagerPressStyle.CardRow && gate.value && focused.value &&
            inputMode.value.inputMode == InputMode.Keyboard) {
            val width = 2.dp.toPx()
            if (size.width > width && size.height > width) {
                drawRect(focusColor.value, topLeft = Offset(width / 2f, width / 2f),
                    size = Size(size.width - width, size.height - width), style = Stroke(width))
            }
        }
    }
}
