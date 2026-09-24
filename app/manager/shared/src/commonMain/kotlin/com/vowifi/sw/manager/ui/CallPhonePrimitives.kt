package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*

internal data class PhoneColors(val canvas: Color, val surface: Color, val text: Color, val accent: Color, val onAccent: Color) {
    val red = Color(0xFFD94A4A)
    val secondary = if (canvas.luminance() < .5f) Color(0xFFB9BEC5) else Color(0xFF626A73)
}

@Composable fun PhoneActionFeedback(settings: AppearanceSettings, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSoftVoWiFiChromeEnvironment provides SoftVoWiFiChromeEnvironment(
        appChromeSpec(settings, blurSupported = false), backdrop = null), content = content)
}
@Composable internal fun phoneColors(): PhoneColors = if (LocalSoftVoWiFiColors.current.canvas.luminance() < .5f)
    PhoneColors(Color(0xFF121417), Color(0xFF1C1F24), Color(0xFFF4F4F4), Color(0xFF91B8B3), Color(0xFF121417))
else PhoneColors(Color(0xFFF4F6F8), Color.White, Color(0xFF161616), Color(0xFF376F6B), Color.White)

@Composable internal fun PhoneText(text: String, modifier: Modifier = Modifier, size: Int = 16, secondary: Boolean = false) {
    val palette = phoneColors()
    BasicText(text, modifier, style = TextStyle(color = if (secondary) palette.secondary else palette.text,
        fontSize = size.sp, lineHeight = (if (size == 14) 20 else size + 8).sp, fontFamily = FontFamily.SansSerif,
        fontWeight = if (size >= 24) FontWeight.Medium else FontWeight.Normal,
        fontFeatureSettings = "tnum", textAlign = TextAlign.Center), softWrap = true)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun PhoneButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null,
    selected: Boolean = false, destructive: Boolean = false, enabled: Boolean = true,
    busy: Boolean = false, wide: Boolean = false, onLongClick: (() -> Unit)? = null,
    compact: Boolean = false, showLabel: Boolean = true,
    longClickLabel: String? = null,
    toggle: Boolean = false, prominent: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    val palette = phoneColors()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val feedback = if (toggle) toggleActionFeedback(!selected, enabled && !busy)
        else managerActionFeedback(ManagerAction.ContextAction, enabled && !busy)
    val click = rememberManagerActionHandler(feedback, onClick)
    val longClick = onLongClick?.let { rememberManagerActionHandler(feedback, it) }
    val opacity by animateFloatAsState(if (enabled || busy) 1f else .55f, tween(160), label = "phoneAvailability")
    val shape = if (wide) RoundedCornerShape(40.dp) else CircleShape
    val surface by animateColorAsState(when { destructive -> palette.red; selected -> palette.accent; else -> palette.surface }, tween(160))
    val foreground by animateColorAsState(when { destructive -> Color.White; selected -> palette.onAccent; else -> palette.text }, tween(160))
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val target = if (compact) 48.dp else if (prominent || destructive) 80.dp else 72.dp
        Box(Modifier.then(if (wide) Modifier.fillMaxWidth().heightIn(min = 64.dp) else Modifier.sizeIn(minWidth = target, minHeight = target))
            .managerPressScale(feedback, interaction).graphicsLayer { alpha = opacity }
            .clip(shape).background(if (pressed && feedback.accepted) foreground.copy(alpha = .12f).compositeOver(surface) else surface)
            .combinedClickable(enabled = enabled && !busy, role = Role.Button, interactionSource = interaction,
                indication = null, onClick = click, hapticFeedbackEnabled = false,
                onLongClick = longClick, onLongClickLabel = longClickLabel)
            .semantics {
                contentDescription = label
                if (toggle) { this.selected = selected; stateDescription = if (selected) "已开启" else "已关闭" }
                if (busy) stateDescription = "处理中"
            }
            .padding(if (wide) 12.dp else 6.dp), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator(Modifier.size(28.dp))
                content != null -> content()
                wide -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    icon?.let { Icon(it, null, tint = foreground, modifier = Modifier.size(24.dp)) }
                    BasicText(label, style = TextStyle(color = foreground, fontSize = 18.sp, lineHeight = 26.sp,
                        fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center))
                }
                icon != null -> Icon(icon, null, tint = foreground, modifier = Modifier.size(30.dp))
            }
        }
        if (!wide && content == null && showLabel) PhoneText(label, size = 14)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun PhoneNavigation(label: String, onClick: () -> Unit, icon: ImageVector? = null, showText: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val feedback = managerActionFeedback(ManagerAction.Navigate)
    val click = rememberManagerActionHandler(feedback, onClick)
    Box(Modifier.sizeIn(minHeight = 48.dp, minWidth = 48.dp).managerPressScale(feedback, interaction)
        .background(if (pressed) phoneColors().text.copy(alpha = .12f) else Color.Transparent, CircleShape)
        .combinedClickable(role = Role.Button, interactionSource = interaction, indication = null, onClick = click)
        .semantics { contentDescription = label }
        .padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { Icon(it, null, tint = phoneColors().text, modifier = Modifier.size(24.dp)) }
            if (showText) PhoneText(label)
        }
    }
}

internal fun displayCallNumber(raw: String): String = if (raw.startsWith("+1") && raw.length == 12 && raw.drop(1).all(Char::isDigit))
    "+1 ${raw.substring(2, 5)} ${raw.substring(5, 8)} ${raw.substring(8)}" else raw

@Composable fun OngoingCallAffordance(label: String = "返回当前通话", onClick: () -> Unit) {
    PhoneButton(label, onClick, Modifier.widthIn(max = 320.dp).padding(8.dp), selected = true, wide = true, icon = CallPhoneIcons.Phone)
}
