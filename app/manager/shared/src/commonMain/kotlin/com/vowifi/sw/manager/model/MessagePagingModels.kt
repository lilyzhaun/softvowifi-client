package com.vowifi.sw.manager.model

import com.vowifi.sw.manager.data.MessageConversationSummary
import com.vowifi.sw.manager.data.MessageSummaryCursor
import com.vowifi.sw.manager.data.MessageThreadCursor
import com.vowifi.sw.manager.data.StoredMessageConversation
import com.vowifi.sw.manager.data.SimOwnerId

data class MessageInboxPage(
    val query: String = "",
    val items: List<MessageConversationSummary> = emptyList(),
    val next: MessageSummaryCursor? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
) { override fun toString(): String = "MessageInboxPage(size=${items.size}, loading=$loading, failed=$failed)" }

data class MessageThreadWindow(
    val identity: StoredMessageConversation,
    val older: MessageThreadCursor?,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

data class MessageSendAttempt(
    val owner: SimOwnerId,
    val messageId: String,
    val recipient: String,
    val body: String,
    val status: OutgoingMessageStatus,
) { override fun toString(): String = "MessageSendAttempt(status=$status)" }
