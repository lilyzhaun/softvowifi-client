package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun MessageHistoryRepository.resolveConversation(raw: String): StoredMessageConversation? {
    val handle = ConversationHandle.parse(raw) ?: return null
    var ownerCursor: MessageOwnerCursor? = null
    do {
        val owners = owners(ownerCursor)
        for (owner in owners.items) {
            var cursor: MessageSummaryCursor? = null
            do {
                currentCoroutineContext().ensureActive()
                val page = summaries(MessageSummaryRequest(owner.owner, cursor = cursor))
                page.items.firstOrNull { it.identity.handle == handle }?.let { return it.identity }
                cursor = page.next
            } while (cursor != null)
        }
        ownerCursor = owners.next
    } while (ownerCursor != null)
    return null
}

internal suspend fun MessageHistoryRepository.refreshThread(identity: StoredMessageConversation, oldest: MessageRecord?): MessageThreadSlice {
    var page = thread(identity)
    val records = page.records.toMutableList()
    while (oldest != null && page.older != null && records.firstOrNull()?.let {
        it.timestampMs > oldest.timestampMs || it.timestampMs == oldest.timestampMs && it.id > oldest.id
    } == true) {
        currentCoroutineContext().ensureActive()
        page = thread(identity, page.older)
        records.addAll(0, page.records)
    }
    val visible = if (oldest == null) records else records.filter {
        it.timestampMs > oldest.timestampMs || it.timestampMs == oldest.timestampMs && it.id >= oldest.id
    }
    if (visible.isEmpty()) return page
    val cursor = if (visible.size < records.size) visible.first().let {
        MessageThreadCursor(identity, it.timestampMs, it.id)
    } else page.older
    return MessageThreadSlice(identity, visible, cursor)
}
