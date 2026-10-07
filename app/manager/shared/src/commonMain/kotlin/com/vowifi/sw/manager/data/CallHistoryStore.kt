package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallHistoryRecordId
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val repository: CallHistoryRepository?,
    parentContext: CoroutineContext,
    clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val canClear: () -> Boolean = { true },
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(parentContext.minusKey(Job) + job)
    private val writes = Channel<CallHistoryEffect>(16)
    private val selections = Channel<Unit>(Channel.CONFLATED)
    private val clears = Channel<CallHistoryState.Ready>(Channel.CONFLATED)
    private val deletions = Channel<Pair<CallHistoryState.Ready, Set<CallHistoryRecordId>>>(Channel.CONFLATED)
    private val snapshot = MutableStateFlow(
        CallHistorySnapshot(null, if (repository == null) CallHistoryState.Empty else CallHistoryState.Loading, emptyList()),
    )
    val state: StateFlow<CallHistoryState> = CallHistoryProjection(snapshot) { it.view }
    val owners: StateFlow<List<CallHistoryOwnerChoice>> = CallHistoryProjection(snapshot) { it.owners }
    private var owner: CallHistorySelection? = null
    private val selectionLock = Any()
    private var explicitHistorySelection = false
    private val recoveryTime = clock()
    private val worker = scope.launch {
        try {
            repository?.interruptOpen(recoveryTime)
            val storedOwner = repository?.lastOwner()
            val recoveredSelection = storedOwner?.let(::CallHistorySelection)
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
            if (outgoingSelection) explicitHistorySelection = false
            if (explicitHistorySelection && snapshot.value.selection?.owner != owner) return
            val selected = CallHistorySelection(owner)
            snapshot.update { it.copy(selection = selected, view = CallHistoryState.Loading) }
            if (selections.trySend(Unit).isFailure) fail()
        }
    }

    fun clear(expected: CallHistoryState.Ready) {
        if (clears.trySend(expected).isFailure) fail()
    }

    fun delete(expected: CallHistoryState.Ready, ids: Set<CallHistoryRecordId>) {
        if (ids.isEmpty() || !expected.records.mapNotNull { it.recordId }.containsAll(ids)) return
        if (deletions.trySend(expected to ids).isFailure) fail()
    }

    fun selectHistory(choice: CallHistoryOwnerChoice) {
        synchronized(selectionLock) {
            val selected = CallHistorySelection(choice.owner)
            while (true) {
                val current = snapshot.value
                if (current.owners.none { it === choice }) return
                if (snapshot.compareAndSet(current, current.copy(selection = selected, view = CallHistoryState.Loading))) break
            }
            explicitHistorySelection = true
            if (selections.trySend(Unit).isFailure) fail()
        }
    }

    fun fail() {
        snapshot.update { current ->
            if (current.view is CallHistoryState.Failure) current
            else current.copy(view = CallHistoryState.Failure(CALL_HISTORY_STORAGE_NOTICE))
        }
    }

    fun close() {
        if (!writes.close()) {
            job.complete()
            return
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
                owner?.let { publish(it) }
                true
            }
        }
        selections.onReceive {
            val selected = snapshot.value.selection
            if (selected != null && owner !== selected) {
                if (owner?.owner != selected.owner) repository?.selectOwner(selected.owner)
                owner = selected
                publishState(CallHistoryState.Loading, selected)
                publish(selected)
            }
            true
        }
        clears.onReceive { expected ->
            val current = snapshot.value
            val selected = owner?.takeIf { current.selection === it && current.view === expected }
            if (selected != null && canClear()) {
                repository?.clearByOwner(selected.owner)
                publish(selected)
            }
            true
        }
        deletions.onReceive { (expected, ids) ->
            val current = snapshot.value
            val selected = owner?.takeIf { current.selection === it && current.view === expected }
            if (selected != null && canClear()) {
                repository?.deleteByIds(selected.owner, ids)
                publish(selected)
            }
            true
        }
    }

    private suspend fun publish(selected: CallHistorySelection) {
        val records = repository?.listByOwner(selected.owner).orEmpty()
        val available = (repository?.listOwners().orEmpty() + selected.owner).distinct().sortedBy { it.wire() }
        val choices = available.mapIndexed { index, candidate ->
            CallHistoryOwnerChoice(candidate, "历史线路 ${index + 1}", candidate == selected.owner)
        }
        val next = if (records.isEmpty()) CallHistoryState.Empty else CallHistoryState.Ready(records, selected.owner)
        snapshot.update { current ->
            if (current.selection !== selected || current.view is CallHistoryState.Failure) current
            else current.copy(
                view = if (current.view == next) current.view else next,
                owners = choices,
            )
        }
    }

    private fun publishState(next: CallHistoryState, selected: CallHistorySelection?) {
        snapshot.update { current ->
            if (current.selection !== selected || current.view is CallHistoryState.Failure) current
            else current.copy(view = if (current.view == next) current.view else next)
        }
    }
}
