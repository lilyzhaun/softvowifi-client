package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.ConversationHandle

/** One already persisted mutation, carried to the existing readback; not an event queue. */
internal data class MessageHistoryMutation(
    val identity: StoredMessageConversation,
    val reason: HistoryChangeReason,
    val ids: Set<String> = emptySet(),
)

internal fun inboxHistoryChange(
    previous: MessageInboxPage, items: List<MessageConversationSummary>, owner: SimOwnerId,
    scope: HistoryListScope, version: Long, append: Boolean, mutation: MessageHistoryMutation?,
): HistoryListChange<ConversationHandle>? {
    val old = previous.change
    val replacement = old?.scope !== scope
    if (!replacement && !append && previous.items == items) return old
    val before = previous.items.map { it.identity.handle }
    val after = items.map { it.identity.handle }
    val target = mutation?.takeIf { it.identity.owner == owner }?.identity?.handle
    val reason = when {
        replacement -> HistoryChangeReason.REPLACE
        append -> HistoryChangeReason.PAGE_MERGE
        target == null -> HistoryChangeReason.CONTENT
        mutation?.reason == HistoryChangeReason.USER_DELETE && target in before && target !in after -> HistoryChangeReason.USER_DELETE
        mutation?.reason == HistoryChangeReason.LIVE_INSERT && target !in before && target in after -> HistoryChangeReason.LIVE_INSERT
        mutation?.reason in setOf(HistoryChangeReason.LIVE_INSERT, HistoryChangeReason.LIVE_MOVE) &&
            target in before && target in after && before.indexOf(target) != after.indexOf(target) -> HistoryChangeReason.LIVE_MOVE
        else -> HistoryChangeReason.CONTENT
    }
    val ids = when {
        replacement -> after.toSet()
        append -> (after - before.toSet()).toSet()
        target != null && (target in before || target in after) -> setOf(target)
        else -> items.filter { item -> previous.items.none { it == item } }.map { it.identity.handle }.toSet()
    }
    return HistoryListChange(scope, version, reason, ids)
}

internal fun threadHistoryChange(
    previous: MessageUiState.Ready?, records: List<MessageRecord>, identity: StoredMessageConversation,
    scope: HistoryListScope, version: Long, append: Boolean, mutation: MessageHistoryMutation?,
): HistoryListChange<String>? {
    val old = previous?.window?.change
    val replacement = old?.scope !== scope
    if (!replacement && !append && previous?.records == records) return old
    val before = previous?.records.orEmpty().map { it.id }.toSet()
    val after = records.map { it.id }.toSet()
    val affected = mutation?.takeIf { it.identity == identity }?.ids.orEmpty()
    val reason = when {
        replacement -> HistoryChangeReason.REPLACE
        append -> HistoryChangeReason.PAGE_MERGE
        mutation?.reason == HistoryChangeReason.LIVE_INSERT && affected.any { it in after && it !in before } -> HistoryChangeReason.LIVE_INSERT
        mutation?.reason == HistoryChangeReason.USER_DELETE && affected.any { it in before && it !in after } -> HistoryChangeReason.USER_DELETE
        else -> HistoryChangeReason.CONTENT
    }
    val ids = when (reason) {
        HistoryChangeReason.REPLACE -> after
        HistoryChangeReason.PAGE_MERGE -> after - before
        HistoryChangeReason.LIVE_INSERT, HistoryChangeReason.USER_DELETE -> affected
        else -> records.filter { record -> previous?.records.orEmpty().none { it == record } }.map { it.id }.toSet()
    }
    val carried = if (!replacement && previous?.window?.identity == identity) old?.liveInsertedIds.orEmpty() else emptySet()
    val inserted = if (reason == HistoryChangeReason.LIVE_INSERT) ids else emptySet()
    return HistoryListChange(scope, version, reason, ids, (carried + inserted).intersect(after))
}
