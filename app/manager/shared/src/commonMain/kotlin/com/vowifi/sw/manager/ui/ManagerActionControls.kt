package com.vowifi.sw.manager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ManagerIconAction(
    icon: ImageVector,
    label: String,
    action: ManagerAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconRotation: State<Float>? = null,
) {
    val feedback = managerActionFeedback(action, enabled && LocalAppRouteActive.current)
    val actionable = feedback.accepted
    val handler = rememberManagerActionHandler(feedback, onClick)
    val interaction = remember { MutableInteractionSource() }
    val motion = rememberManagerPressMotion(interaction, actionable, ManagerPressStyle.Icon)
    val colors = LocalSoftVoWiFiColors.current
    Box(
        modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .managerPressInputGate(actionable)
            .clickable(enabled = actionable, role = Role.Button, interactionSource = interaction, indication = null, onClick = handler)
            .semantics { contentDescription = label }
            .managerKeyboardFocusRing(interaction, actionable, CircleShape, colors.interactive)
            .drawBehind { drawCircle(colors.textPrimary.copy(alpha = motion.tintAlpha.value), radius = 20.dp.toPx()) },
        contentAlignment = Alignment.Center,
    ) {
        val rotation = if (iconRotation == null) Modifier else Modifier.graphicsLayer { rotationZ = iconRotation.value }
        Icon(icon, null, modifier = Modifier.size(24.dp).managerPressDrawing(motion).then(rotation), tint = colors.interactive)
    }
}

@Composable
internal fun ManagerTextAction(
    label: String,
    action: ManagerAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    primary: Boolean = false,
    busyLabel: String = label,
    labelCandidates: List<String> = listOf(label, busyLabel),
) {
    val routeActive = LocalAppRouteActive.current
    val businessFeedback = managerActionFeedback(action, enabled && !busy)
    val actionable = businessFeedback.accepted && routeActive
    val feedback = if (actionable) businessFeedback else ManagerActionFeedback.None
    val handler = rememberManagerActionHandler(feedback, onClick)
    val interaction = remember { MutableInteractionSource() }
    val colors = LocalSoftVoWiFiColors.current
    val buttonColors = if (primary) ButtonDefaults.buttonColors(color = colors.interactive, contentColor = colors.surface)
        else ButtonDefaults.buttonColors()
    val indication = rememberManagerPressIndication(ManagerPressStyle.Solid, actionable)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MiuixTheme.textStyles.main.merge(fontSize = 14.sp, lineHeight = 20.sp)
    val candidates = labelCandidates + label + busyLabel
    val width = remember(measurer, density, style, candidates, busyLabel) {
        fun textWidth(value: String) = measurer.measure(value, style, softWrap = false, maxLines = 1).size.width
        val plain = candidates.maxOf(::textWidth)
        val pending = textWidth(busyLabel) + with(density) { 20.dp.roundToPx() }
        with(density) { maxOf(plain, pending).toDp() } + 24.dp
    }
    Button(
        onClick = handler, enabled = actionable, minWidth = maxOf(ButtonDefaults.MinWidth, width), minHeight = 48.dp,
        colors = buttonColors.preservingPressBusinessColors(businessFeedback.accepted, routeActive),
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        interactionSource = interaction, indication = null,
        modifier = modifier.semantics {
            if (busy) {
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                stateDescription = if (busyLabel == label) "正在处理" else busyLabel
            }
        }.managerKeyboardFocusRing(interaction, actionable, color = if (primary) colors.surface else colors.interactive)
            .managerPressInputGate(actionable).indication(interaction, indication),
    ) {
        ManagerActionStateContent(if (busy) busyLabel else label, busy)
    }
}
