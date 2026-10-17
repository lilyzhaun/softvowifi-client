package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallHistoryRecordId
import com.vowifi.sw.manager.model.CallRecord
import com.vowifi.sw.manager.model.HistoryChangeReason
import com.vowifi.sw.manager.model.HistoryListChange
import kotlinx.coroutines.flow.update

internal suspend fun CallHistoryStore.publish(
    selected: CallHistorySelection,
    records: List<CallRecord>? = null,
    reason: HistoryChangeReason = HistoryChangeReason.CONTENT,
    ids: Set<CallHistoryRecordId> = emptySet(),
) {
    val confirmedRecords = records ?: repository?.listByOwner(selected.owner).orEmpty()
    val available = (repository?.listOwners().orEmpty() + selected.owner).distinct().sortedBy { it.wire() }
    val labels = lineLabels(available)
    val choices = available.mapIndexed { index, candidate ->
        CallHistoryOwnerChoice(candidate, labels[candidate] ?: "历史线路 ${index + 1}", candidate == selected.owner)
    }
    val next = if (confirmedRecords.isEmpty()) CallHistoryState.Empty else CallHistoryState.Ready(confirmedRecords, selected.owner)
    val version = ++changeVersion
    snapshot.update { current ->
        if (current.selection !== selected || current.view is CallHistoryState.Failure) current
        else {
            val previous = (current.view as? CallHistoryState.Ready)?.records.orEmpty()
            val replacement = current.change?.scope !== selected.scope
            val effective = when {
                replacement -> HistoryChangeReason.REPLACE
                reason != HistoryChangeReason.LIVE_INSERT -> reason
                confirmedRecords.any { it.recordId in ids && previous.none { old -> old.recordId == it.recordId } } -> reason
                confirmedRecords.withIndex().any { (index, record) -> record.recordId in ids &&
                    previous.indexOfFirst { it.recordId == record.recordId } != index } -> HistoryChangeReason.LIVE_MOVE
                else -> HistoryChangeReason.CONTENT
            }
            current.copy(
                view = if (current.view == next) current.view else next,
                owners = choices,
                change = if (!replacement && current.view == next) current.change else HistoryListChange(
                    selected.scope, version, effective,
                    if (replacement) confirmedRecords.mapNotNull { it.recordId }.toSet() else ids),
                deletePending = if (reason == HistoryChangeReason.USER_DELETE) emptySet() else current.deletePending,
            )
        }
    }
}
