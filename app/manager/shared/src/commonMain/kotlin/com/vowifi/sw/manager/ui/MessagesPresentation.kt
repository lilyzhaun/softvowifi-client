package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.ConversationGroup
import com.vowifi.sw.manager.data.ConversationGrouping
import com.vowifi.sw.manager.data.isStorageAttachmentNotice
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.model.SmsEngineStatus
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh

private val RECIPIENT_PATTERN = Regex("""\+?\d{1,20}""")

internal fun isValidMessageRecipient(recipient: String): Boolean = RECIPIENT_PATTERN.matches(recipient)

internal fun messageConversationGroups(state: MessageUiState): List<ConversationGroup> =
    if (state is MessageUiState.Ready) ConversationGrouping.group(state.records) else emptyList()

internal fun messageConversationSectionSummary(group: ConversationGroup): String = "${group.records.size} 条消息"

internal fun canSendMessage(
    state: MessageUiState,
    recipient: String,
    message: String,
): Boolean = state is MessageUiState.Available &&
    state.origin == MessageOrigin.ENGINE &&
    state.composerEnabled &&
    !state.sendBusy &&
    isValidMessageRecipient(recipient) &&
    message.isNotBlank()

internal fun messageStateSpec(state: MessageUiState): StatePanelSpec? = when (state) {
    MessageUiState.Loading -> StatePanelSpec(MiuixIcons.Refresh, StatePanelTone.INTERACTIVE, "正在读取消息", "完成后将显示短信会话。", loading = true)
    MessageUiState.Empty -> StatePanelSpec(MiuixIcons.Info, StatePanelTone.NEUTRAL, "暂无消息", "当前没有短信会话。")
    is MessageUiState.Available -> state.attachmentNotice?.let { notice ->
        if (isStorageAttachmentNotice(notice)) {
            StatePanelSpec(MiuixIcons.Close, StatePanelTone.ERROR, "短信未发送", notice)
        } else {
            StatePanelSpec(MiuixIcons.Info, StatePanelTone.NEUTRAL, "消息只读", notice)
        }
    }
    is MessageUiState.Failure -> StatePanelSpec(MiuixIcons.Close, StatePanelTone.ERROR, "消息读取失败", "无法读取短信会话，请稍后刷新。")
    is MessageUiState.PermissionDenied -> StatePanelSpec(MiuixIcons.Close, StatePanelTone.ERROR, "消息权限不足", "请在 KernelSU Manager 授权后刷新。")
    is MessageUiState.Unavailable -> StatePanelSpec(MiuixIcons.Info, StatePanelTone.NEUTRAL, "消息不可用", "当前没有可用的引擎短信会话。")
}

internal fun messageDirectionLabel(record: MessageRecord): String = when (record) {
    is MessageRecord.Incoming -> "本机已收到 MT 短信"
    is MessageRecord.Outgoing -> "本机发送的短信"
}

internal fun messageRecordStatusLabel(record: MessageRecord.Outgoing): String = messageStatusLabel(record.status, record.engineStatus)

internal fun messageStatusLabel(status: OutgoingMessageStatus, engineStatus: SmsEngineStatus?): String = when (engineStatus) {
    SmsEngineStatus.ACCEPTED -> "已受理"
    SmsEngineStatus.PENDING -> "处理中"
    SmsEngineStatus.DELIVERED -> "状态报告：已送达"
    SmsEngineStatus.FORWARDED -> "状态报告：已转发"
    SmsEngineStatus.REPLACED -> "状态报告：已替换"
    SmsEngineStatus.FAILED -> "发送未完成"
    null -> outgoingStatusLabel(status)
}

internal fun outgoingStatusLabel(status: OutgoingMessageStatus): String = when (status) {
    OutgoingMessageStatus.QUEUED -> "排队中"
    OutgoingMessageStatus.ACCEPTED -> "已受理"
    OutgoingMessageStatus.DELIVERY_REPORTED -> "状态报告：已送达"
    OutgoingMessageStatus.FAILED -> "发送未完成"
    OutgoingMessageStatus.UNKNOWN -> "发送结果未知"
}

internal fun messageStatusNote(record: MessageRecord.Outgoing): String? = when (record.engineStatus) {
    SmsEngineStatus.DELIVERED,
    SmsEngineStatus.FORWARDED,
    SmsEngineStatus.REPLACED,
    -> "不代表本机收到 MT 短信"
    SmsEngineStatus.ACCEPTED,
    SmsEngineStatus.PENDING,
    SmsEngineStatus.FAILED,
    -> null
    null -> if (record.status == OutgoingMessageStatus.DELIVERY_REPORTED) {
        "不代表本机收到 MT 短信"
    } else {
        null
    }
}
