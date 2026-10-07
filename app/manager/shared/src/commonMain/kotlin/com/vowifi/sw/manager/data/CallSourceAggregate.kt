package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

class CallSourceAggregate(
    private val runtime: EngineMessageRuntime,
    audio: CallAudioController,
    private val control: CallControlTransport,
    parentContext: CoroutineContext,
    repository: CallHistoryRepository,
    private val ownerIdFor: (String) -> SimOwnerId?,
    nextCorrelation: () -> String,
    private val lineLabel: (String) -> String = { "SIM" },
) : CallSource {
    private val scope = CoroutineScope(parentContext.minusKey(Job) + SupervisorJob())
    private val rosterMutex = Mutex()
    internal val lock = Any()
    private val audio = CallSessionAudio(audio)
    private val recorder = CallHistoryRecorder(CallHistoryBinding(repository, ownerIdFor, nextCorrelation), runtime.clock, parentContext)
    internal val mutableState = MutableStateFlow<CallUiState>(CallUiState.Loading)
    internal val mutableLines = MutableStateFlow(CallLineState())
    override val state = mutableState.asStateFlow()
    override val lines = mutableLines.asStateFlow()
    override val history = recorder.state
    override val historyOwners = recorder.owners
    @Volatile internal var children = emptyList<CallLineChild>()
    @Volatile internal var closed = false
    internal var roster: List<SimOwnerId?>? = null
    internal var labels = emptyMap<SimOwnerId, String>()
    internal var inventoryReady = false
    internal var selected: SimOwnerId? = null
    internal var explicitSelection = false
    private var alignedHistoryOwner: SimOwnerId? = null
    internal var presented: CallLineChild? = null
    internal var preparing: CallBinding? = null
    private var reconciliation: Pair<CallLineChild, CallBinding?>? = null

    init { refresh() }
    override fun refresh() { launch { updateRoster(refreshChildren = true) } }
    internal fun refreshRoster() { launch { updateRoster(refreshChildren = false) } }
    private fun launch(block: suspend () -> Unit) { if (!closed) scope.launch { block() } }

    private suspend fun updateRoster(refreshChildren: Boolean) {
        if (!rosterMutex.tryLock()) return
        try { readRoster(refreshChildren) } finally { rosterMutex.unlock() }
    }

    private suspend fun readRoster(refreshChildren: Boolean) {
        val instances: List<EngineInstanceState>
        val active: List<String?>?
        try { instances = runtime.listInstances(); active = runtime.activeIccids() }
        catch (failure: CancellationException) { throw failure }
        catch (_: Exception) {
            synchronized(lock) { inventoryReady = false; roster = null }
            publish()
            return
        }
        val normalized = active?.map { IccidNormalizer.normalize(it) }
        val owners = normalized?.map { it?.let(ownerIdFor) }
        val running = instances.filter { it.lifecycle == EngineLifecycle.RUNNING }.distinctBy { it.iccid }
        val retained = children.filter { old -> running.any { it.iccid == old.instance.iccid && it.pid == old.instance.pid } }
        val retiring = children - retained.toSet()
        synchronized(lock) { children = retained }
        retiring.forEach { it.observer?.cancel(); it.source.close() }
        if (closed) return
        val added = running.filter { instance -> retained.none { it.instance.iccid == instance.iccid } }.mapNotNull { instance ->
            val owner = IccidNormalizer.normalize(instance.iccid)?.let(ownerIdFor) ?: return@mapNotNull null
            val transport = runtime.transportFor(instance.iccid)
            val child = EngineCallSource(
                EngineMessageRuntime({ runtime.listInstances().filter { it.iccid == instance.iccid && it.pid == instance.pid } },
                    { transport }, runtime.clock), audio.child(), scope.coroutineContext,
                control = control, sharedRecorder = recorder,
            )
            CallLineChild(instance, owner, child)
        }
        val nextLabels = (normalized.orEmpty().filterNotNull() + running.map { it.iccid }).associate { raw ->
            ownerIdFor(raw) to lineLabel(raw)
        }.mapNotNull { (owner, label) -> owner?.let { it to label } }.toMap()
        val installed = synchronized(lock) {
            if (closed) return@synchronized false
            children = retained + added
            roster = owners
            labels = nextLabels
            inventoryReady = true
            true
        }
        if (!installed) { added.forEach { it.source.close() }; return }
        added.forEach { child -> child.observer = scope.launch { child.source.state.collect { publish() } } }
        publish()
        retained.forEach { if (refreshChildren) it.source.refresh() else it.source.refreshAvailability() }
    }

    internal fun publish() {
        val reconcile = synchronized(lock) {
            if (closed) return
            publishCallLines()
            if (selected != alignedHistoryOwner) {
                alignedHistoryOwner = selected
                selected?.let(recorder::refreshOwner)
            }
            val previous = presented
            val active = children.filter { it.ready()?.phase?.let { phase -> phase != CallPhase.Idle } == true }
            val current = previous?.takeIf { it in active } ?: active.firstOrNull()
            presented = current
            mutableState.value = current?.source?.state?.value
                ?: children.singleOrNull { it.owner == selected }?.source?.state?.value
                ?: CallUiState.Unavailable(if (selected == null) "请选择通话线路" else "所选线路暂不可用")
            val conflict = current?.takeIf { active.size > 1 }
            val key = conflict?.let { it to active.first { candidate -> candidate !== it }.ready()?.binding }
            if (key == null) { reconciliation = null; null }
            else if (key == reconciliation) null else { reconciliation = key; conflict }
        }
        reconcile?.source?.refresh()
    }

    override fun selectLine(owner: SimOwnerId) {
        synchronized(lock) {
            if (closed || lines.value.choices.none { it.owner == owner && it.running }) return
            selected = owner
            explicitSelection = true
        }
        recorder.selectOutgoingOwner(owner)
        publish()
    }

    internal fun resolve(expected: CallBinding): EngineCallSource? = children
        .firstOrNull { !closed && !it.source.closed && it.ready()?.binding === expected }?.source

    internal fun prepare(expected: CallBinding, dial: Boolean): EngineCallSource? = synchronized(lock) {
        if (preparing != null || !inventoryReady) return@synchronized null
        val child = resolve(expected) ?: return@synchronized null
        if (dial && lines.value.outgoingBinding !== expected || !dial && (state.value as? CallUiState.Ready)?.binding !== expected) return@synchronized null
        if (children.any { it.source !== child && it.ready()?.phase?.let { phase -> phase != CallPhase.Idle } == true }) return@synchronized null
        preparing = expected
        child
    }

    internal fun permitsPrepared(expected: CallBinding, child: EngineCallSource, dial: Boolean): Boolean = synchronized(lock) {
        val line = children.firstOrNull { it.source === child } ?: return@synchronized false
        !closed && preparing === expected && child.binding === expected && child.sync == CallSyncState.Ready &&
            (!dial || inventoryReady && roster != null && line.owner in roster.orEmpty()) &&
            children.none { it.source !== child && it.ready()?.phase?.let { phase -> phase != CallPhase.Idle } == true }
    }

    internal fun finishPreparation(expected: CallBinding) {
        synchronized(lock) { if (preparing === expected) preparing = null }
        publish()
    }

    override fun dial(number: String) { lines.value.outgoingBinding?.let { dial(it, number) } }
    override fun dial(expected: CallBinding, number: String) {
        val child = prepare(expected, dial = true) ?: return
        child.scope.launch {
            child.performDial(expected, number) { permitsPrepared(expected, child, dial = true) }
        }.invokeOnCompletion { finishPreparation(expected) }
    }
    override fun act(expected: CallBinding, action: CallAction) {
        if (action != CallAction.Answer) { resolve(expected)?.act(expected, action); return }
        val child = prepare(expected, dial = false) ?: return
        child.scope.launch {
            child.performControl(expected, action) { permitsPrepared(expected, child, dial = false) }
        }.invokeOnCompletion { finishPreparation(expected) }
    }
    override fun sendDtmf(expected: CallBinding, digit: Char) { resolve(expected)?.sendDtmf(expected, digit) }
    private fun current(action: CallAction) { (state.value as? CallUiState.Ready)?.binding?.let { act(it, action) } }
    override fun answer() = current(CallAction.Answer)
    override fun reject() = current(CallAction.Reject)
    override fun hangup() = current(CallAction.Hangup)
    override fun cancel() = current(CallAction.Cancel)
    override fun selectHistoryOwner(choice: CallHistoryOwnerChoice) = recorder.selectHistory(choice)
    override fun clearHistory(expected: CallHistoryState.Ready) = recorder.clear(expected)
    override fun deleteHistory(expected: CallHistoryState.Ready, ids: Set<CallHistoryRecordId>) = recorder.delete(expected, ids)
    override fun close() {
        val retiring = synchronized(lock) {
            if (closed) return
            closed = true
            children.also { children = emptyList() }
        }
        retiring.forEach { it.observer?.cancel(); it.source.close() }
        audio.close()
        recorder.close()
        scope.cancel()
    }
}

internal class CallLineChild(val instance: EngineInstanceState, val owner: SimOwnerId, val source: EngineCallSource) {
    var observer: Job? = null
    fun ready(): CallUiState.Ready? = source.state.value as? CallUiState.Ready
}
