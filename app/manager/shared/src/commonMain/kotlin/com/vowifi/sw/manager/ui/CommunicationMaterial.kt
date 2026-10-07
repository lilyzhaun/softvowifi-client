package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.ui.liquid.lens
import com.vowifi.sw.manager.ui.liquid.vibrancy
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop

internal enum class CommunicationMaterial { LIQUID, BLUR, OPAQUE }

internal fun communicationMaterial(spec: AppChromeSpec, hasBackdrop: Boolean): CommunicationMaterial = when {
    !hasBackdrop -> CommunicationMaterial.OPAQUE
    spec.communicationLiquidGlass -> CommunicationMaterial.LIQUID
    spec.effectiveBlur -> CommunicationMaterial.BLUR
    else -> CommunicationMaterial.OPAQUE
}

// Each host captures only the content behind its controls, never the controls themselves.
internal val LocalCommunicationBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
internal fun rememberCommunicationBackdrop(): LayerBackdrop? {
    val spec = LocalSoftVoWiFiChromeEnvironment.current.spec
    return rememberSoftVoWiFiBackdrop(spec.copy(effectiveLiquidGlass = spec.communicationLiquidGlass))
}

@Composable
internal fun communicationSurfaceModifier(
    modifier: Modifier,
    shape: Shape,
    tint: Color = communicationColors().list,
): Modifier {
    val spec = LocalSoftVoWiFiChromeEnvironment.current.spec
    val backdrop = LocalCommunicationBackdrop.current
    val mode = communicationMaterial(spec, backdrop != null)
    if (mode == CommunicationMaterial.OPAQUE || backdrop == null) return modifier.background(tint.copy(alpha = 1f), shape)
    val density = LocalDensity.current
    return modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (mode == CommunicationMaterial.LIQUID) vibrancy()
            blur(with(density) { 12.dp.toPx() })
            if (mode == CommunicationMaterial.LIQUID) lens(with(density) { 8.dp.toPx() }, with(density) { 12.dp.toPx() })
        },
        onDrawSurface = { drawRect(tint.copy(alpha = if (mode == CommunicationMaterial.LIQUID) 0.64f else 0.88f)) },
    )
}

@Composable
internal fun CommunicationIconAction(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = LocalSoftVoWiFiColors.current
    val interaction = remember { MutableInteractionSource() }
    val feedback = managerActionFeedback(ManagerAction.Navigate, enabled)
    Box(
        Modifier.size(48.dp).managerPressScale(feedback, interaction)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null,
                onClick = rememberManagerActionHandler(feedback, onClick))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(24.dp), tint = if (enabled) communicationColors().accent else colors.textDisabled) }
}

@Composable
internal fun CommunicationLineChip(label: String, actionLabel: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = LocalSoftVoWiFiColors.current
    val interaction = remember { MutableInteractionSource() }
    val feedback = managerActionFeedback(ManagerAction.Navigate, enabled)
    Row(
        Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).managerPressScale(feedback, interaction)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null,
                onClick = rememberManagerActionHandler(feedback, onClick))
            .semantics { contentDescription = actionLabel }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f, fill = false), fontSize = 15.sp, lineHeight = 20.sp,
            color = if (enabled) communicationColors().accent else colors.textDisabled)
        Icon(CommunicationChevronDown, null, Modifier.size(14.dp), tint = communicationColors().accent)
    }
}

@Composable
internal fun CommunicationRootHeader(title: String, actions: @Composable RowScope.() -> Unit) {
    CommunicationNavigation(title, trailing = actions, sideInset = 104.dp)
}
