package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.EaseOut
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

internal enum class TabClickIndication { NONE }

internal data class FloatingBubbleBarContract(
    val outerHeightDp: Int,
    val indicatorHeightDp: Int,
    val tabMinimumWidthDp: Int,
    val tabMinimumHeightDp: Int,
    val innerPaddingDp: Int,
    val bottomPaddingDp: Int,
    val addsNavigationBarInset: Boolean,
    val rubberBandDp: Int,
    val pressedScale: Float,
    val renderLayerCount: Int,
    val draggable: Boolean,
    val continuousPosition: Boolean,
    val pagerLinked: Boolean,
    val supportsRtl: Boolean,
    val semanticsOnBaseOnly: Boolean,
    val tabClickIndication: TabClickIndication,
    val dragGestureOwner: FloatingDragGestureOwner,
    val baseRowDraggable: Boolean,
    val horizontalOnlyMotion: Boolean,
    val stableMeasuredBounds: Boolean,
    val usesVerticalExpansion: Boolean,
    val shapeAwareDropShadow: Boolean,
    val animatesShadowElevation: Boolean,
    val dropShadowRadiusDp: Int,
    val alwaysComposedStableSlot: Boolean = false,
    val usesUnmountingVisibility: Boolean = true,
)

internal fun floatingBubbleBarContract(): FloatingBubbleBarContract = FloatingBubbleBarContract(
    outerHeightDp = 64,
    indicatorHeightDp = 56,
    tabMinimumWidthDp = 76,
    tabMinimumHeightDp = 48,
    innerPaddingDp = 4,
    bottomPaddingDp = 12,
    addsNavigationBarInset = true,
    rubberBandDp = 4,
    pressedScale = 78f / 56f,
    renderLayerCount = 3,
    draggable = true,
    continuousPosition = true,
    pagerLinked = true,
    supportsRtl = true,
    semanticsOnBaseOnly = true,
    tabClickIndication = TabClickIndication.NONE,
    dragGestureOwner = FloatingDragGestureOwner.Indicator,
    baseRowDraggable = false,
    horizontalOnlyMotion = true,
    stableMeasuredBounds = true,
    usesVerticalExpansion = false,
    shapeAwareDropShadow = true,
    animatesShadowElevation = false,
    dropShadowRadiusDp = 10,
    alwaysComposedStableSlot = true,
    usesUnmountingVisibility = false,
)

internal fun clampBubblePosition(position: Float, tabCount: Int): Float =
    position.coerceIn(0f, (tabCount - 1).toFloat())

internal fun dragBubblePosition(
    current: Float,
    deltaPx: Float,
    tabWidthPx: Float,
    tabCount: Int,
    rtl: Boolean,
): Float {
    if (tabWidthPx <= 0f) return current
    val directionalDelta = deltaPx / tabWidthPx * if (rtl) -1f else 1f
    return clampBubblePosition(current + directionalDelta, tabCount)
}

internal fun settleBubbleIndex(position: Float, tabCount: Int): Int =
    position.roundToInt().coerceIn(0, tabCount - 1)

internal fun bubbleTranslationPx(position: Float, tabWidthPx: Float, rtl: Boolean): Float =
    position * tabWidthPx * if (rtl) -1f else 1f

internal fun absolutePointerBubblePosition(
    pointerInBarPx: Float,
    grabOffsetPx: Float,
    barWidthPx: Float,
    tabWidthPx: Float,
    innerPaddingPx: Float,
    panelOffsetPx: Float,
    tabCount: Int,
    rtl: Boolean,
): Float {
    if (tabWidthPx <= 0f) return 0f
    val draggedOriginPx = pointerInBarPx - grabOffsetPx - panelOffsetPx
    val position = if (rtl) {
        (barWidthPx - innerPaddingPx - tabWidthPx - draggedOriginPx) / tabWidthPx
    } else {
        (draggedOriginPx - innerPaddingPx) / tabWidthPx
    }
    return clampBubblePosition(position, tabCount)
}

internal fun rubberBandPanelOffset(rawOffsetPx: Float, totalWidthPx: Float, rubberBandPx: Float): Float {
    if (totalWidthPx <= 0f) return 0f
    val fraction = (rawOffsetPx / totalWidthPx).coerceIn(-1f, 1f)
    return rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
}

internal class SlidingBottomBarModel(initialIndex: Int, private val tabCount: Int) {
    var position: Float = initialIndex.toFloat()
        private set
    var targetPosition: Float = position
        private set
    var dragActive: Boolean = false
        private set
    private var selectionPending = false

    fun beginDrag() {
        dragActive = true
    }

    fun dragBy(deltaPx: Float, tabWidthPx: Float, rtl: Boolean) {
        if (!dragActive) return
        position = dragBubblePosition(position, deltaPx, tabWidthPx, tabCount, rtl)
    }

    fun endDrag(): Int {
        val index = settleBubbleIndex(position, tabCount)
        targetPosition = index.toFloat()
        position = targetPosition
        dragActive = false
        selectionPending = true
        return index
    }

    fun cancelDrag() {
        position = targetPosition
        dragActive = false
    }

    fun externalSelection(index: Int) {
        targetPosition = index.coerceIn(0, tabCount - 1).toFloat()
        if (!dragActive) position = targetPosition
    }

    fun consumeSelectionCallback(currentSelected: Int): Int? {
        if (!selectionPending) return null
        selectionPending = false
        val index = targetPosition.roundToInt()
        return index.takeIf { it != currentSelected }
    }
}
