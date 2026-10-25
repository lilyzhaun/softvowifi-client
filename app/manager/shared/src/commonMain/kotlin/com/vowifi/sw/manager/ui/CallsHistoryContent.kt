package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallHistoryRecordId
import kotlin.time.Clock
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Refresh

internal fun LazyListScope.callsHistoryContent(history: CallHistoryState,
    onOpen: (com.vowifi.sw.manager.ui.navigation.CallHistoryHandle) -> Unit = {}, callbackEnabled: Boolean = false,
    onCallback: (String) -> Unit = {}, onDelete: (Set<CallHistoryRecordId>) -> Unit = {},
    rows: CallsHistoryRows<CallHistoryGroup, String>? = null, pending: Set<CallHistoryRecordId> = emptySet(),
    deletionUi: CallsHistoryDeleteUi? = null, rootCustomActionsBlocked: Boolean = false,
    loading: ManagerLoadingState? = null, historyEnabled: Boolean = true, onRetry: (() -> Unit)? = null) {
    if (loading?.hidden == true) return
    val shown = if (loading?.showLoading == true) CallHistoryState.Loading else history
    val fade = loading?.let { Modifier.managerLoadingAlpha(it) } ?: Modifier
    val displayed = if (shown == CallHistoryState.Loading) emptyList() else
        rows?.rows ?: (shown as? CallHistoryState.Ready)?.let(::callHistoryGroups).orEmpty()
    val errorIds = deletionUi?.ids.orEmpty()
    if (displayed.isNotEmpty()) {
        val now = Clock.System.now().toEpochMilliseconds()
        displayed.groupBy { phoneHistoryDateLabel(it.latest.startedAtMs, now) }.entries.forEachIndexed { index, (date, groups) ->
            item(key = "calls-history-date-$date") {
                Text(date, modifier = fade.callsHistoryPlacement(rows)
                    .padding(top = if (index == 0) 20.dp else 24.dp, bottom = 8.dp),
                    fontSize = 16.sp, lineHeight = 24.sp, color = phoneColors().secondary)
            }
            groups.forEach { group ->
                val key = group.handle.value
                item(key = key) {
                    val held = group.ids.any { it in pending }
                    val exitAlpha = rows?.removing?.get(key)
                    CallsHistoryRowMotion(fade.callsHistoryPlacement(rows),
                        key, key in rows?.entering.orEmpty(), held, exitAlpha) {
                        CallHistoryGroupRow(group, onOpen, callbackEnabled, onCallback, onDelete,
                            enabled = historyEnabled && !held && exitAlpha == null, onActionStart = { deletionUi?.clearMessage?.invoke() },
                            rootCustomActionsBlocked = rootCustomActionsBlocked)
                    }
                }
                communicationNoticeItem("calls-history-result-$key", deletionUi?.message
                    ?.takeIf { group.ids.any { it in errorIds } }?.let { CommunicationNoticeSpec(it) })
            }
        }
        communicationNoticeItem("calls-history-result-unmatched", deletionUi?.message
            ?.takeIf { displayed.none { row -> row.ids.any { it in errorIds } } }?.let { CommunicationNoticeSpec(it) })
        return
    }
    when (shown) {
        CallHistoryState.Loading -> item { Box(fade) { CommunicationListState(StatePanelSpec(MiuixIcons.Refresh,
            StatePanelTone.INTERACTIVE, "正在读取通话记录", "请稍候", loading = true)) } }
        CallHistoryState.Empty -> item { Box(fade) { CallHistoryNotice("暂无通话记录", "此 SIM 的通话记录仅保存在本机") } }
        is CallHistoryState.Failure -> item { Box(fade) { CommunicationListState(StatePanelSpec(MiuixIcons.Close,
            StatePanelTone.ERROR, "无法读取通话记录", shown.message)) {
                onRetry?.let { CommunicationTextAction("重试", it) }
            } } }
        is CallHistoryState.Ready -> {
            if (shown.records.isEmpty()) item { Box(fade) { CallHistoryNotice("暂无通话记录", "此 SIM 的通话记录仅保存在本机") } }
        }
    }
    communicationNoticeItem("calls-history-result-unmatched", deletionUi?.message?.let { CommunicationNoticeSpec(it) })
}

@Composable
private fun CallHistoryNotice(title: String, summary: String) {
    CommunicationListState(StatePanelSpec(CallPhoneIcons.Phone, StatePanelTone.NEUTRAL, title, summary))
}

@Composable
internal fun ClearCallHistoryConfirmation(onCancel: () -> Unit, onConfirm: () -> Unit) {
    CommunicationConfirmation("清除通话记录？", "仅清除此 SIM 在本机的通话记录，无法撤销。", "确认清除", onCancel, onConfirm)
}
