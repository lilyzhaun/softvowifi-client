package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject

internal suspend fun EngineCallSource.refreshConnection(recovering: Boolean = false) =
    withConnectionWork(recovering) { refreshSelectedConnection(recovering) }

internal fun EngineCallSource.refreshAvailability() = launch {
    val claimed = mutex.withLock {
        if (closed || connectionWork != 0 || recoveryAttempt || recoveryJob?.isActive == true ||
            dialOperation != null || controlOperation != null ||
            session.phase != CallPhase.Idle && sync == CallSyncState.Ready) false
        else { recoveryAttempt = true; true }
    }
    if (claimed) {
        try { refreshConnection(recovering = true) }
        finally { finishRecoveryAttempt() }
    }
}

private suspend fun EngineCallSource.refreshSelectedConnection(recovering: Boolean) {
    val ticket = mutex.withLock { ++refreshRequest }
    val instances = try { runtime.listInstances() }
    catch (error: CancellationException) { throw error }
    catch (_: MessagePermissionException) {
        apply(mutex.withLock { cancelRecovery(); unavailable(permissionFailure()) }); return
    } catch (_: Exception) {
        if (recovering) {
            apply(mutex.withLock {
                loseConnection(CallSyncState.Unavailable, CallFailureMessages.LIST)
                CallEffects(stopAudio = true, stopSession = audioSession())
            })
            return
        }
        apply(mutex.withLock { unavailable(listFailure()) }); return
    }
    var refresh: EngineMessageTransport? = null
    val effects = mutex.withLock {
        if (closed || ticket != refreshRequest) return@withLock CallEffects()
        when (val selection = ActiveEngineSelector.select(instances)) {
            ActiveEngineSelection.None -> unavailable(CallUiState.Unavailable(ActiveEngineSelector.NONE_MESSAGE))
            ActiveEngineSelection.Multiple -> unavailable(CallUiState.Failure(ActiveEngineSelector.MULTIPLE_MESSAGE))
            is ActiveEngineSelection.Single -> {
                if (activeIccid != null && activeIccid != selection.iccid && session.phase != CallPhase.Idle) {
                    loseConnection(CallSyncState.OwnerChanged, CallFailureMessages.OWNER_CHANGED)
                    cancelRecovery()
                    return@withLock CallEffects(stopAudio = true, stopSession = audioSession())
                }
                if (activeIccid == selection.iccid && activeTransport != null) {
                    refresh = activeTransport
                    return@withLock CallEffects(historyOwner = recorder.ownerFor(activeIccid))
                }
                val old = detach()
                val transport = runtime.transportFor(selection.iccid)
                activeIccid = selection.iccid
                activeTransport = transport
                sync = CallSyncState.Recovering
                publish()
                eventJob = scope.launch { subscribeCalls(transport, recovering) }
                old.copy(historyOwner = recorder.ownerFor(selection.iccid))
            }
        }
    }
    apply(effects)
    refresh?.let { fetchSnapshot(it, recovering) }
}

private suspend fun EngineCallSource.subscribeCalls(transport: EngineMessageTransport, recovering: Boolean) {
    if (transport is CallEventTransport) failureJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        transport.disconnected.collect {
            apply(mutex.withLock {
                if (closed || activeTransport !== transport) return@withLock CallEffects()
                loseConnection(CallSyncState.Recovering, CallFailureMessages.RECOVERING)
                scheduleRecovery(transport)
                CallEffects(stopAudio = true, stopSession = audioSession())
            })
        }
    }
    try {
        transport.events.onSubscription {
            fetchRequiredSnapshot(transport, recovering)
        }.collect { frame -> receiveCallFrame(transport, frame) }
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { connectionFailed(transport) }
}

private suspend fun EngineCallSource.connectionFailed(transport: EngineMessageTransport, retry: Boolean = true) {
    apply(mutex.withLock {
        if (closed || activeTransport !== transport) return@withLock CallEffects()
        if (session.phase == CallPhase.Idle && transport !is CallEventTransport) return@withLock unavailable(connectFailure())
        loseConnection(CallSyncState.Recovering, CallFailureMessages.RECOVERING)
        if (retry) scheduleRecovery(transport)
        CallEffects(stopAudio = true, stopSession = audioSession())
    })
}

