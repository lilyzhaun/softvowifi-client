package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import kotlin.time.Clock
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Refresh

internal fun LazyListScope.callsHistoryContent(history: CallHistoryState,
    onOpen: (com.vowifi.sw.manager.ui.navigation.CallHistoryHandle) -> Unit = {}, callbackEnabled: Boolean = false,
    onCallback: (String) -> Unit = {}, onDelete: (Set<com.vowifi.sw.manager.model.CallHistoryRecordId>) -> Unit = {}) {
    when (history) {
        CallHistoryState.Loading -> item { CommunicationListState(StatePanelSpec(MiuixIcons.Refresh,
            StatePanelTone.INTERACTIVE, "正在读取通话记录", "请稍候", loading = true)) }
        CallHistoryState.Empty -> item { CallHistoryNotice("暂无通话记录", "此 SIM 的通话记录仅保存在本机") }
        is CallHistoryState.Failure -> item { CommunicationListState(StatePanelSpec(MiuixIcons.Close,
            StatePanelTone.ERROR, "无法读取通话记录", history.message)) }
        is CallHistoryState.Ready -> {
            if (history.records.isEmpty()) item { CallHistoryNotice("暂无通话记录", "此 SIM 的通话记录仅保存在本机") }
            val now = Clock.System.now().toEpochMilliseconds()
            callHistoryGroups(history).groupBy { phoneHistoryDateLabel(it.latest.startedAtMs, now) }.entries.forEachIndexed { index, (date, groups) ->
                 item { Text(date, modifier = Modifier.padding(top = if (index == 0) 20.dp else 24.dp, bottom = 8.dp), fontSize = 16.sp, lineHeight = 24.sp, color = phoneColors().secondary) }
                items(groups.size, key = { groups[it].handle.value }) { index ->
                    CallHistoryGroupRow(groups[index], onOpen, callbackEnabled, onCallback, onDelete)
                }
            }
        }
    }
}

@Composable
private fun CallHistoryNotice(title: String, summary: String) {
    CommunicationListState(StatePanelSpec(CallPhoneIcons.Phone, StatePanelTone.NEUTRAL, title, summary))
}

@Composable
internal fun ClearCallHistoryConfirmation(onCancel: () -> Unit, onConfirm: () -> Unit) {
    CommunicationConfirmation("清除通话记录？", "仅清除此 SIM 在本机的通话记录，无法撤销。", "确认清除", onCancel, onConfirm)
}
