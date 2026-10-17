package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageSendAttempt
import com.vowifi.sw.manager.model.MessageUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
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
    val attachmentMutex = Mutex()
    val selection = MessageHistorySelection(repository, scope, observer)
    val state: StateFlow<MessageUiState> = selection.state
    val unreadSummary = MessageUnreadSummaryStore(repository, scope)

    @Volatile var closed = false
    @Volatile var sessions: Map<SimOwnerId, MessageLiveSession> = emptyMap()
    var activeOwners: Set<SimOwnerId>? = null
    var activeRosterComplete = false
    var startupRecovered = false
    var automaticOwner: SimOwnerId? = null
    var lineLabels: Map<SimOwnerId, String> = emptyMap()
    var runningOwners: Set<SimOwnerId> = emptySet()
    var fallback: MessageUiState = MessageUiState.Loading

    fun isCurrent(line: MessageLiveSession): Boolean = !closed && line.attached && sessions[line.owner] === line
    fun isAvailable(owner: SimOwnerId): Boolean = !activeRosterComplete || activeOwners?.contains(owner) == true

    suspend fun detach(line: MessageLiveSession) {
        line.attached = false
        line.transport.retireMessages()
        line.connected = false
        line.registered = false
        line.pendingSent = emptyList()
        try {
            if (line.sendBusy) {
                line.submitted?.let { repository.markUnknown(line.owner, it.messageId, runtime.clock()) }
                line.records = repository.listByOwner(line.owner)
            }
        } finally {
            line.sendBusy = false
            line.eventJob?.cancel()
            line.disconnectJob?.cancel()
            line.statusJob?.cancel()
        }
    }

    fun publishRecords(line: MessageLiveSession, freshIncomingId: String? = null, mutation: MessageHistoryMutation? = null) {
        if (closed) return
        observer.onSnapshot(line.owner, line.records, freshIncomingId)
        publish(mutation)
    }

    fun publish(mutation: MessageHistoryMutation? = null) {
        if (closed) return
        automaticOwner = if (activeRosterComplete) activeOwners?.firstOrNull { owner ->
            sessions[owner]?.let { it.attached && it.connected && it.registered && it.storageHealthy } == true
        } ?: activeOwners?.firstOrNull { owner ->
            sessions[owner]?.let { it.attached && it.connected && it.registered } == true
        } else null
        selection.publishLive(fallback, sessions.mapValues { (_, line) ->
            val snapshot = line.snapshot()
            if (isAvailable(line.owner)) snapshot else snapshot.copy(composerEnabled = false, attachmentNotice = HISTORY_READ_ONLY)
        }, activeOwners, automaticOwner, activeRosterComplete, lineLabels, runningOwners, mutation)
    }
}

internal class MessageLiveSession(
    val owner: SimOwnerId,
    val iccid: String,
    val pid: Long?,
    val transport: EngineMessageTransport,
) {
    @Volatile var attached = true
    var registered = false
    var connected = false
    var eventJob: Job? = null
    var disconnectJob: Job? = null
    var statusJob: Job? = null
    var records: List<MessageRecord> = emptyList()
    var submitted: PlannedMessageSend? = null
    var pendingSent: List<SmsWireEvent.Sent> = emptyList()
    var sendBusy = false
    var storageHealthy = true
    var attachmentNotice: String? = MESSAGE_NOT_REGISTERED

    fun snapshot() = MessageUiState.Ready(
        ownerId = owner, records = records, origin = MessageOrigin.ENGINE, sendBusy = sendBusy,
        composerEnabled = attached && connected && registered && storageHealthy,
        sessionConnected = attached && connected,
        attachmentNotice = attachmentNotice,
        sendAttempt = submitted?.let { attempt ->
            records.filterIsInstance<MessageRecord.Outgoing>().firstOrNull { it.id == attempt.messageId }?.let { record ->
                MessageSendAttempt(owner, attempt.messageId, attempt.number, attempt.text, record.status)
            }
        },
    )

    override fun toString(): String = "MessageLiveSession"
}

internal class PlannedMessageSend(
    val messageId: String,
    val line: MessageLiveSession,
    val number: String,
    val text: String,
) {
    val owner get() = line.owner
    val transport get() = line.transport
    override fun toString(): String = "PlannedMessageSend"
}

internal const val MESSAGE_LIST_FAILURE = "无法读取短信会话"
internal const val MESSAGE_PERMISSION_MESSAGE = "请在 KernelSU 中授予 SoftVoWiFi root 权限"
internal const val MESSAGE_CONNECT_FAILURE = "无法连接短信会话"
internal const val MESSAGE_NOT_REGISTERED = "此 SIM 尚未完成 IMS 注册，暂不能发送短信。"
internal const val MESSAGE_SELECT_OWNER = "请选择发送短信的 SIM。"
