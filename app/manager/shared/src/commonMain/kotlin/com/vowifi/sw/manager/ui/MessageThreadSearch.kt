package com.vowifi.sw.manager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
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
internal fun MessageThreadSearch(open: Boolean, query: String, matches: Int, onToggle: () -> Unit, onQuery: (String) -> Unit) {
    val colors = LocalSoftVoWiFiColors.current
    val duration = smsInnerMotionMillis()
    Column {
        ManagerTextAction(if (open) "关闭会话搜索" else "搜索会话", ManagerAction.Navigate, onToggle)
        AnimatedVisibility(open, enter = fadeIn(tween(duration)) + expandVertically(tween(duration)),
            exit = fadeOut(tween(duration)) + shrinkVertically(tween(duration))) {
            Column {
                TextField(value = query, onValueChange = { onQuery(it.take(128)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clearFocusOnInactiveRouteAndImeHide(),
                    colors = TextFieldDefaults.textFieldColors(backgroundColor = colors.surface),
                    label = "搜索已加载消息", useLabelAsPlaceholder = true, singleLine = true, cornerRadius = 24.dp)
                Text("仅搜索已加载消息 · $matches 条", color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}
