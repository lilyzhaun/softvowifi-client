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

internal fun LazyListScope.callsHistoryContent(history: CallHistoryState) {
    when (history) {
        CallHistoryState.Loading -> item { CallHistoryNotice("正在读取通话记录", "请稍候") }
        CallHistoryState.Empty -> item { CallHistoryNotice("暂无通话记录", "此 SIM 的通话记录仅保存在本机") }
        is CallHistoryState.Failure -> item { CallHistoryNotice("无法读取通话记录", history.message) }
        is CallHistoryState.Ready -> {
            val now = Clock.System.now().toEpochMilliseconds()
            callHistoryDays(history.records) { messageDateSpec(it).label }.forEachIndexed { index, day ->
                item { Text(day.label, modifier = Modifier.padding(top = if (index == 0) 0.dp else 12.dp, bottom = 8.dp), fontSize = 14.sp, lineHeight = 20.sp, color = LocalSoftVoWiFiColors.current.textSecondary) }
                items(day.records.size) { index ->
                    CallHistoryRow(callHistoryRowPresentation(day.records[index], now, ::messageTimestampLabel))
                }
            }
        }
    }
}

@Composable
private fun CallHistoryNotice(title: String, summary: String) {
    CommunicationNotice(title, summary)
}

@Composable
internal fun ClearCallHistoryConfirmation(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("清除通话记录？", fontSize = 18.sp)
            Text("仅清除此 SIM 在本机的通话记录，无法撤销。", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ManagerTextAction("取消", ManagerAction.Rejected, onCancel, Modifier.weight(1f))
                ManagerTextAction("确认清除", ManagerAction.ClearCallHistory, onConfirm, Modifier.weight(1f))
            }
        }
    }
}
