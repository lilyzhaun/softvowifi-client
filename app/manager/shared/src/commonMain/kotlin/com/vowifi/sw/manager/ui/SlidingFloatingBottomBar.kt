// SPDX-License-Identifier: GPL-3.0-only
// Closely adapted from KernelSU FloatingBottomBar.kt @ befbcd388bb6c10befd3645311ad08e556d362ac.
package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
) {
    val density = LocalDensity.current
    val layout = floatingRowLayoutContract()
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val scope = rememberCoroutineScope()
    val latestSelectedIndex by rememberUpdatedState(selectedIndex)
    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }
    var grabOffsetPx by remember { mutableFloatStateOf(0f) }
    var currentIndex by remember { mutableIntStateOf(selectedIndex) }
    val rawOffset = remember { Animatable(0f) }
    val innerPaddingPx = with(density) { layout.innerPaddingDp.dp.toPx() }
    val rubberBandPx = with(density) { floatingBubbleBarContract().rubberBandDp.dp.toPx() }
    val panelOffset by remember {
        derivedStateOf { rubberBandPanelOffset(rawOffset.value, totalWidthPx, rubberBandPx) }
    }
    val drag = remember(scope, items.size, ltr) {
        DampedDragAnimation(
            scope = scope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..items.lastIndex.toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = floatingBubbleBarContract().pressedScale,
            onDragStarted = { down -> grabOffsetPx = down.x },
            onDragStopped = {
                val target = targetValue.roundToInt().coerceIn(0, items.lastIndex)
                currentIndex = target
                animateToValue(target.toFloat())
                scope.launch { rawOffset.animateTo(0f, spring(1f, 300f, 0.5f)) }
                if (target != latestSelectedIndex) onSelected(target)
            },
            onDragCancelled = {
                currentIndex = latestSelectedIndex
                animateToValue(latestSelectedIndex.toFloat())
                scope.launch { rawOffset.animateTo(0f, spring(1f, 300f, 0.5f)) }
            },
            onDrag = { _, pointer, amount ->
                if (tabWidthPx > 0f) {
                    val origin = if (ltr) innerPaddingPx + value * tabWidthPx else totalWidthPx - innerPaddingPx - tabWidthPx - value * tabWidthPx
                    updateValue(absolutePointerBubblePosition(origin + panelOffset + pointer.x, grabOffsetPx, totalWidthPx, tabWidthPx, innerPaddingPx, panelOffset, items.size, !ltr))
                    scope.launch { rawOffset.snapTo(rawOffset.value + amount.x) }
                }
            },
        )
    }
    LaunchedEffect(selectedIndex) {
        snapshotFlow { selectedIndex }.collectLatest {
            currentIndex = it
            drag.animateToValue(it.toFloat())
        }
    }
    SlidingBarLayers(
        modifier = modifier,
        items = items,
        selectedIndex = currentIndex,
        onSelected = { index ->
            currentIndex = index
            drag.animateToValue(index.toFloat())
            if (index != selectedIndex) onSelected(index)
        },
        liquidEnabled = spec.effectiveLiquidGlass && backdrop != null,
        backdrop = backdrop,
        tabWidthPx = tabWidthPx,
        panelOffset = panelOffset,
        drag = drag,
        ltr = ltr,
        onMeasured = { width ->
            totalWidthPx = width.toFloat()
            tabWidthPx = (width - with(density) { 8.dp.toPx() }) / items.size
        },
    )
}

internal data class SlidingBarItem(val label: String, val icon: ImageVector)
