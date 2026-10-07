package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon

@Composable
internal fun ConversationRow(
    spec: ConversationRowPresentation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0,
    onLongClick: (() -> Unit)? = null,
    previewInset: Dp = 0.dp,
    contextOpen: Boolean = false,
) {
    val colors = LocalSoftVoWiFiColors.current
    val layout = messageListLayoutContract()
    val interaction = remember { MutableInteractionSource() }
    val active = LocalAppRouteActive.current
    val feedback = managerActionFeedback(ManagerAction.Navigate, active)
    val handler = rememberManagerActionHandler(feedback, onClick)
    val longHandler = rememberManagerActionHandler(managerActionFeedback(ManagerAction.ContextAction, active), { onLongClick?.invoke() })
    val titleWeight = if (unreadCount > 0) FontWeight.SemiBold else FontWeight.Medium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val identityWidth = with(density) { measurer.measure(spec.peerDisplay,
        style = TextStyle(fontSize = 17.sp, fontWeight = titleWeight)).size.width.toDp() }
    val timeWidth = with(density) { measurer.measure(spec.timeText,
        style = TextStyle(fontSize = 12.sp)).size.width.toDp() }
    val stacked = maxWidth < 320.dp || density.fontScale > 1.3f ||
        identityWidth > maxWidth - layout.avatarSizeDp.dp - 24.dp - maxOf(timeWidth, 32.dp)
    // Decide stacking from the source width, before the animated preview inset (as on Calls).
    Column(Modifier.padding(horizontal = previewInset)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = layout.rowMinHeightDp.dp)
                .communicationContentPress(if (contextOpen) ManagerActionFeedback.None else feedback, interaction)
                .combinedClickable(enabled = active, interactionSource = interaction, indication = null, onClick = handler,
                    hapticFeedbackEnabled = false, onLongClickLabel = "会话操作", onLongClick = if (onLongClick != null) longHandler else null)
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(layout.avatarSizeDp.dp)
                    .background(communicationColors().outgoing, CircleShape).clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                if (spec.peerDisplay.any(Char::isDigit) && spec.peerDisplay.all { it.isDigit() || it == '+' || it == ' ' }) {
                    Icon(CommunicationPerson, null, Modifier.size(24.dp), tint = colors.textSecondary)
                } else Text(spec.avatarText, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.peerDisplay, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 23.sp, fontWeight = titleWeight)
                    if (!stacked) Text(spec.timeText, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp,
                        style = TextStyle(fontFeatureSettings = "tnum"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            spec.preview,
                            color = if (unreadCount > 0) colors.textPrimary else colors.textSecondary,
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                            maxLines = layout.previewMaxLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                        spec.statusText?.let { Text(it, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp) }
                    }
                    if (!stacked) UnreadBadge(unreadCount, color = communicationColors().accent, foreground = communicationColors().onAccent)
                }
                if (stacked) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.timeText, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp,
                        style = TextStyle(fontFeatureSettings = "tnum"))
                    UnreadBadge(unreadCount, color = communicationColors().accent, foreground = communicationColors().onAccent)
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(start = (layout.avatarSizeDp + 12).dp), thickness = .5.dp, color = colors.separator)
    }
    }
}
