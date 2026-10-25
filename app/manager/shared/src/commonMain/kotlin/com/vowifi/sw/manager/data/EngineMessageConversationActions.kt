package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.HistoryChangeReason
import kotlinx.coroutines.sync.withLock

internal suspend fun changeStoredMessageConversation(
    session: EngineMessageSession,
    handle: String,
    action: MessageConversationAction,
): MessageConversationActionResult = session.mutex.withLock {
    if (session.closed) return@withLock MessageConversationActionResult.FAILED
    try {
        val identity = session.repository.history.resolveConversation(handle)
            ?: return@withLock MessageConversationActionResult.MISSING
        if (!session.repository.changeConversation(identity, action)) return@withLock MessageConversationActionResult.MISSING
        session.unreadSummary.refresh()
        if (action == MessageConversationAction.MARK_UNREAD) {
            // Only the persisted reminder changed. Keep the visible list and line chrome in place.
            session.selection.refreshInboxContent(MessageHistoryMutation(identity, HistoryChangeReason.CONTENT))
            return@withLock MessageConversationActionResult.APPLIED
        }
        val records = session.repository.listByOwner(identity.owner)
        session.sessions[identity.owner]?.let { it.records = records }
        (session.fallback as? MessageUiState.Ready)?.takeIf { it.ownerId == identity.owner }?.let {
            session.fallback = it.copy(records = records)
        }
        // Reconcile existing notifications only; a manual reminder is not a new MT.
        session.observer.onThreadUnread(identity, session.repository.history.unreadIds(identity).toSet())
        val mutation = MessageHistoryMutation(identity,
            if (action == MessageConversationAction.DELETE) HistoryChangeReason.USER_DELETE else HistoryChangeReason.CONTENT)
        session.publish(mutation)
        if (action == MessageConversationAction.MARK_READ) session.selection.refreshInboxContent(mutation)
        else session.selection.refreshInbox(mutation)
        MessageConversationActionResult.APPLIED
    } catch (_: MessageStorageException) {
        MessageConversationActionResult.FAILED
    }
}
