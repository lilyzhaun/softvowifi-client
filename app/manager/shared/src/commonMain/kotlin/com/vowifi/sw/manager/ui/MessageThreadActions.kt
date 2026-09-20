package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog

internal val LocalSmsBubbleAction = staticCompositionLocalOf<((String) -> Unit)?> { null }

internal fun canReeditMessage(record: MessageRecord): Boolean =
    record is MessageRecord.Outgoing && record.status == OutgoingMessageStatus.FAILED

@Composable
internal fun MessageThreadActions(record: MessageRecord?, body: String, editingEnabled: Boolean,
    onDismiss: () -> Unit, onReedit: (String) -> Unit, onCopied: () -> Unit) {
    var details by remember(record?.id) { mutableStateOf(false) }
    var replacing by remember(record?.id) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    OverlayDialog(show = record != null, title = when { replacing -> "替换现有草稿？"; details -> "消息详情"; else -> "消息操作" },
        summary = if (replacing) "仅回填正文，不会发送。取消将保留现有草稿。" else null, onDismissRequest = onDismiss) {
        if (record != null) Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                replacing -> {
                    ManagerTextAction("取消", ManagerAction.Rejected, onDismiss)
                    ManagerTextAction("替换草稿", ManagerAction.Navigate, {
                        if (editingEnabled && canReeditMessage(record)) onReedit(boundedDraftBody(record.preview))
                        onDismiss()
                    }, enabled = editingEnabled && canReeditMessage(record))
                }
                details -> {
                    Text(if (record is MessageRecord.Incoming) "方向：收到" else "方向：发出")
                    Text("时间：${messageDateSpec(record.timestampMs).label} ${messageTimestampLabel(record.timestampMs)}")
                    if (record is MessageRecord.Outgoing) {
                        Text(messageRecordStatusLabel(record))
                        messageStatusNote(record)?.let { Text(it) }
                        if (record.status == OutgoingMessageStatus.UNKNOWN) Text("请先核对发送结果；不会自动重发。")
                    } else Text("本机已收到 MT 短信")
                    ManagerTextAction("关闭详情", ManagerAction.Navigate, onDismiss)
                }
                else -> {
                    val copy = messageCopyableBody(record.preview)
                    ManagerTextAction("复制正文", ManagerAction.Navigate, {
                        if (copy != null) scope.launch { clipboard.setClipEntry(messageTextClipEntry(copy)); onCopied() }
                        onDismiss()
                    }, enabled = copy != null)
                    ManagerTextAction("消息详情", ManagerAction.Navigate, { details = true })
                    if (canReeditMessage(record)) ManagerTextAction("重新编辑", ManagerAction.Navigate, {
                        if (body.isNotBlank() && body != boundedDraftBody(record.preview)) replacing = true
                        else { onReedit(boundedDraftBody(record.preview)); onDismiss() }
                    }, enabled = editingEnabled)
                    ManagerTextAction("取消", ManagerAction.Rejected, onDismiss)
                }
            }
        }
    }
}
