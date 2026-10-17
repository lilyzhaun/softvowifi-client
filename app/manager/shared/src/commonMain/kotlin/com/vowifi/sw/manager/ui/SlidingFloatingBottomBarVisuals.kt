// SPDX-License-Identifier: GPL-3.0-only
// Closely adapted from KernelSU FloatingBottomBar.kt @ befbcd388bb6c10befd3645311ad08e556d362ac.
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.ui.liquid.InnerShadow
import com.vowifi.sw.manager.ui.liquid.InteractiveHighlight
import com.vowifi.sw.manager.ui.liquid.innerShadow
import com.vowifi.sw.manager.ui.liquid.lens
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource

@Composable
internal fun LiquidIndicator(
    tabWidthPx: Float,
    panelOffset: Float,
    drag: DampedDragAnimation,
    ltr: Boolean,
    backdrop: Backdrop,
    highlight: InteractiveHighlight,
    position: () -> Float = { drag.value },
) {
    if (tabWidthPx <= 0f) return
    val density = LocalDensity.current
    val colors = LocalSoftVoWiFiColors.current
    val accent = MiuixTheme.colorScheme.primary
    val layout = floatingRowLayoutContract()
    Box(
        Modifier.padding(horizontal = layout.innerPaddingDp.dp)
            .graphicsLayer {
                translationX = bubbleTranslationPx(position(), tabWidthPx, !ltr) + panelOffset
            }
            .then(highlight.gestureModifier)
            .then(drag.modifier)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { androidx.compose.foundation.shape.CircleShape },
                effects = {
                    val progress = drag.pressProgress
                    lens(
                        refractionHeight = with(density) { 10.dp.toPx() } * progress,
                        refractionAmount = with(density) { 14.dp.toPx() } * progress,
                        depthEffect = true,
                        chromaticAberration = 0.5f,
                    )
                },
                highlight = { PillSpecular.copy(alpha = drag.pressProgress) },
                layerBlock = {
                    scaleX = drag.scaleX
                    scaleY = drag.scaleY
                    val velocity = drag.velocity / 10f
                    scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                    scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                },
                onDrawSurface = {
                    val progress = drag.pressProgress
                    drawRect(colors.textPrimary.copy(alpha = 0.10f * (1f - progress)))
                    drawRect(accent.copy(alpha = 0.10f * progress))
                },
            )
            .innerShadow(androidx.compose.foundation.shape.CircleShape) {
                InnerShadow(8.dp * drag.pressProgress, color = Color.Black.copy(alpha = 0.15f), alpha = drag.pressProgress)
            }
            .then(highlight.modifier)
            .height(layout.indicatorHeightDp.dp)
            .width(with(density) { tabWidthPx.toDp() }),
    )
}

@Composable
internal fun OpaqueIndicator(tabWidthPx: Float, panelOffset: Float, drag: DampedDragAnimation, ltr: Boolean,
    position: () -> Float = { drag.value }) {
    if (tabWidthPx <= 0f) return
    val density = LocalDensity.current
    val accent = MiuixTheme.colorScheme.primary
    val layout = floatingRowLayoutContract()
    Box(
        Modifier.padding(horizontal = layout.innerPaddingDp.dp)
            .graphicsLayer {
                translationX = bubbleTranslationPx(position(), tabWidthPx, !ltr) + panelOffset
                scaleX = drag.scaleX
                scaleY = drag.scaleY
            }
            .then(drag.modifier)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(accent.copy(alpha = 0.15f), androidx.compose.foundation.shape.CircleShape)
            .height(layout.indicatorHeightDp.dp)
            .width(with(density) { tabWidthPx.toDp() }),
    )
}

@Composable
internal fun androidx.compose.foundation.layout.RowScope.TabContent(
    items: List<SlidingBarItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    selectable: Boolean,
) {
    val colors = LocalSoftVoWiFiColors.current
    val layout = floatingRowLayoutContract()
    val focusIndication = rememberManagerKeyboardFocusIndication(LocalAppRouteActive.current)
    items.forEachIndexed { index, item ->
        val tint = rememberBottomTint(if (index == selectedIndex) MiuixTheme.colorScheme.primary else colors.textPrimary)
        val tab = Modifier.defaultMinSize(layout.tabMinimumWidthDp.dp, layout.tabMinimumHeightDp.dp).fillMaxHeight().weight(1f)
        Column(
            modifier = if (selectable) tab.selectable(
                selected = index == selectedIndex,
                interactionSource = null,
                indication = focusIndication,
                role = Role.Tab,
                onClick = { onSelected(index) },
            ) else tab,
            verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            top.yukonga.miuix.kmp.basic.BadgedBox(badge = {
                if (index == AppDestination.MESSAGES.ordinal) MessageSummaryBadge(LocalMessageUnreadSummary.current, indicatorOnly = LocalDensity.current.fontScale > 1.3f)
            }) {
                Icon(painter = rememberVectorPainter(item.icon), contentDescription = null, tint = tint)
            }
            Text(item.label, color = tint, fontSize = layout.labelSizeSp.sp, lineHeight = layout.labelLineHeightSp.sp, fontWeight = FontWeight.Normal)
        }
    }
}

internal val PillSpecular = Highlight(
    width = 1.dp,
    alpha = 0.75f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.dp,
        primaryLight = LightSource(LightPosition(0.5f, -0.3f, -0.05f), Color.White, 1f),
        secondaryLight = LightSource(LightPosition(0.5f, 0.8f, -0.5f), Color.White, 0.4f),
        dualPeak = true,
    ),
)
