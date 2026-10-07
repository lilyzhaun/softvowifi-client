package com.vowifi.sw.manager.model

enum class MessageOrigin {
    DESKTOP_FIXTURE,
    ENGINE,
}

enum class OutgoingMessageStatus {
    QUEUED,
    ACCEPTED,
    DELIVERY_REPORTED,
    FAILED,
    UNKNOWN,
}

enum class SmsEngineStatus {
    ACCEPTED,
    PENDING,
    DELIVERED,
    FORWARDED,
    REPLACED,
    FAILED,
}

sealed interface MessageRecord {
    val id: String
    val engineSmsId: String?
    val peerLabel: String
    val preview: String
    val timestampMs: Long
    val isLocalMtReceipt: Boolean

    data class Incoming(
        override val id: String,
        override val peerLabel: String,
        override val preview: String,
        override val timestampMs: Long,
        override val engineSmsId: String? = null,
        val isRead: Boolean = false,
        val readAtMs: Long? = null,
    ) : MessageRecord {
        override val isLocalMtReceipt: Boolean = true

        override fun toString(): String = "Incoming(id=$id, isRead=$isRead)"
    }

    data class Outgoing(
        override val id: String,
        override val peerLabel: String,
        override val preview: String,
        override val timestampMs: Long,
        val status: OutgoingMessageStatus,
        override val engineSmsId: String? = null,
        val engineStatus: SmsEngineStatus? = null,
    ) : MessageRecord {
        override val isLocalMtReceipt: Boolean = false

        override fun toString(): String = "Outgoing(id=$id, status=$status)"
    }
}

data class MessageHistoryLine(
    val owner: com.vowifi.sw.manager.data.SimOwnerId,
    val conversationCount: Int,
    val connected: Boolean,
    val label: com.vowifi.sw.manager.data.MessageOwnerLabel? = null,
    val active: Boolean? = null,
    val sendable: Boolean = connected,
    val simLabel: String? = null,
    val running: Boolean = false,
) {
    override fun toString(): String = "MessageHistoryLine(conversationCount=$conversationCount)"
}

data class MessageHistoryChoices(
    val lines: List<MessageHistoryLine> = emptyList(),
    val selectedOwner: com.vowifi.sw.manager.data.SimOwnerId? = null,
    val storageAvailable: Boolean = false,
    val labelsLoading: Boolean = false,
    val labelsFailed: Boolean = false,
    val activeRosterComplete: Boolean = false,
)

sealed interface MessageUiState {
    sealed interface Available : MessageUiState {
        val origin: MessageOrigin
        val sendBusy: Boolean
        val composerEnabled: Boolean
        val attachmentNotice: String?
        val ownerId: com.vowifi.sw.manager.data.SimOwnerId?
        val sendAttempt: MessageSendAttempt?
    }
    data object Loading : MessageUiState
    data object Empty : MessageUiState
    data class Ready(
        val records: List<MessageRecord>,
        override val origin: MessageOrigin,
        override val sendBusy: Boolean = false,
        override val composerEnabled: Boolean = false,
        override val attachmentNotice: String? = null,
        override val ownerId: com.vowifi.sw.manager.data.SimOwnerId? = null,
        override val sendAttempt: MessageSendAttempt? = null,
        val window: MessageThreadWindow? = null,
        val sessionConnected: Boolean = composerEnabled,
    ) : Available
    data class Inbox(
        override val ownerId: com.vowifi.sw.manager.data.SimOwnerId,
        val page: MessageInboxPage,
        override val sendBusy: Boolean = false,
        override val composerEnabled: Boolean = false,
        override val attachmentNotice: String? = null,
        override val sendAttempt: MessageSendAttempt? = null,
    ) : Available { override val origin = MessageOrigin.ENGINE }
    data class Failure(val message: String) : MessageUiState
    data class PermissionDenied(val message: String) : MessageUiState
    data class Unavailable(val message: String) : MessageUiState
}
