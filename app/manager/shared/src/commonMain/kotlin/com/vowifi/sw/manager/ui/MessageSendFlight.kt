package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

internal class MessageSendFlight(val id: String, val body: String, val text: Rect, val textColor: Color)

internal val LocalMessageSendFlight = staticCompositionLocalOf<MessageSendFlightState?> { null }

/**
 * 2026-10-17 user request: the submitted text leaves the floating composer and lands as its own
 * persisted queued bubble. The ghost only draws; input, semantics and the retained draft stay with
 * the real composer and bubble, and the real bubble stays hidden until the ghost has landed.
 */
@Stable
internal class MessageSendFlightState {
    // Page-local geometry from placement callbacks; plain fields never recompose the page.
    var page: LayoutCoordinates? = null
    var textArea: Rect? = null
    var textColor: Color = Color.Unspecified
    /** False while search or an inactive route cannot follow to the landing bubble. */
    var landingVisible = true

    var pendingHandoff by mutableStateOf<String?>(null)
    var handedOff by mutableStateOf<String?>(null)
        private set
    var flight by mutableStateOf<MessageSendFlight?>(null)
        private set
    private var landed by mutableStateOf<String?>(null)
    private var targetId: String? = null
    var target by mutableStateOf<Rect?>(null)
        private set
    val progress = Animatable(0f)

    /** The persisted bubble is hidden from its first frame until its own flight has ended. */
    fun hides(id: String): Boolean = flight?.id == id || id == pendingHandoff && landed != id

    fun reportTarget(id: String, coordinates: LayoutCoordinates) {
        if (id != pendingHandoff && id != flight?.id) return
        val page = page?.takeIf { it.isAttached } ?: return
        if (!coordinates.isAttached) return
        targetId = id
        val bounds = page.localBoundingBoxOf(coordinates, clipBounds = false)
        if (bounds != target) target = bounds
    }

    fun releaseTarget(id: String) {
        if (targetId == id) { targetId = null; target = null }
    }

    suspend fun fly(id: String, body: String, animate: Boolean) {
        val source = textArea?.takeIf { animate && landingVisible && body.isNotEmpty() }
        if (source == null) {
            handedOff = id
            landed = id
            return
        }
        if (targetId != id) target = null
        flight = MessageSendFlight(id, body, source, textColor)
        handedOff = id
        try {
            progress.snapTo(0f)
            val landing = withTimeoutOrNull(MotionTokens.DurBase.toLong()) {
                snapshotFlow { target.takeIf { targetId == id } }.first { it != null }
            }
            if (landing != null) progress.animateTo(1f, MotionTokens.SpringSpatialFraction)
        } finally {
            if (flight?.id == id) flight = null
            landed = id
        }
    }

    fun frame(flight: MessageSendFlight, padX: Float, padY: Float): Rect {
        val text = flight.text
        val source = Rect(text.left - padX, text.top - padY, text.right + padX, text.bottom + padY)
        val landing = target.takeIf { targetId == flight.id } ?: return source
        return lerp(source, landing, progress.value)
    }
}

@Composable
internal fun rememberMessageSendFlightState(): MessageSendFlightState = remember { MessageSendFlightState() }

// The input glyphs leave first, the bubble surface gathers, then its own content settles in.
private fun Float.window(start: Float, end: Float): Float = ((this - start) / (end - start)).coerceIn(0f, 1f)
private fun flightSurfaceAlpha(progress: Float) = progress.window(0f, .3f)
private fun flightInputAlpha(progress: Float) = 1f - progress.window(0f, .45f)
private fun flightBubbleAlpha(progress: Float) = progress.window(.1f, .55f)

@Composable
internal fun MessageSendFlightOverlay(state: MessageSendFlightState, item: MessageThreadItem.OutgoingBubble?) {
    val flight = state.flight ?: return
    val density = LocalDensity.current
    val spec = messageBubbleLayoutContract(MessageBubbleDirection.OUTGOING)
    val padX = with(density) { MESSAGE_BUBBLE_CONTENT_PADDING_X.toPx() }
    val padY = with(density) { MESSAGE_BUBBLE_CONTENT_PADDING_Y.toPx() }
    val corners = with(density) {
        listOf(spec.topStartRadiusDp, spec.topEndRadiusDp, spec.bottomEndRadiusDp, spec.bottomStartRadiusDp)
            .map { CornerRadius(it.dp.toPx()) }
    }
    val fill = communicationColors().outgoing
    val outline = remember { Path() }
    Layout(
        content = {
            // Same style as the field's own BasicTextField, so the first frame keeps its line breaks.
            BasicText(flight.body, style = TextStyle(color = flight.textColor, fontSize = MESSAGE_COMPOSER_FONT_SP.sp,
                lineHeight = (MESSAGE_COMPOSER_FONT_SP + 5).sp), softWrap = true)
            if (item != null) MessageBubbleFlightContent(item)
        },
        modifier = Modifier.fillMaxSize().clearAndSetSemantics { }.drawWithContent {
            val frame = state.frame(flight, padX, padY)
            outline.reset()
            outline.addRoundRect(RoundRect(frame, corners[0], corners[1], corners[2], corners[3]))
            drawPath(outline, fill.copy(alpha = fill.alpha * flightSurfaceAlpha(state.progress.value)))
            clipPath(outline) { this@drawWithContent.drawContent() }
        },
    ) { measurables, constraints ->
        val frame = state.frame(flight, padX, padY)
        val width = flight.text.width.roundToInt().coerceAtLeast(0)
        val text = measurables[0].measure(Constraints(minWidth = width, maxWidth = width))
        val landing = state.target
        val bubble = if (landing == null) null else measurables.getOrNull(1)?.measure(
            Constraints.fixedWidth(landing.width.roundToInt().coerceAtLeast(0)))
        // A draft taller than the 5-line field was showing its last lines; keep that same window.
        val scrolled = (flight.text.height - text.height).coerceAtMost(0f)
        layout(constraints.maxWidth, constraints.maxHeight) {
            text.placeWithLayer((frame.left + padX).roundToInt(), (frame.top + padY + scrolled).roundToInt()) {
                alpha = flightInputAlpha(state.progress.value)
            }
            bubble?.placeWithLayer(frame.left.roundToInt(), frame.top.roundToInt()) {
                alpha = flightBubbleAlpha(state.progress.value)
            }
        }
    }
}
