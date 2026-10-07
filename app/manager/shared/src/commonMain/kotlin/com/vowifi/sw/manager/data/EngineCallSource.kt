package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EngineCallSource @JvmOverloads internal constructor(
    internal val runtime: EngineMessageRuntime,
    audio: CallAudioController,
    parentContext: CoroutineContext,
    history: CallHistoryBinding? = null,
    internal val control: CallControlTransport,
    internal val monotonicNow: () -> Long = CallSessionClock.monotonic(),
    sharedRecorder: CallHistoryRecorder? = null,
) : CallSource {
    internal val audio = if (audio is CallSessionAudio.Scoped) audio else CallSessionAudio(audio).child()
    internal val scope = CoroutineScope(parentContext.minusKey(Job) + SupervisorJob())
    internal val mutex = Mutex()
    private val audioMutex = Mutex()
    private val mutableState = MutableStateFlow<CallUiState>(CallUiState.Loading)
    override val state = mutableState.asStateFlow()
    private val ownsRecorder = sharedRecorder == null
    internal val recorder = sharedRecorder ?: CallHistoryRecorder(history, runtime.clock, parentContext)
    override val history = recorder.state
    override val historyOwners get() = recorder.owners
    @Volatile internal var closed = false
    internal var activeIccid: String? = null
    internal var activeTransport: EngineMessageTransport? = null
    internal var eventJob: Job? = null
    internal var failureJob: Job? = null
    internal var recoveryJob: Job? = null
    internal var connectionWork = 0
    internal var recoveryAttempt = false
    internal var requiredSnapshot: EngineMessageTransport? = null
    internal var session = CallSession()
    internal var generation = 0L
    internal var connectionGeneration = 0L
    internal var snapshotRequest = 0L
    internal var refreshRequest = 0L
    internal var bindingGeneration = 0L
    internal var version: CallVersion? = null
    internal var sync = CallSyncState.Recovering
    internal var dialOperation: Long? = null
    internal var controlOperation: Long? = null
    internal var pendingAction: CallAction? = null
    internal var dtmfOperation: Long? = null
    internal var nextOperation = 0L
    internal var dtmfSupported = false
    internal var cancelDispatched = false
    internal var pendingTerminalCause: CallHistoryCause? = null
    internal var clock: CallSessionClock? = null
    internal var binding: CallBinding? = null
    internal val retiredTokens = mutableSetOf<String>()
    internal val retiredEpochs = mutableSetOf<String>()

    init {
        scope.launch { collectAudioEvents() }
        launch { refreshConnection() }
    }
    override fun refresh() = launch { refreshConnection() }
    override fun deleteHistory(expected: CallHistoryState.Ready, ids: Set<CallHistoryRecordId>) = recorder.delete(expected, ids)

    override fun selectHistoryOwner(choice: CallHistoryOwnerChoice) = launch {
        mutex.withLock { if (activeTransport == null && session.phase == CallPhase.Idle) recorder.selectHistory(choice) }
    }
    override fun answer() = actCurrent(CallAction.Answer)
    override fun reject() = actCurrent(CallAction.Reject)
    override fun hangup() = actCurrent(CallAction.Hangup)
    override fun cancel() = actCurrent(CallAction.Cancel)
    private fun actCurrent(action: CallAction) = launch { performCurrentControl(action) }
    override fun act(expected: CallBinding, action: CallAction) = launch { performControl(expected, action) }
    override fun sendDtmf(expected: CallBinding, digit: Char) = launch { performDtmf(expected, digit) }
    override fun dial(number: String) { (state.value as? CallUiState.Ready)?.binding?.let { dial(it, number) } }
    override fun dial(expected: CallBinding, number: String) = launch { performDial(expected, number) }
    override fun clearHistory(expected: CallHistoryState.Ready) = launch {
        mutex.withLock { if (session.phase == CallPhase.Idle) recorder.clear(expected) }
    }
    override fun close() {
        val effect = runBlocking {
            mutex.withLock {
                if (closed) return@withLock null
                closed = true
                detach()
            }
        }
        effect?.history?.let(recorder::enqueue)
        audio.retire()
        if (ownsRecorder) recorder.close()
        scope.cancel()
    }
    internal fun launch(block: suspend () -> Unit) { if (!closed) scope.launch { block() } }
    internal fun owns(expected: CallBinding): Boolean = !closed && binding === expected && sync == CallSyncState.Ready &&
        activeIccid == expected.owner && bindingGeneration == expected.generation &&
        (session.token == expected.token || expected.token == null && session.phase in setOf(CallPhase.Dialing, CallPhase.Canceling))

    internal fun commit(next: CallSession, cause: CallHistoryCause = CallHistoryCause.Session): CallEffects {
        val before = session
        session = next
        val leaving = before.phase != CallPhase.Idle && next.phase == CallPhase.Idle
        val stopPlayback = before.playbackMode() != null && next.playbackMode() == null
        if (before.phase == CallPhase.Idle && next.phase != CallPhase.Idle) {
            generation += 1
            bindingGeneration += 1
        }
        val history = recorder.observe(activeIccid, generation, before, next, cause)
        if (leaving) {
            before.token?.let(retiredTokens::add)
            invalidateOperations()
            dtmfSupported = false
            clock = null
        }
        publish()
        return CallEffects(
            stopAudio = leaving || stopPlayback,
            stopSession = if (leaving || stopPlayback) audioSession(before) else null,
            audio = activeTransport?.let { pendingCallAudio(before, session, it, activeIccid, generation) },
            history = history,
            endedSession = if (leaving && before.token != null && before.audio !is CallAudioState.Failed &&
                (cause is CallHistoryCause.EventEnded || cause == CallHistoryCause.Hangup)) audioSession(before) else null,
        )
    }
    internal fun invalidateOperations() {
        bindingGeneration += 1
        dialOperation = null
        controlOperation = null
        pendingAction = null
        dtmfOperation = null
        binding = null
        cancelDispatched = false
        pendingTerminalCause = null
    }
    internal fun detach(): CallEffects {
        requiredSnapshot = null
        cancelRecovery()
        eventJob?.cancel()
        failureJob?.cancel()
        eventJob = null
        failureJob = null
        (activeTransport as? CallEventTransport)?.close()
        val effects = CallEffects(stopAudio = needsAudioStop(session), stopSession = audioSession(),
            history = recorder.claimInterrupt(activeIccid, generation, session.token))
        activeIccid = null
        activeTransport = null
        session = CallSession()
        connectionGeneration += 1
        snapshotRequest += 1
        version = null
        clock = null
        dtmfSupported = false
        retiredTokens.clear()
        retiredEpochs.clear()
        invalidateOperations()
        return effects
    }
    internal fun unavailable(state: CallUiState): CallEffects {
        if (session.phase != CallPhase.Idle) {
            loseConnection(CallSyncState.Unavailable, CallFailureMessages.SNAPSHOT)
            return CallEffects(stopAudio = true, stopSession = audioSession())
        }
        return detach().also { mutableState.value = state }
    }
    internal fun loseConnection(reason: CallSyncState, message: String) {
        connectionGeneration += 1
        snapshotRequest += 1
        sync = reason
        invalidateOperations()
        session = session.copy(busy = false, operationMessage = message)
        session = CallSessionReducer.audioFailed(session)
        publish()
    }
    internal suspend fun apply(effects: CallEffects) {
        if (effects.endedSession != null) audio.callEnded(effects.endedSession)
        else if (effects.stopAudio) effects.stopSession?.let(audio::stopIf)
        effects.history?.let(recorder::enqueue)
        if (ownsRecorder) effects.historyOwner?.let(recorder::refreshOwner)
        effects.audio?.let { pending ->
            startOwnedAudio(audio, audioMutex, mutex, pending,
                owns = { !closed && sync == CallSyncState.Ready && activeTransport === pending.transport && pending.isLive(session, generation) },
                onFailed = { session = CallSessionReducer.audioFailed(session); publish() },
                onStarted = { session = CallSessionReducer.audioStarted(session); publish() },
            )
        }
    }
    private suspend fun collectAudioEvents() {
        collectOwnedAudioEnded(audio, onEnded = { event ->
            mutex.withLock {
                if (event.session.iccid != activeIccid || event.session.transport !== activeTransport) return@withLock CallEffects()
                audioEndedEffects(closed, session, generation, event) {
                    // Provisional PCM closes during SDP/final-answer handoff. Keep
                    // the prepared call grant; only confirmed audio loss is a failure.
                    session = if (event.session.mode == CallAudioMode.Progress) session.copy(audio = CallAudioState.Idle)
                        else CallSessionReducer.audioFailed(session, CallFailureMessages.AUDIO_INTERRUPTED)
                    publish()
                    CallEffects(stopAudio = true, stopSession = event.session)
                }
            }
        }, apply = ::apply)
    }
    internal fun audioSession(current: CallSession = session): CallAudioSession? {
        val owner = activeIccid ?: return null
        val transport = activeTransport ?: return null
        val progress = current.phase != CallPhase.InCall && (current.ringback || current.earlyMediaRevision > 0)
        return current.token?.let { CallAudioSession(owner, it, generation, transport,
            if (progress) CallAudioMode.Progress else CallAudioMode.Conversation,
            if (progress) current.earlyMediaRevision else 0L, progress && current.ringback) }
    }
    internal fun publish() {
        val owner = activeIccid
        if (owner == null) return
        val current = binding
        if (current == null || current.owner != owner || current.token != session.token || current.generation != bindingGeneration || current.epoch != version?.epoch) {
            binding = CallBinding(owner, session.token, bindingGeneration, version?.epoch)
        }
        mutableState.value = session.toReadyUi().copy(
            binding = binding, direction = session.direction, sync = sync,
            dtmfSupported = dtmfSupported && session.phase == CallPhase.InCall && sync == CallSyncState.Ready,
            dtmfBusy = dtmfOperation != null, controlBusy = controlOperation != null, clock = clock,
            pendingAction = pendingAction, dialBusy = dialOperation != null, cancelPending = session.cancelRequested,
        )
    }

    companion object {
        fun connect(runtime: EngineMessageRuntime, audio: CallAudioController, control: CallControlTransport,
                    parentContext: CoroutineContext): EngineCallSource =
            EngineCallSource(runtime, audio, parentContext, control = control)
    }
}
