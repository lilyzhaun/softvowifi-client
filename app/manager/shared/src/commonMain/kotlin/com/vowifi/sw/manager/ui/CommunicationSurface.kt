package com.vowifi.sw.manager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
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
    val badge = if (count == 0) null else MessageUnreadBadge(unreadCountLabel(count), count.toLong(), true)
    CommunicationUnreadBadge(badge, if (badge == null) null else "$count 条未读短信", modifier, indicatorOnly, color, foreground)
}

@Composable
internal fun MessageSummaryBadge(summary: MessageUnreadSummary, indicatorOnly: Boolean = false) {
    val badge = messageUnreadBadge(summary)
    val description = when (summary) {
        MessageUnreadSummary.Loading -> "正在读取全部短信未读数"
        is MessageUnreadSummary.Ready -> "${summary.total} 条未读短信"
        is MessageUnreadSummary.Unavailable -> summary.lastKnownTotal?.let {
            "上次确认 $it 条未读短信，当前数量无法确认"
        } ?: "无法确认短信未读数"
    }
    CommunicationUnreadBadge(badge, description, indicatorOnly = indicatorOnly)
}

@Composable
private fun CommunicationUnreadBadge(badge: MessageUnreadBadge?, description: String?, modifier: Modifier = Modifier,
    indicatorOnly: Boolean = false, color: Color? = null, foreground: Color? = null) {
    val colors = LocalSoftVoWiFiColors.current
    key(colors, LocalDensity.current) {
        var retained by remember { mutableStateOf(badge) }
        SideEffect { if (badge != null) retained = badge }
        val visibility = remember { MutableTransitionState(badge != null) }
        visibility.targetState = badge != null
        AnimatedVisibility(visibility, modifier = modifier,
            enter = fadeIn(MotionTokens.SpringSpatialFast) + scaleIn(MotionTokens.SpringSpatialFast, initialScale = MotionTokens.ScalePopFrom),
            exit = fadeOut(tween(MotionTokens.DurFast, easing = MotionTokens.EaseStandard)) +
                scaleOut(tween(MotionTokens.DurFast, easing = MotionTokens.EaseStandard), targetScale = MotionTokens.ScalePopFrom)) {
            val shown = badge ?: retained
            if (shown != null) Surface(
                modifier = Modifier.clearAndSetSemantics { if (badge != null && description != null) contentDescription = description },
                shape = CircleShape, color = color ?: colors.interactive,
            ) {
                if (indicatorOnly && shown.current) Box(Modifier.size(8.dp)) else {
                    val ink = foreground ?: if (color == null) colors.onCommunicationAccent else Color.White
                    Box(Modifier.heightIn(min = 20.dp).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        ManagerStateText(shown.label, { ink }, 12.sp, 16.sp, FontWeight.SemiBold,
                            numericRoll = shown.current)
                    }
                }
            }
        }
    }
}

internal data class CommunicationNoticeSpec(val title: String, val summary: String? = null)

/** Keep the lazy owner even at null; custom content preserves existing notice/action styling. */
internal fun LazyListScope.communicationNoticeItem(key: String, notice: CommunicationNoticeSpec?, onRetry: (() -> Unit)? = null,
    content: (@Composable (CommunicationNoticeSpec, Boolean) -> Unit)? = null) {
    item(key = key) { CommunicationNoticeSlot(notice, onRetry, content) }
}

@Composable
private fun CommunicationNoticeSlot(notice: CommunicationNoticeSpec?, onRetry: (() -> Unit)?,
    content: (@Composable (CommunicationNoticeSpec, Boolean) -> Unit)?) {
    var retained by remember { mutableStateOf(notice) }
    SideEffect { if (notice != null) retained = notice }
    val active = LocalAppRouteActive.current
    val interactive = active && notice != null
    val spatial = MotionTokens.SpringSpatial
    AnimatedVisibility(notice != null,
        modifier = if (interactive) Modifier else Modifier.clearAndSetSemantics { },
        enter = expandVertically(spring<IntSize>(spatial.dampingRatio, spatial.stiffness), expandFrom = Alignment.Top) +
            fadeIn(tween(MotionTokens.DurBase, easing = MotionTokens.EaseStandard)),
        exit = shrinkVertically(spring<IntSize>(spatial.dampingRatio, spatial.stiffness), shrinkTowards = Alignment.Top) +
            fadeOut(tween(MotionTokens.DurFast, easing = MotionTokens.EaseStandard))) {
        val shown = notice ?: retained
        if (shown != null) {
            if (content != null) content(shown, interactive) else Column {
                CommunicationNotice(shown.title, shown.summary,
                    modifier = if (interactive) Modifier.semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                        stateDescription = shown.title
                    } else Modifier)
                if (onRetry != null) {
                    if (interactive) CommunicationTextAction("重试", onRetry)
                    else Box(Modifier.heightIn(min = 48.dp).padding(12.dp), contentAlignment = Alignment.Center) {
                        Text("重试", color = communicationColors().accent, fontSize = 17.sp, lineHeight = 24.sp)
                    }
                }
            }
        }
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
