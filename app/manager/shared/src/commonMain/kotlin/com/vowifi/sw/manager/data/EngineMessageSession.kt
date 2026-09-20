package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

internal class EngineMessageSession(
    val runtime: EngineMessageRuntime,
    val repository: MessageRepository,
    val ownerIdFor: (String) -> SimOwnerId?,
    val nextMessageId: () -> String,
    val scope: CoroutineScope,
    val observer: MessagePersistenceObserver = MessagePersistenceObserver { _, _, _ -> },
) {
    val mutex = Mutex()
    val selection = MessageHistorySelection(repository, scope, observer)
    val state: StateFlow<MessageUiState> = selection.state
    val unreadSummary = MessageUnreadSummaryStore(repository, scope)

    @Volatile
    var closed = false
    var activeIccid: String? = null
    var activeOwner: SimOwnerId? = null
    var activeTransport: EngineMessageTransport? = null
    var eventJob: Job? = null
    var records: List<MessageRecord> = emptyList()
    var submitted: PlannedMessageSend? = null
    var pendingSent: List<SmsWireEvent.Sent> = emptyList()
    var sendBusy = false
    var storageHealthy = true
    var attachmentNotice: String? = null

    fun finishSend() {
        sendBusy = false
        publishRecords()
    }

    fun fail(state: MessageUiState) {
        detachTransport()
        records = emptyList()
        activeOwner = null
        attachmentNotice = null
        selection.publishLive(state)
    }

    fun detachTransport() {
        eventJob?.cancel()
        eventJob = null
        activeIccid = null
        activeTransport = null
        pendingSent = emptyList()
        sendBusy = false
    }

    fun publishRecords(freshIncomingId: String? = null) {
        activeOwner?.let { observer.onSnapshot(it, records, freshIncomingId) }
        selection.publishLive(MessageUiState.Ready(
            ownerId = activeOwner,
            records = records,
            origin = MessageOrigin.ENGINE,
            sendBusy = sendBusy,
            composerEnabled = activeTransport != null && storageHealthy,
            attachmentNotice = attachmentNotice,
            sendAttempt = submitted?.takeIf { it.owner == activeOwner }?.let { attempt ->
                records.filterIsInstance<MessageRecord.Outgoing>().firstOrNull { it.id == attempt.messageId }?.let { record ->
                    com.vowifi.sw.manager.model.MessageSendAttempt(attempt.owner, attempt.messageId, attempt.number, attempt.text, record.status)
                }
            },
        ))
    }
}

internal data class PlannedMessageSend(
    val messageId: String,
    val owner: SimOwnerId,
    val transport: EngineMessageTransport,
    val number: String,
    val text: String,
)

internal const val MESSAGE_LIST_FAILURE = "无法读取短信会话"
internal const val MESSAGE_PERMISSION_MESSAGE = "请在 KernelSU 中授予 SoftVoWiFi root 权限"
internal const val MESSAGE_CONNECT_FAILURE = "无法连接短信会话"
