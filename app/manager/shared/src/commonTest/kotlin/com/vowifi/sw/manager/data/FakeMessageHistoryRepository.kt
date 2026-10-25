package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.ui.boundedCommunicationText
import com.vowifi.sw.manager.ui.boundedMessageSearchQuery

internal class FakeMessageHistoryRepository(private val snapshot: () -> List<FakeStoredMessage>) : MessageHistoryRepository {
    override suspend fun owners(cursor: MessageOwnerCursor?): MessageOwnerPage {
        val rows = snapshot().groupBy { it.ownerId }.entries.sortedBy { it.key.wire() }
            .filter { cursor == null || it.key.wire() > cursor.after }.take(51)
        val visible = rows.take(50)
        return MessageOwnerPage(visible.map { (owner, messages) ->
            MessageOwnerSummary(owner, messages.map { it.conversationKey }.distinct().size.toLong(), messages.size.toLong(), messages.count { it.countsAsUnread }.toLong())
        }, if (rows.size > 50) MessageOwnerCursor(visible.last().key.wire()) else null)
    }

    override suspend fun summaries(request: MessageSummaryRequest): MessageSummaryPage {
        val query = boundedMessageSearchQuery(request.query).trim()
        val cursor = request.cursor
        require(cursor == null || cursor.owner == request.owner && cursor.query == query)
        val rows = snapshot().filter { it.ownerId == request.owner }.groupBy { it.conversationKey }.map { (key, messages) ->
            val last = messages.maxWith(compareBy<FakeStoredMessage> { it.messageAtMs }.thenBy { it.id })
            MessageConversationSummary(StoredMessageConversation(request.owner, key), messageSenderDisplay(last.peerAddress),
                boundedCommunicationText(last.body, "内容不可显示", 80), last.id, last.messageAtMs, last.outgoingStatus,
                messages.size.toLong(), messages.count { it.countsAsUnread }.toLong(), last.engineStatus)
        }.sortedWith(compareByDescending<MessageConversationSummary> { it.timestampMs }.thenByDescending { it.latestMessageId }.thenByDescending { it.identity.key })
            .filter { cursor == null || it.timestampMs < cursor.timestampMs || it.timestampMs == cursor.timestampMs && it.latestMessageId < cursor.messageId }
            .filter { it.peerDisplay.contains(query, true) || it.latestPreview.contains(query, true) }.take(51)
        val visible = rows.take(50)
        return MessageSummaryPage(visible, if (rows.size > 50) visible.last().let {
            MessageSummaryCursor(request.owner, query, it.timestampMs, it.latestMessageId, it.identity.key)
        } else null)
    }

    override suspend fun thread(identity: StoredMessageConversation, cursor: MessageThreadCursor?): MessageThreadSlice {
        require(cursor == null || cursor.identity == identity)
        val rows = snapshot().filter { it.ownerId == identity.owner && it.conversationKey == identity.key }
            .filter { cursor == null || it.messageAtMs < cursor.timestampMs || it.messageAtMs == cursor.timestampMs && it.id < cursor.messageId }
            .sortedWith(compareByDescending<FakeStoredMessage> { it.messageAtMs }.thenByDescending { it.id }).take(51)
        val visible = rows.take(50)
        return MessageThreadSlice(identity, visible.asReversed().map(FakeStoredMessage::toRecord), if (rows.size > 50) visible.last().let {
            MessageThreadCursor(identity, it.messageAtMs, it.id)
        } else null)
    }

    override suspend fun unreadIds(identity: StoredMessageConversation): List<String> = snapshot()
        .filter { it.ownerId == identity.owner && it.conversationKey == identity.key && it.incoming && !it.isRead }.map { it.id }
}