internal suspend fun EngineCallSource.fetchSnapshot(transport: EngineMessageTransport, recovering: Boolean = false) =
    withConnectionWork(recovering) { readSnapshot(transport, recovering) }

private suspend fun EngineCallSource.fetchRequiredSnapshot(transport: EngineMessageTransport, background: Boolean = false) =
    withConnectionWork(recovering = false, required = transport) { readSnapshot(transport, background) }

private suspend fun EngineCallSource.readSnapshot(transport: EngineMessageTransport, recovering: Boolean) {
    val request = mutex.withLock {
        if (closed || activeTransport !== transport) return
        SnapshotRequest(activeIccid ?: return, connectionGeneration, ++snapshotRequest, bindingGeneration)
    }
    try { transport.connect() }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { connectionFailed(transport, retry = !recovering); return }
    val response = try {
        control.request(request.owner, "getCallSnapshot", buildJsonObject {}, CallProtocolCodec.CONTROL_TIMEOUT_MILLIS)
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { null }
    val snapshot = response?.let(CallSnapshotCodec::decode)
    val effects = mutex.withLock {
        if (closed || activeTransport !== transport || activeIccid != request.owner ||
            connectionGeneration != request.connection || snapshotRequest != request.id || bindingGeneration != request.binding) return@withLock CallEffects()
        if (snapshot == null) {
            val stop = needsAudioStop(session)
            invalidateOperations()
            sync = if (response?.error?.code == 3002) CallSyncState.Unregistered else CallSyncState.Unavailable
            session = CallSessionReducer.audioFailed(session).copy(busy = false,
                operationMessage = if (sync == CallSyncState.Unregistered) CallFailureMessages.IMS else CallFailureMessages.SNAPSHOT)
            publish()
            if (!recovering) scheduleRecovery(transport)
            return@withLock CallEffects(stopAudio = stop, stopSession = audioSession())
        }
        adoptSnapshot(snapshot, readOnly = recovering)
    }
    apply(if (recovering) effects.copy(audio = null) else effects)
    if (!recovering) dispatchPendingCancel()
}

private class SnapshotRequest(val owner: String, val connection: Long, val id: Long, val binding: Long)

private fun EngineCallSource.adoptSnapshot(snapshot: CallSnapshot, readOnly: Boolean): CallEffects {
    val previous = version
    val previousAudio = audioSession()
    if (previous?.epoch == snapshot.version.epoch && previous.revision > snapshot.version.revision) return CallEffects()
    if (previous == snapshot.version && snapshot.phase == CallPhase.Idle && dialOperation != null &&
        session.token == null && session.phase in setOf(CallPhase.Dialing, CallPhase.Canceling)) return CallEffects()
    val epochChanged = previous != null && previous.epoch != snapshot.version.epoch
    val replaced = session.token != null && snapshot.token != null && session.token != snapshot.token
    var interrupted: CallHistoryEffect? = null
    if (epochChanged || replaced) {
        interrupted = recorder.claimInterrupt(activeIccid, generation, session.token)
        session.token?.let(retiredTokens::add)
        session = CallSession()
        invalidateOperations()
        clock = null
        if (epochChanged) {
            retiredEpochs.add(previous.epoch)
            retiredEpochs.remove(snapshot.version.epoch)
        }
    }
    val operationMessage = session.operationMessage.takeUnless { sync == CallSyncState.Unregistered }
    version = snapshot.version
    sync = CallSyncState.Ready
    if (!recoveryAttempt) cancelRecovery()
    val continuing = session.token == snapshot.token && !epochChanged && !replaced
    val failedCancel = continuing && snapshot.phase == CallPhase.Dialing && session.cancelRequested &&
        cancelDispatched && controlOperation == null && session.operationMessage != null
    if (failedCancel) cancelDispatched = false
    val cancel = snapshot.phase == CallPhase.Canceling || continuing && session.cancelRequested && !failedCancel && snapshot.phase == CallPhase.Dialing
    if (snapshot.phase == CallPhase.Canceling) cancelDispatched = true
    val phase = if (cancel) CallPhase.Canceling else snapshot.phase
    val audioState = if (continuing) session.audio else CallAudioState.Idle
    val next = if (phase == CallPhase.Idle) CallSession(operationMessage = operationMessage) else CallSession(
        token = snapshot.token, phase = phase,
        audio = if (readOnly && phase == CallPhase.InCall && audioState == CallAudioState.Idle)
            CallAudioState.Failed(CallFailureMessages.AUDIO_INTERRUPTED) else audioState,
        busy = controlOperation != null || dialOperation != null,
        peer = snapshot.peer ?: if (continuing) session.peer else null,
        direction = snapshot.direction, cancelRequested = cancel,
        operationMessage = if (continuing) operationMessage?.takeUnless {
            it in setOf(CallFailureMessages.RECOVERING, CallFailureMessages.SNAPSHOT, CallFailureMessages.OWNER_CHANGED)
        } else null,
    )
    dtmfSupported = snapshot.dtmf
    if (phase != CallPhase.InCall) dtmfOperation = null
    clock = if (snapshot.phase == CallPhase.InCall) {
        CallSessionClock(maxOf(snapshot.elapsed, if (continuing) clock?.elapsedMs() ?: 0 else 0), monotonicNow)
    } else if (snapshot.phase == CallPhase.Ending && continuing) clock else null
    val cause = if (phase == CallPhase.Idle) pendingTerminalCause ?: CallHistoryCause.Session else CallHistoryCause.Session
    val result = commit(next, cause)
    interrupted?.let(recorder::enqueue)
    return result.copy(stopAudio = result.stopAudio || epochChanged || replaced,
        stopSession = if (epochChanged || replaced) previousAudio else result.stopSession)
}

private suspend fun EngineCallSource.receiveCallFrame(transport: EngineMessageTransport, frame: EngineProtocolFrame.Event) {
    val event = CallProtocolCodec.decodeEvent(frame) ?: return
    val eventVersion = CallSnapshotCodec.version(frame.data)
    if (("epoch" in frame.data || "revision" in frame.data) && eventVersion == null) return
    var query = false
    val effects = mutex.withLock {
        if (closed || activeTransport !== transport) return@withLock CallEffects()
        if (eventVersion != null) {
            val current = version
            if (current == null || current.epoch != eventVersion.epoch) {
                if (eventVersion.epoch in retiredEpochs) return@withLock CallEffects()
                query = true
                loseConnection(CallSyncState.Recovering, CallFailureMessages.RECOVERING)
                return@withLock CallEffects(stopAudio = true, stopSession = audioSession())
            }
            if (eventVersion.revision <= current.revision) return@withLock CallEffects()
            version = eventVersion
        } else if (event.token in retiredTokens || sync != CallSyncState.Ready) return@withLock CallEffects()
        if (eventVersion != null && session.token != null && session.token != event.token) {
            query = true
            return@withLock CallEffects()
        }
        val change = CallSessionReducer.reduce(session, event)
        var next = change.session
        if (event is CallWireEvent.Dialing && session.cancelRequested && next.token == event.token) {
            next = next.copy(phase = CallPhase.Canceling, cancelRequested = true)
        }
        if (event is CallWireEvent.Ringing && next.phase == CallPhase.Ringing && next.token == event.token) {
            next = next.copy(direction = CallDirection.INCOMING)
        }
        if (event is CallWireEvent.InCall && eventVersion != null) query = true
        commit(next, change.ended?.let(CallHistoryCause::EventEnded) ?: CallHistoryCause.Session)
    }
    apply(effects)
    dispatchPendingCancel()
    if (query) launch { fetchRequiredSnapshot(transport) }
}
