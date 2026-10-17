package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallHistoryDeleteResult
import com.vowifi.sw.manager.model.CallHistoryRecordId
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.HistoryChangeReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException

internal class CallHistoryDeletion(
    val selection: CallHistorySelection,
    val expected: CallHistoryState.Ready,
    val ids: Set<CallHistoryRecordId>,
) {
    val completion = CompletableDeferred<CallHistoryDeleteResult>()
    var started = false
}

internal fun CallHistoryStore.requestDelete(expected: CallHistoryState.Ready, ids: Set<CallHistoryRecordId>) =
    synchronized(selectionLock) {
        val current = snapshot.value
        when {
            closing || !worker.isActive || repository == null -> CompletableDeferred(CallHistoryDeleteResult.UNAVAILABLE)
            pendingDeletion != null || current.selection !== owner || current.view !== expected || !canClear() ||
                ids.isEmpty() || !expected.records.mapNotNull { it.recordId }.containsAll(ids) ->
                CompletableDeferred(CallHistoryDeleteResult.REJECTED)
            else -> {
                val request = CallHistoryDeletion(requireNotNull(current.selection), expected, ids.toSet())
                pendingDeletion = request
                val admitted = snapshot.updateAndGet { latest ->
                    if (latest.selection === request.selection && latest.view === expected) latest.copy(deletePending = request.ids)
                    else latest
                }
                if (admitted.selection !== request.selection || admitted.view !== expected)
                    finishDelete(request, CallHistoryDeleteResult.REJECTED)
                else if (deletions.trySend(request).isFailure) finishDelete(request, CallHistoryDeleteResult.UNAVAILABLE)
                request.completion
            }
        }
    }

internal suspend fun CallHistoryStore.processDelete(request: CallHistoryDeletion) {
    var result = CallHistoryDeleteResult.REJECTED
    try {
        val accepted = synchronized(selectionLock) {
            val current = snapshot.value
            (current.selection === request.selection && owner === request.selection &&
                current.view === request.expected && canClear()).also { request.started = it }
        }
        if (!accepted) return
        try {
            requireNotNull(repository).deleteByIds(request.selection.owner, request.ids)
        } catch (_: CallHistoryStorageException) {
            result = CallHistoryDeleteResult.FAILED
            return
        }
        result = CallHistoryDeleteResult.UNCONFIRMED
        try {
            val records = requireNotNull(repository).listByOwner(request.selection.owner)
            if (records.any { it.recordId in request.ids }) return
            publish(request.selection, records, HistoryChangeReason.USER_DELETE, request.ids)
            result = CallHistoryDeleteResult.COMMITTED
        } catch (_: CallHistoryStorageException) {
            // The write returned, but its readback is not confirmed. Keep the confirmed view.
        }
    } catch (cancelled: CancellationException) {
        result = if (request.started) CallHistoryDeleteResult.UNCONFIRMED else CallHistoryDeleteResult.UNAVAILABLE
        throw cancelled
    } finally {
        finishDelete(request, if (result == CallHistoryDeleteResult.COMMITTED || result == CallHistoryDeleteResult.FAILED ||
            kotlinx.coroutines.currentCoroutineContext().isActive) result
            else if (request.started) CallHistoryDeleteResult.UNCONFIRMED else CallHistoryDeleteResult.UNAVAILABLE)
    }
}

internal fun CallHistoryStore.finishDelete(request: CallHistoryDeletion, result: CallHistoryDeleteResult) {
    synchronized(selectionLock) {
        if (pendingDeletion === request) {
            pendingDeletion = null
            snapshot.update { it.copy(deletePending = emptySet()) }
        }
        request.completion.complete(result)
    }
}
