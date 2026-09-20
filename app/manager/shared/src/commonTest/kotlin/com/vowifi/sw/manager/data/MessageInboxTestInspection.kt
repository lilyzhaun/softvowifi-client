package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse

internal data class InspectedMessageInbox(val inbox: MessageUiState.Inbox, val records: List<MessageRecord>) {
    val ownerId get() = inbox.ownerId
    val origin get() = inbox.origin
    val composerEnabled get() = inbox.composerEnabled
    val sendBusy get() = inbox.sendBusy
    val attachmentNotice get() = inbox.attachmentNotice
}

internal suspend fun inspectInboxThreads(source: MessageSource): InspectedMessageInbox {
    val inbox = assertIs<MessageUiState.Inbox>(source.state.value)
    assertFalse(inbox.page.loading)
    val observed = mutableListOf<MessageRecord>()
    try {
        for (summary in inbox.page.items) {
            source.selectConversation(summary.identity.handle.value)
            var window = withTimeout(5_000) {
                source.state.first { it is MessageUiState.Ready && it.window?.identity == summary.identity && !it.window.loading }
            } as MessageUiState.Ready
            assertEquals(inbox.ownerId, window.ownerId)
            observed.addAll(window.records)
            while (window.window?.older != null) {
                val previous = window.records.size
                source.loadOlderMessages()
                window = withTimeout(5_000) {
                    source.state.first { it is MessageUiState.Ready && it.records.size > previous && it.window?.loading == false }
                } as MessageUiState.Ready
                observed.removeAll { PeerNumberNormalizer.normalize(it.peerLabel) == summary.identity.key }
                observed.addAll(window.records)
            }
            assertEquals(summary.messageCount, window.records.size.toLong())
            assertEquals(summary.unreadCount, window.records.count { it is MessageRecord.Incoming && !it.isRead }.toLong())
            assertEquals(summary.latestMessageId, window.records.last().id)
        }
    } finally { source.selectConversation(null) }
    return InspectedMessageInbox(inbox, observed.sortedWith(compareBy<MessageRecord> { it.timestampMs }.thenBy { it.id }))
}
