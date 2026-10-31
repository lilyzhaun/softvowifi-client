package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.OutgoingMessageStatus

const val MESSAGE_HISTORY_PAGE_SIZE = 50

interface MessageHistoryRepository {
    suspend fun owners(cursor: MessageOwnerCursor? = null): MessageOwnerPage
    suspend fun summaries(request: MessageSummaryRequest): MessageSummaryPage
    suspend fun thread(identity: StoredMessageConversation, cursor: MessageThreadCursor? = null): MessageThreadSlice
    suspend fun hasConversation(identity: StoredMessageConversation): Boolean = thread(identity).records.isNotEmpty()
    suspend fun unreadIds(identity: StoredMessageConversation): List<String>
}

class MessageOwnerCursor internal constructor(internal val after: String) {
    override fun toString(): String = "MessageOwnerCursor"
}

data class MessageOwnerSummary(
    val owner: SimOwnerId,
    val conversationCount: Long,
    val messageCount: Long,
    val unreadCount: Long,
) {
    override fun toString(): String = "MessageOwnerSummary"
}

data class MessageOwnerPage(val items: List<MessageOwnerSummary>, val next: MessageOwnerCursor?)

class MessageSummaryCursor internal constructor(
    internal val owner: SimOwnerId,
    internal val query: String,
    internal val timestampMs: Long,
    internal val messageId: String,
    internal val conversationKey: String,
) {
    override fun toString(): String = "MessageSummaryCursor"
}

data class MessageSummaryRequest(
    val owner: SimOwnerId,
    val query: String = "",
    val cursor: MessageSummaryCursor? = null,
) {
    override fun toString(): String = "MessageSummaryRequest"
}

data class MessageConversationSummary(
    val identity: StoredMessageConversation,
    val peerDisplay: String,
    val latestPreview: String,
    val latestMessageId: String,
    val timestampMs: Long,
    val outgoingStatus: OutgoingMessageStatus?,
    val messageCount: Long,
    val unreadCount: Long,
    val engineStatus: com.vowifi.sw.manager.model.SmsEngineStatus? = null,
) {
    override fun toString(): String = "MessageConversationSummary"
}

data class MessageSummaryPage(val items: List<MessageConversationSummary>, val next: MessageSummaryCursor?)

class MessageThreadCursor internal constructor(
    internal val identity: StoredMessageConversation,
    internal val timestampMs: Long,
    internal val messageId: String,
) {
    override fun toString(): String = "MessageThreadCursor"
}

data class MessageThreadSlice(
    val identity: StoredMessageConversation,
    val records: List<MessageRecord>,
    val older: MessageThreadCursor?,
) {
    override fun toString(): String = "MessageThreadSlice(size=${records.size})"
}
