package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallHistoryRecordId
import com.vowifi.sw.manager.model.CallHistoryDeleteResult
import com.vowifi.sw.manager.model.CallHistoryViewSnapshot
import com.vowifi.sw.manager.model.HistoryChangeReason
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

internal class CallHistoryStore(
    internal val repository: CallHistoryRepository?,
    parentContext: CoroutineContext,
    clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    internal val canClear: () -> Boolean = { true },
    internal val lineLabels: suspend (List<SimOwnerId>) -> Map<SimOwnerId, String> = { emptyMap() },
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(parentContext.minusKey(Job) + job)
    private val writes = Channel<CallHistoryEffect>(16)
    private val selections = Channel<Unit>(Channel.CONFLATED)
    private val clears = Channel<CallHistoryState.Ready>(Channel.CONFLATED)
    internal val deletions = Channel<CallHistoryDeletion>(1)
    internal val snapshot = MutableStateFlow(
        CallHistorySnapshot(null, if (repository == null) CallHistoryState.Empty else CallHistoryState.Loading, emptyList()),
    )
    val state: StateFlow<CallHistoryState> = CallHistoryProjection(snapshot) { it.view }
    val owners: StateFlow<List<CallHistoryOwnerChoice>> = CallHistoryProjection(snapshot) { it.owners }
    val historySnapshot: StateFlow<CallHistoryViewSnapshot> = CallHistoryProjection(snapshot) {
        CallHistoryViewSnapshot(it.view, it.owners, it.change, it.deletePending)
    }
    internal var owner: CallHistorySelection? = null
    internal val selectionLock = Any()
    internal var pendingDeletion: CallHistoryDeletion? = null
    internal var changeVersion = 0L
    internal var closing = false
    private var explicitHistorySelection = false
    private val recoveryTime = clock()
    internal val worker = scope.launch {
        try {
            repository?.interruptOpen(recoveryTime)
            val storedOwner = repository?.lastOwner()
            val recoveredSelection = storedOwner?.let { CallHistorySelection(it) }
            val selected = snapshot.updateAndGet { current ->
                if (current.selection == null) current.copy(selection = recoveredSelection) else current
            }.selection
            owner = selected
            if (selected == null) publishState(CallHistoryState.Empty, null) else {
                repository?.selectOwner(selected.owner)
                publish(selected)
            }
            while (processNext()) {}
        } catch (error: CancellationException) {
            throw error
        } catch (_: CallHistoryStorageException) {
            fail()
        } finally {
            synchronized(selectionLock) {
                closing = true
                pendingDeletion?.let { finishDelete(it, if (it.started) CallHistoryDeleteResult.UNCONFIRMED
                    else CallHistoryDeleteResult.UNAVAILABLE) }
                snapshot.value.selection?.completion?.cancel()
            }
            writes.cancel()
            selections.cancel()
            clears.cancel()
            deletions.cancel()
        }
    }

    fun write(effect: CallHistoryEffect) {
        if (writes.trySend(effect).isFailure) fail()
    }

    fun select(owner: SimOwnerId, outgoingSelection: Boolean = false) {
        synchronized(selectionLock) {
            if (closing) return
            if (outgoingSelection) explicitHistorySelection = false
            if (explicitHistorySelection && snapshot.value.selection?.owner != owner) return
            val selected = CallHistorySelection(owner)
            snapshot.update {
                it.selection?.completion?.complete(false)
                it.copy(selection = selected, view = CallHistoryState.Loading, change = null, deletePending = emptySet())
            }
            if (selections.trySend(Unit).isFailure) fail()
        }
    }

    fun clear(expected: CallHistoryState.Ready) {
        if (clears.trySend(expected).isFailure) fail()
    }

    fun delete(expected: CallHistoryState.Ready, ids: Set<CallHistoryRecordId>) {
        requestDelete(expected, ids)
    }
    suspend fun deleteAndAwait(expected: CallHistoryState.Ready, ids: Set<CallHistoryRecordId>) = requestDelete(expected, ids).await()

    fun selectHistory(choice: CallHistoryOwnerChoice) {
        requestHistory(choice)
    }
    suspend fun selectHistoryAndAwait(choice: CallHistoryOwnerChoice) = requestHistory(choice).await()

    fun requestHistory(choice: CallHistoryOwnerChoice): CompletableDeferred<Boolean> = synchronized(selectionLock) {
        if (closing || repository == null || !worker.isActive) return@synchronized CompletableDeferred(false)
        val completion = CompletableDeferred<Boolean>()
        val selected = CallHistorySelection(choice.owner, completion)
        while (true) {
            val current = snapshot.value
            if (current.owners.none { it === choice }) return@synchronized CompletableDeferred(false)
            if (snapshot.compareAndSet(current, current.copy(selection = selected, view = CallHistoryState.Loading,
                    change = null, deletePending = emptySet()))) {
                current.selection?.completion?.complete(false)
                break
            }
        }
        explicitHistorySelection = true
        if (selections.trySend(Unit).isFailure) {
            completion.complete(false)
            fail()
        }
        completion
    }

    fun fail() {
        snapshot.update { current ->
            if (current.view is CallHistoryState.Failure) current
            else current.copy(view = CallHistoryState.Failure(CALL_HISTORY_STORAGE_NOTICE))
        }
    }

    fun close() {
        synchronized(selectionLock) {
            if (closing) {
                job.complete()
                return
            }
            closing = true
            writes.close()
        }
        scope.launch {
            if (withTimeoutOrNull(5_000) { worker.join(); true } != true) {
                fail()
                worker.cancel()
            }
            job.complete()
        }
    }

    private suspend fun processNext(): Boolean = select {
        writes.onReceiveCatching { received ->
            val effect = received.getOrNull()
            if (effect == null) {
                false
            } else {
                repository?.upsert(effect.owner, effect.write)
                if (owner == null && snapshot.value.selection == null) {
                    val selected = CallHistorySelection(effect.owner)
                    snapshot.update { if (it.selection == null) it.copy(selection = selected) else it }
                    owner = snapshot.value.selection
                    owner?.let { repository?.selectOwner(it.owner) }
                }
                owner?.let { publish(it, reason = HistoryChangeReason.LIVE_INSERT,
                    ids = if (it.owner == effect.owner) setOfNotNull(effect.write.toRecord(effect.owner).recordId) else emptySet()) }
                true
            }
        }
        selections.onReceive {
            val selected = snapshot.value.selection
            if (selected != null && owner !== selected) {
                try {
                    if (owner?.owner != selected.owner) repository?.selectOwner(selected.owner)
                    owner = selected
                    publishState(CallHistoryState.Loading, selected)
                    publish(selected)
                    selected.completion?.complete(snapshot.value.selection === selected && snapshot.value.view !is CallHistoryState.Failure)
                } catch (error: CallHistoryStorageException) {
                    selected.completion?.complete(false)
                    throw error
                }
            }
            true
        }
        clears.onReceive { expected ->
            val current = snapshot.value
            val selected = owner?.takeIf { current.selection === it && current.view === expected }
            if (selected != null && canClear()) {
                repository?.clearByOwner(selected.owner)
                publish(selected, reason = HistoryChangeReason.USER_DELETE, ids = expected.records.mapNotNull { it.recordId }.toSet())
            }
            true
        }
        deletions.onReceive { request ->
            processDelete(request)
            true
        }
    }

    private fun publishState(next: CallHistoryState, selected: CallHistorySelection?) {
        snapshot.update { current ->
            if (current.selection !== selected || current.view is CallHistoryState.Failure) current
            else current.copy(view = if (current.view == next) current.view else next)
        }
    }
}
