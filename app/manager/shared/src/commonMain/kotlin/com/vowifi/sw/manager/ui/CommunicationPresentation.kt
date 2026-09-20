package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.ConversationGroup
import com.vowifi.sw.manager.model.CallDirection
import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.CallRecord
import com.vowifi.sw.manager.model.MessageRecord

internal enum class SemanticTone { INTERACTIVE, NEUTRAL, SUCCESS, WARNING, ERROR }

internal enum class SemanticColorRole { INTERACTIVE, NEUTRAL, SUCCESS, WARNING, ERROR }

internal enum class PresentationIcon { INFO, PROGRESS, SUCCESS, WARNING, ERROR, MESSAGE, INCOMING_CALL, OUTGOING_CALL, BACKSPACE }

internal data class ToneVisual(
    val semanticLabel: String,
    val icon: PresentationIcon,
    val lightColorRole: SemanticColorRole,
    val darkColorRole: SemanticColorRole,
)

internal fun toneVisual(tone: SemanticTone): ToneVisual = when (tone) {
    SemanticTone.INTERACTIVE -> ToneVisual("进行中", PresentationIcon.PROGRESS, SemanticColorRole.INTERACTIVE, SemanticColorRole.INTERACTIVE)
    SemanticTone.NEUTRAL -> ToneVisual("信息", PresentationIcon.INFO, SemanticColorRole.NEUTRAL, SemanticColorRole.NEUTRAL)
    SemanticTone.SUCCESS -> ToneVisual("正常", PresentationIcon.SUCCESS, SemanticColorRole.SUCCESS, SemanticColorRole.SUCCESS)
    SemanticTone.WARNING -> ToneVisual("需注意", PresentationIcon.WARNING, SemanticColorRole.WARNING, SemanticColorRole.WARNING)
    SemanticTone.ERROR -> ToneVisual("未完成", PresentationIcon.ERROR, SemanticColorRole.ERROR, SemanticColorRole.ERROR)
}

internal data class TextFlowSpec(val softWrap: Boolean = true, val maxLines: Int? = null)

internal data class OverallStatusHeroSpec(
    val tone: SemanticTone,
    val title: String,
    val summary: String,
    val statusText: String,
) {
    val titleFlow = TextFlowSpec()
    val summaryFlow = TextFlowSpec()
    val statusFlow = TextFlowSpec()
}

internal data class NoticeCardSpec(val tone: SemanticTone, val title: String, val summary: String) {
    val titleFlow = TextFlowSpec()
    val summaryFlow = TextFlowSpec()
}

internal data class SectionTitleSpec(val title: String, val summary: String? = null) {
    val titleFlow = TextFlowSpec()
    val summaryFlow = TextFlowSpec()
}

internal data class ActionPresentation(
    val label: String,
    val enabled: Boolean,
    val busy: Boolean,
) {
    val minimumTouchDp: Int = 48
    val labelFlow = TextFlowSpec()
}

internal data class CompactActionRowSpec(
    val title: String,
    val summary: String,
    val statusText: String,
    val tone: SemanticTone,
    val action: ActionPresentation,
)

internal data class ConversationRowPresentation(
    val peerDisplay: String,
    val avatarText: String,
    val preview: String,
    val timeText: String,
    val statusText: String?,
) {
    override fun toString(): String = "ConversationRowPresentation"
}

internal fun conversationRowPresentation(
    group: ConversationGroup,
    nowMs: Long,
    localDateLabel: (Long) -> String,
): ConversationRowPresentation {
    val latest = group.records.last()
    return ConversationRowPresentation(
        peerDisplay = privateUiPhoneDisplay(latest.peerLabel),
        avatarText = messageAvatarFallback(privateUiPhoneDisplay(latest.peerLabel)),
        preview = boundedCommunicationText(latest.preview, "内容不可显示", 80),
        timeText = formatCommunicationTime(latest.timestampMs, nowMs, localDateLabel),
        statusText = when (latest) {
            is MessageRecord.Incoming -> null
            is MessageRecord.Outgoing -> messageRecordStatusLabel(latest)
        },
    )
}

internal data class CallHistoryRowPresentation(
    val peerDisplay: String,
    val directionText: String,
    val outcomeText: String,
    val durationText: String,
    val timeText: String,
) {
    override fun toString(): String = "CallHistoryRowPresentation"
}

internal fun callHistoryRowPresentation(
    record: CallRecord,
    nowMs: Long,
    localDateLabel: (Long) -> String,
): CallHistoryRowPresentation = CallHistoryRowPresentation(
    peerDisplay = privateUiPhoneDisplay(record.peer?.digits()),
    directionText = when (record.direction) {
        CallDirection.INCOMING -> "来电"
        CallDirection.OUTGOING -> "去电"
    },
    outcomeText = when (record.outcome) {
        CallOutcome.STARTED -> "已开始"
        CallOutcome.CONNECTED -> "已接通"
        CallOutcome.COMPLETED -> "已完成"
        CallOutcome.MISSED -> "未接听"
        CallOutcome.REJECTED -> "已拒接"
        CallOutcome.CANCELED -> "已取消"
        CallOutcome.FAILED -> "未完成"
        CallOutcome.INTERRUPTED -> "已中断"
    },
    durationText = formatCallDuration(record.durationSec),
    timeText = formatCommunicationTime(record.startedAtMs, nowMs, localDateLabel),
)

internal enum class PressFeedback { MIUIX_DEFAULT }

internal enum class DialKey(val emittedText: String?, val spokenLabel: String) {
    ONE("1", "输入 1"), TWO("2", "输入 2"), THREE("3", "输入 3"),
    FOUR("4", "输入 4"), FIVE("5", "输入 5"), SIX("6", "输入 6"),
    SEVEN("7", "输入 7"), EIGHT("8", "输入 8"), NINE("9", "输入 9"),
    PLUS("+", "输入加号"), ZERO("0", "输入 0"), BACKSPACE(null, "退格"),
}

internal data class DialKeyVisualSpec(
    val key: DialKey,
    val enabled: Boolean,
    val semanticsLabel: String,
    val minimumTouchDp: Int = 48,
    val pressFeedback: PressFeedback = PressFeedback.MIUIX_DEFAULT,
)

internal fun dialKeyVisualSpec(key: DialKey, enabled: Boolean): DialKeyVisualSpec = DialKeyVisualSpec(
    key = key,
    enabled = enabled,
    semanticsLabel = if (enabled) key.spokenLabel else "${key.spokenLabel}（不可用）",
)
