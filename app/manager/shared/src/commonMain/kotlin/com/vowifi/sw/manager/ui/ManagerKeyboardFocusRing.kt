package com.vowifi.sw.manager.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.squircle.SquircleDefaults
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled

// Public shape API only; no internal implementation copied.
// compose-miuix-ui/miuix 0.9.3 @ c36fab72391801d1e3ea5a00f966bf16bac28d4c, Apache-2.0.
// https://github.com/compose-miuix-ui/miuix/blob/c36fab72391801d1e3ea5a00f966bf16bac28d4c/miuix-squircle/src/commonMain/kotlin/top/yukonga/miuix/kmp/squircle/SquirclePath.kt
@Composable
internal fun Modifier.managerKeyboardFocusRing(
    interaction: MutableInteractionSource,
    enabled: Boolean,
    shape: Shape? = null,
    color: Color? = null,
    cornerRadius: Dp = ButtonDefaults.CornerRadius,
): Modifier {
    val focused = interaction.collectIsFocusedAsState()
    val inputMode = LocalInputModeManager.current
    val ink = color ?: LocalSoftVoWiFiColors.current.interactive
    val squircle = isSquircleEnabled()
    return drawWithCache {
        val width = 2.dp.toPx()
        val inset = width / 2f
        val drawable = size.width > width && size.height > width
        val path = Path()
        if (drawable && shape == null) path.addSquircleRect(
            width = size.width - width, height = size.height - width,
            cornerRadius = (cornerRadius.toPx() - inset).coerceAtLeast(0f),
            extension = SquircleDefaults.Extension, squircleEnabled = squircle,
        )
        val outline = if (drawable) shape?.createOutline(size, layoutDirection, this) else null
        val clip = outline?.let { Path().apply { addOutline(it) } }
        val stroke = Stroke(width)
        val innerStroke = Stroke(width * 2f)
        onDrawWithContent {
            drawContent()
            // Read focus and platform mode only during drawing; keep the original layout and hit owner.
            if (drawable && enabled && focused.value && inputMode.inputMode == InputMode.Keyboard) {
                if (outline != null && clip != null) {
                    // Clip the centered stroke to the original shape: exactly 2dp stays inside.
                    clipPath(clip) { drawOutline(outline, ink, style = innerStroke) }
                } else {
                    translate(inset, inset) { drawPath(path, ink, style = stroke) }
                }
            }
        }
    }
}

/** Focus only: library selectable/toggleable supplies its real source to create(). No press visuals. */
@Composable
internal fun rememberManagerKeyboardFocusIndication(
    enabled: Boolean,
    shape: Shape = RectangleShape,
    color: Color? = null,
): IndicationNodeFactory {
    val gate = rememberUpdatedState(enabled && LocalAppRouteActive.current)
    val ink = rememberUpdatedState(color ?: LocalSoftVoWiFiColors.current.interactive)
    val inputMode = rememberUpdatedState(LocalInputModeManager.current)
    val outlineShape = rememberUpdatedState(shape)
    return remember { ManagerKeyboardFocusIndication(gate, ink, inputMode, outlineShape) }
}

private class ManagerKeyboardFocusIndication(
    private val gate: State<Boolean>,
    private val ink: State<Color>,
    private val inputMode: State<InputModeManager>,
    private val shape: State<Shape>,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        ManagerKeyboardFocusNode(interactionSource, gate, ink, inputMode, shape)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0
}

private class ManagerKeyboardFocusNode(
    private val source: InteractionSource,
    private val gate: State<Boolean>,
    private val ink: State<Color>,
    private val inputMode: State<InputModeManager>,
    private val shape: State<Shape>,
) : Modifier.Node(), DrawModifierNode {
    private var focus: FocusInteraction.Focus? = null
    private val focused = mutableStateOf(false)
    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> { focus = interaction; focused.value = true }
                    is FocusInteraction.Unfocus -> if (focus === interaction.focus) { focus = null; focused.value = false }
                    else -> Unit
                }
            }
        }
    }
    // Modifier.Node cancels coroutineScope on detach; do not carry the previous source's focus.
    override fun onDetach() { focus = null; focused.value = false }
    override fun ContentDrawScope.draw() {
        drawContent()
        if (gate.value && focused.value && inputMode.value.inputMode == InputMode.Keyboard) {
            val width = 2.dp.toPx()
            if (size.width > width && size.height > width) {
                val outline = shape.value.createOutline(size, layoutDirection, this)
                val clip = Path().apply { addOutline(outline) }
                clipPath(clip) { drawOutline(outline, ink.value, style = Stroke(width * 2f)) }
            }
        }
    }
}
