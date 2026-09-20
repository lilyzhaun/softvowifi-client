package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OutgoingMessageStatus

enum class MessageFixtureMode {
    LOADING,
    EMPTY,
    READY,
    READ_ONLY,
    ERROR,
    PERMISSION,
    UNAVAILABLE,
    ;

    companion object {
        fun fromWire(mode: String): MessageFixtureMode = when (mode) {
            "loading" -> LOADING
            "empty" -> EMPTY
            "ready" -> READY
            "read-only" -> READ_ONLY
            "error" -> ERROR
            "permission" -> PERMISSION
            "unavailable" -> UNAVAILABLE
            else -> throw IllegalArgumentException(mode)
        }
    }
}

object MessageFixtures {
    const val PRODUCTION_UNAVAILABLE_MESSAGE = "当前环境不提供短信"

    fun state(mode: MessageFixtureMode): MessageUiState = when (mode) {
        MessageFixtureMode.LOADING -> MessageUiState.Loading
        MessageFixtureMode.EMPTY -> MessageUiState.Empty
        MessageFixtureMode.READY -> ready()
        MessageFixtureMode.READ_ONLY -> readOnly()
        MessageFixtureMode.ERROR -> MessageUiState.Failure("样例短信结果无法解析")
        MessageFixtureMode.PERMISSION -> MessageUiState.PermissionDenied("KernelSU 尚未授权")
        MessageFixtureMode.UNAVAILABLE -> MessageUiState.Unavailable("当前模块不支持短信")
    }

    fun ready(): MessageUiState.Ready = MessageUiState.Ready(
        records = readyRecords(),
        origin = MessageOrigin.DESKTOP_FIXTURE,
    )

    fun readOnly(): MessageUiState.Ready = MessageUiState.Ready(
        records = readyRecords(),
        origin = MessageOrigin.DESKTOP_FIXTURE,
        composerEnabled = false,
        attachmentNotice = ActiveEngineSelector.NONE_MESSAGE,
    )

    fun visibleStrings(state: MessageUiState): List<String> = when (state) {
        is MessageUiState.Inbox -> state.page.items.flatMap { listOf(it.peerDisplay, it.latestPreview) } + listOfNotNull(state.attachmentNotice)
        MessageUiState.Loading,
        MessageUiState.Empty -> emptyList()
        is MessageUiState.Ready -> state.records.flatMap { record ->
            listOf(record.id, record.peerLabel, record.preview)
        } + listOfNotNull(state.attachmentNotice)
        is MessageUiState.Failure -> listOf(state.message)
        is MessageUiState.PermissionDenied -> listOf(state.message)
        is MessageUiState.Unavailable -> listOf(state.message)
    }

    private fun readyRecords(): List<MessageRecord> = listOf(
        MessageRecord.Incoming(
            id = "in-1",
            peerLabel = PEER_LABEL,
            preview = "样例入向短信",
            timestampMs = 1_780_000_000_000L,
            isRead = true,
            readAtMs = 1_780_000_000_000L,
        ),
        MessageRecord.Outgoing(
            id = "out-queued",
            peerLabel = PEER_LABEL,
            preview = "样例排队短信",
            timestampMs = 1_780_000_000_100L,
            status = OutgoingMessageStatus.QUEUED,
        ),
        MessageRecord.Outgoing(
            id = "out-accepted",
            peerLabel = PEER_LABEL,
            preview = "样例已受理短信",
            timestampMs = 1_780_000_000_200L,
            status = OutgoingMessageStatus.ACCEPTED,
        ),
        MessageRecord.Outgoing(
            id = "out-reported",
            peerLabel = PEER_LABEL,
            preview = "样例送达报告",
            timestampMs = 1_780_000_000_300L,
            status = OutgoingMessageStatus.DELIVERY_REPORTED,
        ),
        MessageRecord.Outgoing(
            id = "out-failed",
            peerLabel = PEER_LABEL,
            preview = "样例发送失败",
            timestampMs = 1_780_000_000_400L,
            status = OutgoingMessageStatus.FAILED,
        ),
        MessageRecord.Outgoing(
            id = "out-unknown",
            peerLabel = PEER_LABEL,
            preview = "这是一条示例短信",
            timestampMs = 1_780_000_000_500L,
            status = OutgoingMessageStatus.UNKNOWN,
        ),
    )

    private const val PEER_LABEL = "+155501"
}
