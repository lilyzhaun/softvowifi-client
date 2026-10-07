package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.dropShadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Communication tokens follow the application's existing light/dark and primary-color source.
// This is a surface vocabulary, not a second theme or a page-private preference.
internal data class CommunicationColors(val list: Color, val grouped: Color, val field: Color,
    val timeline: Color, val accent: Color, val outgoing: Color, val metadata: Color)
internal val CommunicationColors.onAccent: Color get() = if (list.luminance() < .5f) list else Color.White

internal data class CommunicationPageControl(val label: String, val icon: ImageVector, val action: () -> Unit,
    val subtitle: String?)
internal val LocalCommunicationPageControl = staticCompositionLocalOf<MutableState<CommunicationPageControl?>?> { null }
internal val LocalCommunicationPalette = staticCompositionLocalOf<CommunicationColors?> { null }

@Composable internal fun communicationColors(): CommunicationColors {
    LocalCommunicationPalette.current?.let { return it }
    val global = LocalSoftVoWiFiColors.current
    val dark = global.canvas.luminance() < .5f
    val accent = lerp(MiuixTheme.colorScheme.primary, if (dark) Color.White else Color.Black, if (dark) .28f else .18f)
    return if (dark) CommunicationColors(global.canvas, Color(0xFF0E1013), global.surfaceRaised,
        Color(0xFF151D25), accent, Color(0xFF283E50), Color(0xFFACC0CF))
    else CommunicationColors(global.surface, Color(0xFFF2F2F7), Color(0xFFEEEEF0),
        Color(0xFFE9EEF2), accent, Color(0xFFDCECF8), Color(0xFF466376))
}

@Composable internal fun CommunicationTextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, destructive: Boolean = false, weight: FontWeight = FontWeight.Normal) {
    val colors = LocalSoftVoWiFiColors.current
    val feedback = managerActionFeedback(ManagerAction.ContextAction, enabled)
    Text(label, modifier.heightIn(min = 48.dp).managerPressScale(feedback).semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, interactionSource = remember { MutableInteractionSource() },
            indication = null, onClick = rememberManagerActionHandler(feedback, onClick))
        .padding(horizontal = 12.dp, vertical = 12.dp), fontSize = 17.sp, lineHeight = 24.sp,
        fontWeight = weight, textAlign = TextAlign.Center,
        color = if (!enabled) colors.textDisabled else if (destructive) colors.error else communicationColors().accent)
}

@Composable internal fun CommunicationNavigation(title: String, leading: @Composable () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {}, systemInset: Boolean = true,
    background: Boolean = true, subtitle: String? = null, sideInset: Dp = 64.dp) {
    val colors = LocalSoftVoWiFiColors.current
    val modifier = if (background) communicationSurfaceModifier(Modifier, RoundedCornerShape(0.dp), communicationColors().list) else Modifier
    val density = LocalDensity.current
    var leadingWidth by remember { mutableStateOf(48.dp) }
    var trailingWidth by remember { mutableStateOf(0.dp) }
    val titleWidth = with(density) { rememberTextMeasurer().measure(title,
        style = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)).size.width.toDp() }
    val controls: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.widthIn(min = 48.dp).onSizeChanged { leadingWidth = with(density) { it.width.toDp() } }) { leading() }
            Row(Modifier.onSizeChanged { trailingWidth = with(density) { it.width.toDp() } },
                verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
    val titleContent: @Composable (Modifier) -> Unit = { headingModifier ->
        Column(headingModifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, Modifier.fillMaxWidth().semantics { heading() }, color = colors.textPrimary,
                fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            subtitle?.let { Text(it, Modifier.fillMaxWidth(), color = colors.textSecondary, fontSize = 12.sp,
                lineHeight = 16.sp, textAlign = TextAlign.Center) }
        }
    }
    Column(modifier.fillMaxWidth().then(if (systemInset) Modifier.statusBarsPadding() else Modifier)) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            val safeSide = maxOf(sideInset, leadingWidth + 8.dp, trailingWidth + 8.dp)
            if (maxWidth - safeSide * 2 < titleWidth) {
                // Large type or long actions get separate rows, never overlapping identities.
                Column { titleContent(Modifier.fillMaxWidth().padding(horizontal = 16.dp)); controls() }
            } else {
                Box(Modifier.align(Alignment.Center)) { controls() }
                titleContent(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = safeSide))
            }
        }
        HorizontalDivider(color = colors.separator.copy(alpha = .6f), thickness = .5.dp)
    }
}

