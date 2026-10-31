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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog

internal val LocalSmsBubbleAction = staticCompositionLocalOf<((String) -> Unit)?> { null }
internal enum class SmsMessageAction { MENU, DETAILS, REEDIT }
internal data class SmsBubbleMenu(val selectedId: String?, val reeditableIds: Set<String>, val editingEnabled: Boolean,
    val onDetails: () -> Unit, val onReedit: () -> Unit, val onDismiss: () -> Unit, val actionsBlocked: Boolean = false)
internal val LocalSmsBubbleMenu = staticCompositionLocalOf<SmsBubbleMenu?> { null }

internal fun canReeditMessage(record: MessageRecord): Boolean =
    record is MessageRecord.Outgoing && record.status == OutgoingMessageStatus.FAILED

@Composable
internal fun MessageThreadActions(record: MessageRecord?, body: String, editingEnabled: Boolean,
    onDismiss: () -> Unit, onReedit: (String) -> Unit, onCopied: () -> Unit,
    mode: SmsMessageAction = SmsMessageAction.MENU) {
    if (record == null || mode == SmsMessageAction.MENU) return
    val scrollState = rememberScrollState()
    val scroll = rememberSmsScrollFeedback(scrollState.canScrollBackward || scrollState.canScrollForward)
    if (mode == SmsMessageAction.REEDIT) {
        if (!editingEnabled || !canReeditMessage(record)) {
            LaunchedEffect(record.id) { onDismiss() }
        } else if (body.isNotBlank() && body != boundedDraftBody(record.preview)) {
            CommunicationConfirmation("替换现有草稿？", "仅回填正文，不会发送。取消将保留现有草稿。", "替换草稿",
                onDismiss, { onReedit(boundedDraftBody(record.preview)); onDismiss() })
        } else LaunchedEffect(record.id) { onReedit(boundedDraftBody(record.preview)); onDismiss() }
        return
    }
    CommunicationSheet("消息详情", onDismiss,
        closeLabel = "关闭详情") {
        Column(Modifier.weight(1f, fill = false).then(scroll.modifier).verticalScroll(scrollState, overscrollEffect = scroll.effect)) {
            CommunicationFact("方向", if (record is MessageRecord.Incoming) "收到" else "发出")
            CommunicationFact("时间", "${messageDateSpec(record.timestampMs).label} ${messageTimestampLabel(record.timestampMs)}")
            if (record is MessageRecord.Outgoing) {
                CommunicationFact("发送状态", messageRecordStatusLabel(record))
                messageStatusNote(record)?.let { CommunicationFootnote(it) }
                if (record.status == OutgoingMessageStatus.UNKNOWN) CommunicationFootnote("请先核对发送结果；不会自动重发。")
            } else CommunicationFact("接收状态", "本机已收到 MT 短信")
        }
    }
}
