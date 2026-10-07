package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

internal class CommunicationContextTarget(val layer: GraphicsLayer) {
    var bounds by mutableStateOf(Rect.Zero)
    var presented by mutableStateOf(false)
}

internal val LocalCommunicationMenuActionsEnabled = staticCompositionLocalOf { true }
internal val smsContextMenuCompositing = CompositingStrategy.ModulateAlpha

/** Shape-rasterized SMS shadow, not an Android elevation/outline RenderNode. */
internal fun Modifier.smsContextShadow(shape: Shape, progress: () -> Float): Modifier =
    dropShadow(shape) {
        radius = 12.dp.toPx()
        offset = Offset(0f, 4.dp.toPx())
        color = Color.Black
        alpha = .12f * progress()
    }

private val ContextWindowPosition = object : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

@Composable
internal fun rememberCommunicationContextTarget(): CommunicationContextTarget {
    val layer = rememberGraphicsLayer()
    return remember(layer) { CommunicationContextTarget(layer) }
}

/** Record only the selected object, on-device, using Compose's retained drawing layer. */
internal fun Modifier.communicationContextSource(target: CommunicationContextTarget, selected: Boolean,
    hideUnderPreview: Boolean = true): Modifier =
    onGloballyPositioned { coordinates ->
        val origin = coordinates.positionInWindow()
        target.bounds = Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
    }.drawWithContent {
        if (!hideUnderPreview) drawContent()
        else if (selected || target.presented) {
            target.layer.record { this@drawWithContent.drawContent() }
            if (!target.presented) drawLayer(target.layer)
        } else drawContent()
    }

