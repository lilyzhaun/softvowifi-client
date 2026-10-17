package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
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
    onDelete: (CallHistoryState.Ready, Set<CallHistoryRecordId>) -> Unit) =
    CallHistoryDetailScreen(handle, history, onDelete, null, null)

@Composable internal fun CallHistoryDetailScreen(handle: CallHistoryHandle, history: CallHistoryState,
    onDelete: (CallHistoryState.Ready, Set<CallHistoryRecordId>) -> Unit,
    historySnapshot: CallHistoryViewSnapshot?,
    onDeleteAndAwait: (suspend (CallHistoryState.Ready, Set<CallHistoryRecordId>) -> CallHistoryDeleteResult)?) = PhoneStyle {
    val palette = phoneColors()
    val confirmedHistory = historySnapshot?.view ?: history
    val ready = confirmedHistory as? CallHistoryState.Ready
    val group = ready?.let(::callHistoryGroups)?.firstOrNull { it.handle == handle }
    val active = LocalAppRouteActive.current
    val selection = handle to (historySnapshot?.change?.scope ?: ready?.owner)
    var deletion by remember(selection, active) { mutableStateOf<CallHistoryRecordId?>(null) }
    val deletionUi = rememberCallsHistoryDeleteUi(selection, active, onDeleteAndAwait, onDelete)
    val rows = rememberCallsHistoryRows(group?.records.orEmpty(), historySnapshot?.change, selection, active,
        key = { it.recordId?.opaque ?: "${it.startedAtMs}:${it.direction}" }, ids = { setOfNotNull(it.recordId) },
        pending = historySnapshot?.deletePending.orEmpty())
    var retainedGroup by remember(selection, active) { mutableStateOf(group) }
    SideEffect { if (group != null) retainedGroup = group }
    val shownGroup = group ?: retainedGroup?.takeIf { rows.rows.isNotEmpty() }
    LaunchedEffect(confirmedHistory, active) { deletion = null }
    LazyColumn(Modifier.fillMaxSize().background(palette.canvas).phoneOverscroll()
        .callsHistoryReplacementFade(historySnapshot?.change, selection), overscrollEffect = null,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = LocalSubpageTopContentInset.current + 24.dp,
            bottom = LocalSubpageBottomContentInset.current + 24.dp)) {
        if (shownGroup == null) item {
            val panel = when (confirmedHistory) {
                CallHistoryState.Loading -> StatePanelSpec(MiuixIcons.Refresh, StatePanelTone.INTERACTIVE,
                    "正在读取通话记录", "完成后将显示此号码的通话详情。", loading = true)
                is CallHistoryState.Failure -> StatePanelSpec(MiuixIcons.Close, StatePanelTone.ERROR,
                    "无法读取通话记录", confirmedHistory.message)
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
                    PhoneText(shownGroup.peer?.dialNumber()?.let { displayCallNumber(privateUiPhoneDisplay(it)) } ?: "未知号码",
                        Modifier.semantics { heading() }, size = 24)
                    PhoneText("${shownGroup.records.size} 次通话 · 当前线路本机记录", size = 14, secondary = true)
                }
            }
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            rows.rows.groupBy { phoneHistoryDateLabel(it.startedAtMs, now) }.forEach { (date, records) ->
                item(key = "call-detail-date-$date") { Text(date, Modifier.callsHistoryPlacement(rows)
                    .padding(top = 8.dp, bottom = 12.dp).semantics { heading() },
                    fontSize = 16.sp, lineHeight = 24.sp, color = palette.secondary) }
                records.forEach { record ->
                    val key = record.recordId?.opaque ?: "${record.startedAtMs}:${record.direction}"
                    item(key = key) {
                        val held = record.recordId in historySnapshot?.deletePending.orEmpty()
                        val exitAlpha = rows.removing[key]
                        CallsHistoryRowMotion(Modifier.callsHistoryPlacement(rows),
                            key, key in rows.entering, held, exitAlpha) {
                            Column {
                                CallHistoryOccurrence(record, enabled = !held && exitAlpha == null,
                                    onActionStart = deletionUi.clearMessage, rootCustomActionsBlocked = deletion != null) {
                                    deletionUi.clearMessage(); deletion = it }
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }
                    communicationNoticeItem("call-detail-result-$key", deletionUi.message
                        ?.takeIf { record.recordId in deletionUi.ids }?.let { CommunicationNoticeSpec(it) })
                }
            }
        }
        communicationNoticeItem("call-detail-result-unmatched", deletionUi.message
            ?.takeIf { rows.rows.none { it.recordId in deletionUi.ids } }?.let { CommunicationNoticeSpec(it) })
    }
    deletion?.takeIf { active }?.let { id -> if (ready != null)
        CallHistoryDeleteConfirmation(ready, setOf(id), true, { deletion = null }, deletionUi.submit) }
}
