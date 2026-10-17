package com.vowifi.sw.manager.model

/** Opaque identity of one source-owned list selection; never a private record key. */
class HistoryListScope internal constructor()

enum class HistoryChangeReason {
    REPLACE,
    PAGE_MERGE,
    LIVE_INSERT,
    LIVE_MOVE,
    USER_DELETE,
    CONTENT,
}

data class HistoryListChange<Id : Any>(
    val scope: HistoryListScope,
    val version: Long,
    val reason: HistoryChangeReason,
    val ids: Set<Id>,
    val liveInsertedIds: Set<Id> = if (reason == HistoryChangeReason.LIVE_INSERT) ids else emptySet(),
)