/** Official Compose popup: one focus owner, retained until the reversible sequence finishes. */
@Composable
internal fun CommunicationContextMenu(show: Boolean, target: CommunicationContextTarget, onDismiss: () -> Unit,
    sourceShape: Shape = RoundedCornerShape(14.dp), sourceColor: Color = communicationColors().list,
    alignEnd: Boolean = false, previewContent: (@Composable (progress: Float) -> Unit)? = null,
    stableSurface: Boolean = false,
    exitEasing: Easing = CubicBezierEasing(.2f, 0f, 0f, 1f),
    content: @Composable ColumnScope.(finish: (() -> Unit) -> Unit) -> Unit) {
    val active = LocalAppRouteActive.current
    val visible = remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var afterClose by remember { mutableStateOf<(() -> Unit)?>(null) }
    val dismiss by rememberUpdatedState(onDismiss)
    val currentShow by rememberUpdatedState(show)
    val currentActive by rememberUpdatedState(active)
    val colors = communicationColors()
    val anchored = target.bounds.width > 0f
    val requested = show && active && anchored && !closing
    val finish: (() -> Unit) -> Unit = { action ->
        if (requested && !closing && currentShow && currentActive) { closing = true; afterClose = action }
    }
    LaunchedEffect(show) { if (!show) closing = false }
    LaunchedEffect(requested, active) {
        if (!active) {
            afterClose = null
            progress.snapTo(0f)
            visible.value = false
            closing = false
        } else if (requested) {
            visible.value = true
            progress.animateTo(1f, tween(260, easing = CubicBezierEasing(.2f, .8f, .2f, 1f)))
        } else {
            progress.animateTo(0f, tween(170, easing = exitEasing))
            visible.value = false
            val action = afterClose
            afterClose = null
            if (currentShow) action?.invoke()
        }
    }
    if (!visible.value) return
    Popup(popupPositionProvider = ContextWindowPosition, onDismissRequest = { finish { dismiss() } },
        properties = PopupProperties(focusable = true, dismissOnBackPress = true,
            dismissOnClickOutside = false, clippingEnabled = false)) {
        DisposableEffect(target) {
            target.presented = true
            onDispose { target.presented = false }
        }
        val shade = if (colors.list.luminance() < .5f) .42f else .18f
        val currentFinish by rememberUpdatedState(finish)
        Box(Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { currentFinish { dismiss() } } }
            .semantics { paneTitle = "上下文操作" }) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }
                .background(Color.Black.copy(alpha = shade)))
            var origin by remember { mutableStateOf(Offset.Zero) }
            Layout(modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                .onGloballyPositioned { origin = it.positionInWindow() },
                content = {
                    // Decorative preview; never duplicate private content in popup semantics.
                    val focusedSurface = if (stableSurface) Modifier
                        .smsContextShadow(sourceShape) { progress.value }
                        .background(sourceColor, sourceShape).clip(sourceShape)
                    else Modifier.graphicsLayer {
                        shadowElevation = 8.dp.toPx() * progress.value
                        shape = sourceShape
                        clip = true
                    }.background(sourceColor)
                    Box(focusedSurface.clearAndSetSemantics { testTag = "communication-context-preview" }) {
                        if (previewContent == null) Box(Modifier.matchParentSize().drawWithContent { drawLayer(target.layer) })
                        else previewContent(progress.value)
                    }
                    CompositionLocalProvider(LocalCommunicationMenuActionsEnabled provides requested) {
                        val menuShape = RoundedCornerShape(16.dp)
                        val menuSurface = if (stableSurface) Modifier.smsContextShadow(menuShape) { progress.value }
                            .background(colors.list, menuShape).clip(menuShape)
                        else Modifier.graphicsLayer {
                            shadowElevation = 12.dp.toPx() * progress.value
                            shape = RoundedCornerShape(16.dp)
                            clip = true
                        }.background(colors.list)
                        Column(menuSurface.verticalScroll(rememberScrollState())) { content(finish) }
                    }
                }) { children, constraints ->
                val margin = 16.dp.roundToPx()
                val gap = 8.dp.roundToPx()
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val availableWidth = (width - margin * 2).coerceAtLeast(0)
                val availableHeight = (height - margin * 2).coerceAtLeast(0)
                val menu = children[1].measure(Constraints(
                    minWidth = minOf(248.dp.roundToPx(), availableWidth),
                    maxWidth = minOf(248.dp.roundToPx(), availableWidth), maxHeight = availableHeight,
                ))
                val bounds = target.bounds.translate(-origin)
                val previewWidth = minOf(bounds.width.roundToInt(), availableWidth)
                val remaining = availableHeight - menu.height - gap
                val previewMaxHeight = if (remaining >= 48.dp.roundToPx()) remaining else 0
                val preview = children[0].measure(if (previewContent == null)
                    Constraints.fixed(previewWidth, minOf(bounds.height.roundToInt(), previewMaxHeight))
                    else Constraints(minWidth = previewWidth, maxWidth = previewWidth, maxHeight = previewMaxHeight))
                val previewHeight = preview.height
                val x = bounds.left.roundToInt().coerceIn(margin, (width - margin - previewWidth).coerceAtLeast(margin))
                val originalY = bounds.top.roundToInt()
                val menuBelow = originalY >= margin && originalY + previewHeight + gap + menu.height <= height - margin
                val menuAbove = originalY - gap - menu.height >= margin && originalY + previewHeight <= height - margin
                val previewY = when {
                    menuBelow || menuAbove -> originalY
                    else -> originalY.coerceIn(margin, (height - margin - previewHeight - gap - menu.height).coerceAtLeast(margin))
                }
                val menuY = when {
                    previewHeight == 0 -> originalY.coerceIn(margin, (height - margin - menu.height).coerceAtLeast(margin))
                    !menuBelow && menuAbove -> previewY - gap - menu.height
                    else -> previewY + previewHeight + gap
                }
                val menuX = (if (alignEnd) x + previewWidth - menu.width else x)
                    .coerceIn(margin, (width - margin - menu.width).coerceAtLeast(margin))
                layout(width, height) {
                    if (previewHeight > 0) preview.placeWithLayer(x, previewY) {
                        translationX = (bounds.left - x) * (1f - progress.value)
                        translationY = (bounds.top - previewY) * (1f - progress.value)
                    }
                    menu.placeWithLayer(menuX, menuY) {
                        // Auto alpha uses a rectangular offscreen buffer and clips exterior shadows.
                        if (stableSurface) compositingStrategy = smsContextMenuCompositing
                        alpha = progress.value
                        scaleX = .94f + .06f * progress.value
                        scaleY = scaleX
                        val above = menuY < previewY
                        transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, if (above) 1f else 0f)
                        translationY = (if (above) 6.dp.toPx() else -6.dp.toPx()) * (1f - progress.value)
                    }
                }
            }
        }
    }
}
