package com.vowifi.sw.manager.model

import com.vowifi.sw.manager.data.CallHistoryOwnerChoice

/** The view is the original confirmed object, including its expected-reference identity. */
data class CallHistoryViewSnapshot(
    val view: CallHistoryState,
    val owners: List<CallHistoryOwnerChoice>,
    val change: HistoryListChange<CallHistoryRecordId>? = null,
    val deletePending: Set<CallHistoryRecordId> = emptySet(),
)

enum class CallHistoryDeleteResult {
    COMMITTED,
    REJECTED,
    FAILED,
    UNCONFIRMED,
    UNAVAILABLE,
}
