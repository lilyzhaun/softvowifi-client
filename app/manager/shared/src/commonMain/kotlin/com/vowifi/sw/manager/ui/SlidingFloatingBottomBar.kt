// SPDX-License-Identifier: GPL-3.0-only
// Closely adapted from KernelSU FloatingBottomBar.kt @ befbcd388bb6c10befd3645311ad08e556d362ac.
package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import kotlin.math.roundToInt

@Composable
internal fun SlidingFloatingBottomBar(
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    spec: AppChromeSpec,
    backdrop: LayerBackdrop?,
    items: List<SlidingBarItem>,
    modifier: Modifier = Modifier,
    onReselected: (Int) -> Unit = {},
    pagerState: PagerState? = null,
) {
    val density = LocalDensity.current
    val layout = floatingRowLayoutContract()
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val scope = rememberCoroutineScope()
    val active by rememberUpdatedState(LocalAppRouteActive.current)
    val latestSelectedIndex by rememberUpdatedState(selectedIndex)
    val latestOnSelected by rememberUpdatedState(onSelected)
    val latestOnReselected by rememberUpdatedState(onReselected)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }
    var grabOffsetPx by remember { mutableFloatStateOf(0f) }
    var currentIndex by remember { mutableIntStateOf(selectedIndex) }
    val rawOffset = remember { Animatable(0f) }
    var followingPager by remember(pagerState) { mutableStateOf(false) }
    fun takeBubblePosition(animation: DampedDragAnimation) {
        if (followingPager && pagerState != null) {
            val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
            animation.snapToValue(position)
            followingPager = false
        }
    }
    val innerPaddingPx = with(density) { layout.innerPaddingDp.dp.toPx() }
    val rubberBandPx = with(density) { floatingBubbleBarContract().rubberBandDp.dp.toPx() }
    val panelOffset by remember {
        derivedStateOf { rubberBandPanelOffset(rawOffset.value, totalWidthPx, rubberBandPx) }
    }
    val reselectionPointers = remember(scope, items.size, ltr, touchSlop) { FloatingReselectionPointerState() }
    val drag = remember(scope, items.size, ltr, touchSlop, pagerState) {
        var travel = Offset.Zero
        var movedBeyondSlop = false
        DampedDragAnimation(
            scope = scope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..items.lastIndex.toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = floatingBubbleBarContract().pressedScale,
            canDrag = { active },
            onDragStarted = start@{ down ->
                if (!active) return@start
                takeBubblePosition(this)
                grabOffsetPx = down.x
                travel = Offset.Zero
                movedBeyondSlop = false
            },
            onDragStopped = stopped@{
                if (!active) return@stopped
                val target = targetValue.roundToInt().coerceIn(0, items.lastIndex)
                currentIndex = target
                animateToValue(target.toFloat())
                scope.launch { rawOffset.animateTo(0f, spring(1f, 300f, 0.5f)) }
                if (target != latestSelectedIndex) latestOnSelected(target)
                else if (!movedBeyondSlop && reselectionPointers.isSinglePointer) latestOnReselected(target)
            },
            onDragCancelled = {
                currentIndex = latestSelectedIndex
                animateToValue(latestSelectedIndex.toFloat())
                scope.launch { rawOffset.animateTo(0f, spring(1f, 300f, 0.5f)) }
            },
            onDrag = move@{ _, pointer, amount ->
                if (!active) return@move
                travel += amount
                if (travel.getDistance() > touchSlop) movedBeyondSlop = true
                if (tabWidthPx > 0f) {
                    val origin = if (ltr) innerPaddingPx + value * tabWidthPx else totalWidthPx - innerPaddingPx - tabWidthPx - value * tabWidthPx
                    updateValue(absolutePointerBubblePosition(origin + panelOffset + pointer.x, grabOffsetPx, totalWidthPx, tabWidthPx, innerPaddingPx, panelOffset, items.size, !ltr))
                    scope.launch { if (active) rawOffset.snapTo(rawOffset.value + amount.x) }
                }
            },
        )
    }
    LaunchedEffect(pagerState) {
        pagerState?.interactionSource?.interactions?.collect { interaction ->
            // Keep the pager as the position owner through fling and rest, until
            // a real bar gesture takes over. Stop/Cancel is not the end of fling.
            if (interaction is DragInteraction.Start && active) followingPager = true
        }
    }
    LaunchedEffect(active) {
        if (!active) {
            currentIndex = latestSelectedIndex
            drag.animateToValue(latestSelectedIndex.toFloat())
            rawOffset.animateTo(0f, spring(1f, 300f, 0.5f))
        }
    }
    LaunchedEffect(selectedIndex) {
        snapshotFlow { selectedIndex }.collectLatest {
            currentIndex = it
            if (!followingPager) drag.animateToValue(it.toFloat())
        }
    }
    SlidingBarLayers(
        modifier = modifier.pointerInput(reselectionPointers) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                reselectionPointers.isSinglePointer = currentEvent.changes.none { it.pressed && it.id != down.id }
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.pressed && it.id != down.id }) {
                        reselectionPointers.isSinglePointer = false
                    }
                } while (event.changes.any { it.pressed })
                // Keep rejection latched through final UP: Initial precedes the drag's end callback.
                // Only the next DOWN resets it. Observation never consumes or cancels the retained drag.
            }
        },
        items = items,
        selectedIndex = currentIndex,
        onSelected = select@{ index ->
            if (!active) return@select
            takeBubblePosition(drag)
            currentIndex = index
            drag.animateToValue(index.toFloat())
            if (index != latestSelectedIndex) latestOnSelected(index) else latestOnReselected(index)
        },
        liquidEnabled = spec.effectiveLiquidGlass && backdrop != null,
        backdrop = backdrop,
        tabWidthPx = tabWidthPx,
        panelOffset = panelOffset,
        drag = drag,
        position = {
            if (followingPager && pagerState != null) pagerState.currentPage + pagerState.currentPageOffsetFraction
            else drag.value
        },
        ltr = ltr,
        onMeasured = { width ->
            totalWidthPx = width.toFloat()
            tabWidthPx = (width - with(density) { 8.dp.toPx() }) / items.size
        },
    )
}

internal data class SlidingBarItem(val label: String, val icon: ImageVector)

private class FloatingReselectionPointerState {
    var isSinglePointer = false
}
