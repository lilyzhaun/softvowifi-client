package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import com.vowifi.sw.manager.data.CallLineState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import com.vowifi.sw.manager.data.SimOwnerId
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
import top.yukonga.miuix.kmp.icon.extended.Messages

@Composable internal fun CallHistoryDetailScreen(handle: CallHistoryHandle, history: CallHistoryState,
    onDelete: (CallHistoryState.Ready, Set<CallHistoryRecordId>) -> Unit) =
    CallHistoryDetailScreen(handle, history, onDelete, null, null)

@Composable internal fun CallHistoryDetailScreen(handle: CallHistoryHandle, history: CallHistoryState,
    onDelete: (CallHistoryState.Ready, Set<CallHistoryRecordId>) -> Unit,
    historySnapshot: CallHistoryViewSnapshot?,
    onDeleteAndAwait: (suspend (CallHistoryState.Ready, Set<CallHistoryRecordId>) -> CallHistoryDeleteResult)?,
    onCallback: ((String) -> Unit)? = null, callbackUnavailableReason: String? = null,
    onRetry: (() -> Unit)? = null, onMessage: (suspend (SimOwnerId, String) -> Boolean)? = null) = PhoneStyle {
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
    CommunicationFeedbackHost {
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
            CommunicationListState(panel) {
                if (confirmedHistory is CallHistoryState.Failure) onRetry?.let { CommunicationTextAction("重试", it) }
            }
        } else {
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(64.dp).background(palette.tonal, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(CallPhoneIcons.Phone, null, Modifier.size(30.dp), tint = palette.accent)
                    }
                    PhoneText(shownGroup.peer?.dialNumber()?.let { displayCallNumber(privateUiPhoneDisplay(it)) } ?: "未知号码",
                         Modifier.semantics { heading() }, size = 24)
                    shownGroup.peer?.dialNumber()?.let { number ->
                         CallHistoryDetailActions(number, active && group != null && deletion == null,
                             onCallback, callbackUnavailableReason, ready?.owner, onMessage)
                    }
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
}

/** Shared by the list and detail callers; no UI inference of SIM identity or new selection. */
internal fun callHistoryCallbackUnavailableReason(history: CallHistoryState, lines: CallLineState, hasDial: Boolean): String? = when {
    history !is CallHistoryState.Ready -> "通话记录不可用。"
    !hasDial -> "当前入口不支持拨号。"
    history.owner != lines.selectedOwner -> "请先选择此记录所属线路后回拨。"
    lines.outgoingBinding == null -> "当前线路不可拨号，请检查线路注册状态。"
    else -> null
}

@Composable private fun CallHistoryDetailActions(number: String, enabled: Boolean, onCallback: ((String) -> Unit)?,
    unavailableReason: String?, owner: SimOwnerId?, onMessage: (suspend (SimOwnerId, String) -> Boolean)?) {
    val scope = rememberCoroutineScope()
    val activeNow by rememberUpdatedState(enabled && LocalAppRouteActive.current)
    val messageNow by rememberUpdatedState(onMessage)
    var messageJob by remember(number) { mutableStateOf<Job?>(null) }
    var messagePending by remember(number) { mutableStateOf(false) }
    var messageFailure by remember(number) { mutableStateOf(false) }
    LaunchedEffect(enabled, LocalAppRouteActive.current) {
        if (!activeNow) { messageJob?.cancel(); messageFailure = false }
    }
    val reason = unavailableReason ?: if (onCallback == null) "当前入口不支持拨号。" else null
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.widthIn(max = 240.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
            PhoneButton("回拨", { if (activeNow) onCallback?.invoke(number) }, Modifier.weight(1f),
                icon = CallPhoneIcons.Phone, compact = true, enabled = enabled && reason == null)
            PhoneButton("短信", {
                if (activeNow && !messagePending && messageNow != null && owner != null) {
                    messagePending = true
                    messageFailure = false
                    messageJob = scope.launch {
                        try {
                            val opened = withTimeoutOrNull(5_000) { messageNow?.invoke(owner, number) } == true
                            if (activeNow) messageFailure = !opened
                        } finally { messagePending = false }
                    }
                }
            }, Modifier.weight(1f), icon = MiuixIcons.Messages, compact = true,
                enabled = enabled && owner != null && onMessage != null && !messagePending)
        }
        reason?.let { PhoneText(it, secondary = true, size = 14) }
        CommunicationNoticeSlot((when {
            messagePending -> "正在打开短信"
            messageFailure -> "无法打开短信，请稍后重试。"
            else -> null
        })?.let { CommunicationNoticeSpec(it) })
    }
}
