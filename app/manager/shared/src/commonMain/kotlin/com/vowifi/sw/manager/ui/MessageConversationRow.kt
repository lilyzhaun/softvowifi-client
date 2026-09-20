package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun ConversationRow(
    spec: ConversationRowPresentation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0,
) {
    val colors = LocalSoftVoWiFiColors.current
    val layout = messageListLayoutContract()
    val interaction = remember { MutableInteractionSource() }
    val feedback = managerActionFeedback(ManagerAction.Navigate)
    val handler = rememberManagerActionHandler(feedback, onClick)
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = layout.rowMinHeightDp.dp)
                .managerPressScale(feedback, interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = handler)
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(layout.avatarSizeDp.dp)
                    .background(colors.surfaceRaised, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(spec.avatarText, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(spec.peerDisplay, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = if (unreadCount > 0) FontWeight.SemiBold else FontWeight.Medium)
                Text(
                    spec.preview,
                    color = if (unreadCount > 0) colors.textPrimary else colors.textSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = layout.previewMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.timeText, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp, style = TextStyle(fontFeatureSettings = "tnum"))
                    spec.statusText?.let { Text(it, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp) }
                    UnreadBadge(unreadCount)
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(start = (layout.avatarSizeDp + 12).dp), color = colors.separator)
    }
}
