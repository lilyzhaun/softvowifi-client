package com.vowifi.sw.manager.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Independent public-API adapter for compose-miuix-ui/miuix 0.9.3 (Apache-2.0).
// Reference c36fab72391801d1e3ea5a00f966bf16bac28d4c: NavigationBar.kt / Badge.kt.
// https://github.com/compose-miuix-ui/miuix/tree/c36fab72391801d1e3ea5a00f966bf16bac28d4c/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/basic
// Public defaults/leaf APIs only; private badgeBounds is not called or copied.
@Composable internal fun rememberBottomTint(target: Color): ColorProducer {
    val color = animateColorAsState(target, tween(MotionTokens.DurFast, easing = MotionTokens.EaseStandard), label = "bottom-tint")
    return remember(color) { ColorProducer { color.value } }
}

@Composable internal fun RowScope.BottomTintNavigationItem(
    selected: Boolean, onClick: () -> Unit, icon: ImageVector, label: String,
    badge: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    val active = rememberUpdatedState(LocalAppRouteActive.current)
    val click = rememberUpdatedState(onClick)
    val base = MiuixTheme.colorScheme.onSurfaceContainer
    val color = rememberBottomTint(if (selected) base else base.copy(alpha = NavigationBarDefaults.UnselectedAlpha))
    val tint = ColorProducer {
        if (pressed.value) base.copy(alpha = if (selected) NavigationBarDefaults.SelectedPressedAlpha
            else NavigationBarDefaults.UnselectedPressedAlpha) else color()
    }
    BoxWithConstraints(Modifier.height(NavigationBarDefaults.ItemHeight).weight(1f)
        .managerKeyboardFocusRing(interaction, active.value, RectangleShape)
        .managerPressInputGate(active.value).selectable(selected = selected, enabled = active.value,
            role = Role.Tab, interactionSource = interaction, indication = null,
            onClick = { if (active.value) click.value() })) {
        val itemWidth = maxWidth
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top) {
            BadgedBox(modifier = Modifier.padding(top = NavigationBarDefaults.IconTopPadding), badge = {
                if (badge != null) Box(Modifier.layout { measurable, constraints ->
                    val p = measurable.measure(constraints)
                    val content = p.width > BadgeDefaults.Size.roundToPx()
                    // Pinned Badge.kt placement values, used only to bound the badge slot.
                    val tail = (if (content) 12.dp else 6.dp).roundToPx()
                    val overlap = (if (content) 14.dp else 6.dp).roundToPx()
                    val iconPx = NavigationBarDefaults.IconSize.roundToPx()
                    val right = Alignment.CenterHorizontally.align(iconPx, itemWidth.roundToPx(), layoutDirection) + iconPx
                    val top = NavigationBarDefaults.IconTopPadding.roundToPx() - p.height + overlap
                    layout(p.width, p.height) {
                        p.placeRelative(minOf(0, itemWidth.roundToPx() - (right - tail) - p.width), maxOf(0, -top))
                    }
                }) { badge() }
            }) {
                Icon(painter = rememberVectorPainter(icon), tint = tint, contentDescription = null,
                    modifier = Modifier.size(NavigationBarDefaults.IconSize))
            }
            Text(text = label, color = tint, modifier = Modifier.padding(bottom = NavigationBarDefaults.BottomPadding),
                textAlign = TextAlign.Center, fontSize = NavigationBarDefaults.LabelFontSize,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}
