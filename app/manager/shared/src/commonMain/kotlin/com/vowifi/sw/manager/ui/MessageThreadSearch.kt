package com.vowifi.sw.manager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.foundation.layout.heightIn

internal fun filterLoadedThread(items: List<MessageThreadItem>, query: String): List<MessageThreadItem> {
    if (query.isEmpty()) return items
    return buildList {
        var date: MessageThreadItem.DateSeparator? = null
        var emittedDate: String? = null
        for (item in items) {
            if (item is MessageThreadItem.DateSeparator) { date = item; continue }
            val body = when (item) {
                is MessageThreadItem.IncomingBubble -> item.body
                is MessageThreadItem.OutgoingBubble -> item.body
                is MessageThreadItem.DateSeparator -> continue
            }
            if (!body.contains(query, ignoreCase = true)) continue
            if (date?.date?.key != emittedDate) { date?.let(::add); emittedDate = date?.date?.key }
            add(item)
        }
    }
}

@Composable
internal fun MessageThreadSearch(open: Boolean, query: String, matches: Int, onToggle: () -> Unit, onQuery: (String) -> Unit,
    contextLabel: String? = null) {
    val colors = LocalSoftVoWiFiColors.current
    val host = LocalCommunicationPageControl.current
    val currentToggle by rememberUpdatedState(onToggle)
    val active = LocalAppRouteActive.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val toggle = { if (active) { if (open) { focus.clearFocus(); keyboard?.hide() }; currentToggle() } }
    val latestToggle by rememberUpdatedState(toggle)
    if (LocalNavigationEventDispatcherOwner.current != null) {
        NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = active && open,
            onBackCompleted = { latestToggle() })
    }
    val label = if (open) "关闭会话搜索" else "搜索会话"
    DisposableEffect(host, label, contextLabel) {
        host?.value = CommunicationPageControl(label, CommunicationSearch, { latestToggle() }, contextLabel)
        onDispose { host?.value = null }
    }
    Column {
        if (host == null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(contextLabel.orEmpty(), modifier = Modifier.weight(1f), color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            CommunicationTextAction(label, toggle, enabled = active)
        }
        AnimatedVisibility(open, enter = fadeIn(tween(MotionTokens.DurBase, easing = MotionTokens.EaseStandard)) +
            expandVertically(tween(MotionTokens.DurBase, easing = MotionTokens.EaseStandard)),
            exit = fadeOut(tween(MotionTokens.DurFast, easing = MotionTokens.EaseExit)) +
                shrinkVertically(tween(MotionTokens.DurFast, easing = MotionTokens.EaseExit))) {
            Column {
                CommunicationInput(query, { onQuery(it.take(128)) }, "搜索已加载消息",
                    Modifier.fillMaxWidth().clearFocusOnInactiveRouteAndImeHide(), enabled = active && open, singleLine = true, leading = CommunicationSearch)
                Text("仅搜索已加载消息 · $matches 条", color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}
