package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.CallHistoryHandle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Close

@Composable internal fun CallHistoryDetailScreen(handle: CallHistoryHandle, history: CallHistoryState,
    onDelete: (CallHistoryState.Ready, Set<CallHistoryRecordId>) -> Unit) = PhoneStyle {
    val palette = phoneColors()
    val ready = history as? CallHistoryState.Ready
    val group = ready?.let(::callHistoryGroups)?.firstOrNull { it.handle == handle }
    var deletion by remember(handle) { mutableStateOf<CallHistoryRecordId?>(null) }
    val active = LocalAppRouteActive.current
    LaunchedEffect(history, active) { deletion = null }
    LazyColumn(Modifier.fillMaxSize().background(palette.canvas).phoneOverscroll(), overscrollEffect = null,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = LocalSubpageTopContentInset.current + 24.dp,
            bottom = LocalSubpageBottomContentInset.current + 24.dp)) {
        if (group == null) item {
            val panel = when (history) {
                CallHistoryState.Loading -> StatePanelSpec(MiuixIcons.Refresh, StatePanelTone.INTERACTIVE,
                    "正在读取通话记录", "完成后将显示此号码的通话详情。", loading = true)
                is CallHistoryState.Failure -> StatePanelSpec(MiuixIcons.Close, StatePanelTone.ERROR,
                    "无法读取通话记录", history.message)
                else -> StatePanelSpec(CallPhoneIcons.Information, StatePanelTone.NEUTRAL,
                    "通话记录不可用", "记录已变化或线路已切换，请返回通话列表。")
            }
            CommunicationListState(panel)
        } else {
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(64.dp).background(palette.tonal, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(CallPhoneIcons.Phone, null, Modifier.size(30.dp), tint = palette.accent)
                    }
                    PhoneText(group.peer?.dialNumber()?.let { displayCallNumber(privateUiPhoneDisplay(it)) } ?: "未知号码",
                        Modifier.semantics { heading() }, size = 24)
                    PhoneText("${group.records.size} 次通话 · 当前线路本机记录", size = 14, secondary = true)
                }
            }
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            group.records.groupBy { phoneHistoryDateLabel(it.startedAtMs, now) }.forEach { (date, records) ->
                item { Text(date, Modifier.padding(top = 8.dp, bottom = 12.dp).semantics { heading() },
                    fontSize = 16.sp, lineHeight = 24.sp, color = palette.secondary) }
                items(records, key = { it.recordId?.opaque ?: "${it.startedAtMs}:${it.direction}" }) { record ->
                    CallHistoryOccurrence(record) { deletion = it }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
    deletion?.takeIf { active }?.let { id -> if (ready != null) CallHistoryDeleteConfirmation(ready, setOf(id), true, { deletion = null }, onDelete) }
}
