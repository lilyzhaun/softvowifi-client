// SPDX-License-Identifier: GPL-3.0-only
// Closely adapted from KernelSU FloatingBottomBar.kt @ befbcd388bb6c10befd3645311ad08e556d362ac.
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.ui.liquid.lens
import com.vowifi.sw.manager.ui.liquid.rememberCombinedBackdrop
import com.vowifi.sw.manager.ui.liquid.rememberInteractiveHighlight
import com.vowifi.sw.manager.ui.liquid.vibrancy
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

@Composable
internal fun SlidingBarLayers(
    modifier: Modifier,
    items: List<SlidingBarItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    liquidEnabled: Boolean,
    backdrop: LayerBackdrop?,
    tabWidthPx: Float,
    panelOffset: Float,
    drag: DampedDragAnimation,
    ltr: Boolean,
    onMeasured: (Int) -> Unit,
) {
    if (liquidEnabled && backdrop != null) {
        LiquidSlidingBarLayers(modifier, items, selectedIndex, onSelected, backdrop, tabWidthPx, panelOffset, drag, ltr, onMeasured)
    } else {
        OpaqueSlidingBarLayers(modifier, items, selectedIndex, onSelected, tabWidthPx, panelOffset, drag, ltr, onMeasured)
    }
}

@Composable
private fun LiquidSlidingBarLayers(
    modifier: Modifier,
    items: List<SlidingBarItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    backdrop: Backdrop,
    tabWidthPx: Float,
    panelOffset: Float,
    drag: DampedDragAnimation,
    ltr: Boolean,
    onMeasured: (Int) -> Unit,
) {
    val tabsBackdrop = rememberLayerBackdrop()
    val combinedBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
    val highlight = rememberInteractiveHighlight(rememberCoroutineScope()) { size, _ ->
        androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
    }
    BarFrame(modifier, items, selectedIndex, onSelected, panelOffset, onMeasured, backdrop) {
        HiddenTabs(items, tabsBackdrop, panelOffset)
        LiquidIndicator(tabWidthPx, panelOffset, drag, ltr, combinedBackdrop, highlight)
    }
}

@Composable
private fun OpaqueSlidingBarLayers(
    modifier: Modifier,
    items: List<SlidingBarItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    tabWidthPx: Float,
    panelOffset: Float,
    drag: DampedDragAnimation,
    ltr: Boolean,
    onMeasured: (Int) -> Unit,
) {
    BarFrame(modifier, items, selectedIndex, onSelected, panelOffset, onMeasured, null) {
        OpaqueIndicator(tabWidthPx, panelOffset, drag, ltr)
    }
}

@Composable
private fun BarFrame(
    modifier: Modifier,
    items: List<SlidingBarItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    panelOffset: Float,
    onMeasured: (Int) -> Unit,
    backdrop: Backdrop?,
    overlay: @Composable () -> Unit,
) {
    val colors = LocalSoftVoWiFiColors.current
    val density = LocalDensity.current
    val layout = floatingRowLayoutContract()
    val isInDark = isSystemInDarkTheme()
    val pillShape = CircleShape
    val base = Modifier
        .graphicsLayer {
            translationX = panelOffset
        }
        .dropShadow(
            shape = pillShape,
            shadow = Shadow(
                radius = 10.dp,
                color = Color.Black,
                alpha = if (isInDark) 0.2f else 0.1f,
            ),
        )
        .then(
            if (backdrop == null) Modifier.background(colors.surface, pillShape) else Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { pillShape },
                effects = {
                    vibrancy()
                    blur(with(density) { 4.dp.toPx() }, with(density) { 4.dp.toPx() })
                    lens(with(density) { 24.dp.toPx() }, with(density) { 24.dp.toPx() })
                },
                highlight = { PillSpecular },
                onDrawSurface = { drawRect(colors.surface.copy(alpha = 0.40f)) },
            ),
        )
    Box(modifier.width(IntrinsicSize.Min), contentAlignment = Alignment.CenterStart) {
        Row(
            modifier = base.onSizeChanged { onMeasured(it.width) }
                .height(layout.outerHeightDp.dp)
                .padding(layout.innerPaddingDp.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) { TabContent(items, selectedIndex, onSelected, selectable = true) }
        overlay()
    }
}
