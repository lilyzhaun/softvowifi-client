package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.MessageUnreadSummary
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info

internal val SoftVoWiFiColors.onCommunicationAccent
    get() = if (canvas.luminance() < 0.1f) canvas else surface

internal val LocalMessageUnreadSummary = compositionLocalOf<MessageUnreadSummary> { MessageUnreadSummary.Loading }
internal val LocalThreadDrafts = compositionLocalOf<MutableState<Map<String, String>>?> { null }

internal fun unreadCountLabel(count: Int): String = if (count > 99) "99+" else count.toString()

@Composable
internal fun UnreadBadge(count: Int, modifier: Modifier = Modifier, indicatorOnly: Boolean = false, color: Color? = null, foreground: Color? = null) {
    if (count == 0) return
    UnreadBadgeContent(unreadCountLabel(count), "$count 条未读短信", modifier, indicatorOnly, color, foreground)
}

@Composable
internal fun MessageSummaryBadge(summary: MessageUnreadSummary, indicatorOnly: Boolean = false) {
    val badge = messageUnreadBadge(summary) ?: return
    val description = when (summary) {
        MessageUnreadSummary.Loading -> "正在读取全部短信未读数"
        is MessageUnreadSummary.Ready -> "${summary.total} 条未读短信"
        is MessageUnreadSummary.Unavailable -> summary.lastKnownTotal?.let {
            "上次确认 $it 条未读短信，当前数量无法确认"
        } ?: "无法确认短信未读数"
    }
    UnreadBadgeContent(badge.label, description, Modifier, indicatorOnly && badge.current)
}

@Composable
private fun UnreadBadgeContent(label: String, description: String, modifier: Modifier, indicatorOnly: Boolean, color: Color? = null, foreground: Color? = null) {
    val colors = LocalSoftVoWiFiColors.current
    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        shape = CircleShape,
        color = color ?: colors.interactive,
    ) {
        if (indicatorOnly) Box(Modifier.size(8.dp)) else Text(
            label,
            modifier = Modifier.heightIn(min = 20.dp).padding(horizontal = 6.dp, vertical = 2.dp),
            color = foreground ?: if (color == null) colors.onCommunicationAccent else Color.White,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun CommunicationNotice(title: String, summary: String? = null, modifier: Modifier = Modifier) {
    val colors = LocalSoftVoWiFiColors.current
    Row(
         modifier.padding(vertical = 8.dp),
         horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
         Icon(MiuixIcons.Info, null, Modifier.size(16.dp), tint = colors.textSecondary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
             Text(title, fontSize = 13.sp, lineHeight = 18.sp, color = colors.textSecondary)
             summary?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = colors.textSecondary) }
        }
    }
}