@Composable internal fun CommunicationInput(value: String, onChange: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, singleLine: Boolean = false,
    maxLines: Int = 5, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    leading: ImageVector? = null, background: Boolean = true, fontSize: Int = 17) {
    val colors = LocalSoftVoWiFiColors.current
    val tokens = communicationColors()
    val shape = RoundedCornerShape(if (leading != null) 16.dp else 24.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val outline by animateColorAsState(if (enabled && focused) tokens.accent.copy(alpha = .48f)
        else colors.textSecondary.copy(alpha = .12f), tween(140), label = "input-outline")
    val surface = if (background) communicationSurfaceModifier(Modifier.dropShadow(shape) {
        radius = 8.dp.toPx(); offset = androidx.compose.ui.geometry.Offset(0f, 2.dp.toPx())
        color = Color.Black; alpha = .045f
    }, shape, tokens.field).border(1.dp, outline, shape) else Modifier
    BasicTextField(value = value, onValueChange = onChange, enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).then(surface).semantics { contentDescription = placeholder },
        singleLine = singleLine, maxLines = if (singleLine) 1 else maxLines,
        textStyle = TextStyle(color = if (enabled) colors.textPrimary else colors.textDisabled,
            fontSize = fontSize.sp, lineHeight = (fontSize + 5).sp),
        cursorBrush = SolidColor(tokens.accent), keyboardOptions = keyboardOptions, interactionSource = interaction,
        decorationBox = { field ->
            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                leading?.let { Icon(it, null, Modifier.size(20.dp), tint = colors.textSecondary) }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = colors.textSecondary, fontSize = fontSize.sp, lineHeight = (fontSize + 5).sp)
                    field()
                }
            }
        })
}

@Composable internal fun CommunicationMenuRow(label: String, icon: ImageVector, onClick: () -> Unit,
    enabled: Boolean = true, destructive: Boolean = false, divider: Boolean = true) {
    val colors = LocalSoftVoWiFiColors.current
    val available = enabled && LocalAppRouteActive.current
    val interactive = available && LocalCommunicationMenuActionsEnabled.current
    val tone = if (!available) colors.textDisabled else if (destructive) colors.error else colors.textPrimary
    val feedback = managerActionFeedback(ManagerAction.ContextAction, interactive)
    val interaction = remember { MutableInteractionSource() }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).communicationContentPress(feedback, interaction)
            .clickable(enabled = interactive, role = Role.Button, interactionSource = interaction,
                indication = null, onClick = rememberManagerActionHandler(feedback, onClick))
            .padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 24.sp, color = tone)
            Icon(icon, null, Modifier.size(22.dp), tint = tone)
        }
        if (divider) HorizontalDivider(color = colors.separator, thickness = .5.dp)
    }
}

@Composable internal fun CommunicationFootnote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        fontSize = 13.sp, lineHeight = 18.sp, color = LocalSoftVoWiFiColors.current.textSecondary)
}

@Composable internal fun CommunicationFact(label: String, value: String) {
    val colors = LocalSoftVoWiFiColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        Text(value, color = colors.textPrimary, fontSize = 17.sp, lineHeight = 24.sp)
    }
}

@Composable internal fun CommunicationEmpty(title: String, summary: String, icon: ImageVector) {
    val colors = LocalSoftVoWiFiColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(44.dp), tint = colors.textSecondary)
        Text(title, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Text(summary, fontSize = 14.sp, lineHeight = 20.sp, color = colors.textSecondary, textAlign = TextAlign.Center)
    }
}

@Composable internal fun CommunicationLineRow(title: String, summary: String, selected: Boolean,
    onSelect: () -> Unit, onEdit: (() -> Unit)? = null, enabled: Boolean = true) {
    val colors = LocalSoftVoWiFiColors.current
    val tokens = communicationColors()
    val interaction = remember { MutableInteractionSource() }
    val feedback = managerActionFeedback(ManagerAction.SegmentSelection, enabled)
    Column(Modifier.background(tokens.list)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 72.dp).communicationContentPress(feedback, interaction)
                .selectable(selected, enabled = enabled, role = Role.RadioButton, interactionSource = interaction, indication = null,
                    onClick = rememberManagerActionHandler(feedback, onSelect)).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(tokens.field, RoundedCornerShape(8.dp)).clearAndSetSemantics {},
                    contentAlignment = Alignment.Center) { Icon(CommunicationSim, null, Modifier.size(22.dp), tint = tokens.accent) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
                    Text(summary, fontSize = 13.sp, lineHeight = 18.sp, color = colors.textSecondary)
                }
                if (selected) Icon(CommunicationCheck, null, Modifier.size(20.dp), tint = tokens.accent)
            }
            onEdit?.let { CommunicationTextAction("备注", it, enabled = enabled) }
        }
        HorizontalDivider(Modifier.padding(start = 64.dp), thickness = .5.dp, color = colors.separator)
    }
}
