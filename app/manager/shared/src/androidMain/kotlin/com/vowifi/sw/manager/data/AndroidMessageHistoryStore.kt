package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.ui.boundedCommunicationText
import com.vowifi.sw.manager.ui.boundedMessageSearchQuery
import com.vowifi.sw.manager.ui.privateUiPhoneDisplay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

internal class AndroidMessageHistoryStore(private val dao: AndroidMessageHistoryDao) : MessageHistoryRepository {
    override suspend fun owners(cursor: MessageOwnerCursor?): MessageOwnerPage = historyStorageCall {
        val rows = dao.owners(cursor?.after)
        val visible = rows.take(MESSAGE_HISTORY_PAGE_SIZE)
        MessageOwnerPage(
            visible.map { row -> MessageOwnerSummary(
                SimOwnerId.parse(row.owner) ?: throw MessageStorageException(),
                row.conversationCount, row.messageCount, row.unreadCount,
            ) },
            if (rows.size > MESSAGE_HISTORY_PAGE_SIZE) MessageOwnerCursor(visible.last().owner) else null,
        )
    }

    override suspend fun summaries(request: MessageSummaryRequest): MessageSummaryPage {
        val query = boundedMessageSearchQuery(request.query).trim()
        request.cursor?.let { require(it.owner == request.owner && it.query == query) { "History cursor scope mismatch" } }
        return historyStorageCall { dao.summaryPage(request.copy(query = query)) }
    }

    override suspend fun thread(identity: StoredMessageConversation, cursor: MessageThreadCursor?): MessageThreadSlice {
        cursor?.let { require(it.identity == identity) { "History cursor scope mismatch" } }
        return historyStorageCall {
            val rows = dao.thread(identity.owner.wire(), identity.key, cursor?.timestampMs, cursor?.messageId)
            val visible = rows.take(MESSAGE_HISTORY_PAGE_SIZE)
            MessageThreadSlice(
                identity,
                visible.asReversed().map(MessageEntity::toRecord),
                if (rows.size > MESSAGE_HISTORY_PAGE_SIZE) visible.last().let {
                    MessageThreadCursor(identity, it.messageAtMs, it.messageId)
                } else null,
            )
        }
    }

    override suspend fun unreadIds(identity: StoredMessageConversation): List<String> = historyStorageCall {
        dao.unreadIds(identity.owner.wire(), identity.key)
    }
}

private suspend fun <T> historyStorageCall(block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    throw MessageStorageException()
}

internal suspend fun collectMessageSummaryPage(
    request: MessageSummaryRequest,
    batch: suspend (MessageSummaryCursor?) -> List<MessageSummaryEntity>,
): MessageSummaryPage {
    val matches = mutableListOf<MessageConversationSummary>()
    var scanned = request.cursor
    while (true) {
        currentCoroutineContext().ensureActive()
        val rows = batch(scanned)
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            val summary = MessageConversationSummary(
                StoredMessageConversation(request.owner, row.conversationKey),
                privateUiPhoneDisplay(row.peerAddress),
                boundedCommunicationText(row.body, "内容不可显示", 80),
                row.messageId, row.messageAtMs,
                if (row.direction == MESSAGE_DIRECTION_OUTGOING) {
                    row.outgoingStatus?.let(OutgoingMessageStatus::valueOf) ?: OutgoingMessageStatus.QUEUED
                } else null,
                row.messageCount, row.unreadCount,
                row.engineStatus?.let(com.vowifi.sw.manager.model.SmsEngineStatus::valueOf),
            )
            if (summary.peerDisplay.contains(request.query, ignoreCase = true) || summary.latestPreview.contains(request.query, ignoreCase = true)) {
                if (matches.size == MESSAGE_HISTORY_PAGE_SIZE) {
                    val last = matches.last()
                    return MessageSummaryPage(matches, MessageSummaryCursor(
                        request.owner, request.query, last.timestampMs, last.latestMessageId, last.identity.key,
                    ))
                }
                matches.add(summary)
            }
            scanned = MessageSummaryCursor(request.owner, request.query, row.messageAtMs, row.messageId, row.conversationKey)
        }
        if (rows.size < 100) return MessageSummaryPage(matches, null)
    }
}
