package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal suspend fun EngineCallSource.performDial(expected: CallBinding, number: String, started: () -> Boolean = { true }) {
    val planned = mutex.withLock {
        if (!owns(expected) || session.phase != CallPhase.Idle || dialOperation != null) return
        if (!CallProtocolCodec.isValidDialNumber(number)) {
            session = session.copy(operationMessage = CallFailureMessages.INVALID)
            publish()
            return
        }
        val transport = activeTransport ?: return
        generation += 1
        bindingGeneration += 1
        session = (CallSessionReducer.beginDial(session) ?: return).copy(direction = CallDirection.OUTGOING, peer = CallPeer.parse(number))
        if (!started()) {
            session = CallSession(operationMessage = CallFailureMessages.AUDIO)
            publish()
            return
        }
        val operation = ++nextOperation
        dialOperation = operation
        recorder.claimDial(activeIccid, number, generation)?.let(recorder::enqueue)
        publish()
        PlannedCallRequest.Dial(transport, number, CallRequestOwner(bindingGeneration, operation, version))
    }
    val result = EngineCallRequestExecutor.execute(planned)
    var query = false
    val effects = mutex.withLock {
        if (closed || activeTransport !== planned.transport || dialOperation != planned.owner.operationId ||
            bindingGeneration != planned.owner.generation) return@withLock CallEffects()
        dialOperation = null
        pendingTerminalCause = when {
            result.errorCode == 2005 -> CallHistoryCause.DialCanceled
            result.errorCode != null -> CallHistoryCause.DialFailed
            else -> null
        }
        if (version != planned.owner.version) {
            session = session.copy(busy = controlOperation != null)
            publish()
            query = true
            return@withLock CallEffects()
        }
        if (session.cancelRequested || result.errorCode == 2005 || result.operationMessage == CallFailureMessages.NETWORK) {
            session = session.copy(busy = controlOperation != null, operationMessage = result.operationMessage)
            if (result.operationMessage == CallFailureMessages.NETWORK) sync = CallSyncState.Recovering
            publish()
            query = true
            return@withLock CallEffects()
        }
        val completed = CallSessionReducer.completeDial(session, result.resultToken, result.operationMessage)
        val next = if (completed.phase == CallPhase.Idle) completed else completed.copy(direction = session.direction, peer = session.peer)
        if (result.resultToken != null) query = true
        commit(next, rpcCause(planned, session, next))
    }
    apply(effects)
    if (query) fetchSnapshot(planned.transport)
}

internal suspend fun EngineCallSource.performControl(expected: CallBinding, action: CallAction, started: () -> Boolean = { true }) {
    var pendingOnly = false
    val request = mutex.withLock {
        val supersedes = action == CallAction.Hangup && session.phase in setOf(CallPhase.Connecting, CallPhase.InCall) ||
            action == CallAction.Reject && session.phase == CallPhase.Ringing && pendingAction == CallAction.Answer
        if (!owns(expected) || controlOperation != null && !supersedes) return
        val phase = session.phase
        val allowed = when (action) {
            CallAction.Answer, CallAction.Reject -> phase == CallPhase.Ringing
            CallAction.Hangup -> phase in setOf(CallPhase.Connecting, CallPhase.InCall)
            CallAction.Cancel -> session.direction == CallDirection.OUTGOING && phase in setOf(CallPhase.Dialing, CallPhase.Canceling)
        }
        if (!allowed || action == CallAction.Cancel && session.cancelRequested) return
        if (action == CallAction.Cancel) {
            session = session.copy(cancelRequested = true, phase = CallPhase.Canceling, operationMessage = null)
            if (session.token == null) pendingOnly = true
        }
        val token = session.token
        if (pendingOnly || token == null) { publish(); return@withLock null }
        if (!started()) return
        beginControl(expected.owner, token, action)
    }
    request?.let { executeControl(it) }
}

internal suspend fun EngineCallSource.dispatchPendingCancel() {
    val request = mutex.withLock {
        if (closed || sync != CallSyncState.Ready || !session.cancelRequested ||
            controlOperation != null || cancelDispatched || session.phase != CallPhase.Canceling) return
        val owner = activeIccid ?: return
        val token = session.token ?: return
        beginControl(owner, token, CallAction.Cancel)
    }
    executeControl(request)
}

private fun EngineCallSource.beginControl(owner: String, token: String, action: CallAction): CallCommand {
    val previousPhase = session.phase
    val operation = ++nextOperation
    controlOperation = operation
    pendingAction = action
    if (action == CallAction.Hangup) dtmfOperation = null
    if (action == CallAction.Cancel) cancelDispatched = true
    session = session.copy(busy = true, operationMessage = null,
        phase = if (action == CallAction.Hangup) CallPhase.Ending else session.phase)
    publish()
    return CallCommand(owner, token, action, operation, bindingGeneration, activeTransport!!, version, previousPhase)
}

private class CallCommand(
    val owner: String, val token: String, val action: CallAction, val id: Long,
    val generation: Long, val primary: EngineMessageTransport, val version: CallVersion?,
    val previousPhase: CallPhase,
)

private suspend fun EngineCallSource.executeControl(request: CallCommand) {
    val method = when (request.action) {
        CallAction.Answer -> "answer"
        CallAction.Reject -> "reject"
        CallAction.Hangup -> "hangup"
        CallAction.Cancel -> "cancelCall"
    }
    val response = try {
        control.request(request.owner, method, CallProtocolCodec.params(request.token),
            if (request.action == CallAction.Answer) CallProtocolCodec.ANSWER_TIMEOUT_MILLIS else CallProtocolCodec.CONTROL_TIMEOUT_MILLIS)
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { null }
    var query = false
    val hangupResult = if (request.action == CallAction.Hangup) response?.let { CallProtocolCodec.hangupResult(it, request.token) } else null
    val effects = mutex.withLock {
        if (closed || controlOperation != request.id || bindingGeneration != request.generation ||
            activeIccid != request.owner || activeTransport !== request.primary || session.token != request.token) return@withLock CallEffects()
        controlOperation = null
        pendingAction = null
        if (version != request.version && request.action != CallAction.Cancel) {
            if (hangupResult != null) {
                pendingTerminalCause = CallHistoryCause.Hangup
                query = true
            }
            session = session.copy(busy = dialOperation != null)
            publish()
            return@withLock CallEffects()
        }
        val ok = response?.error == null && response?.result != null && when (request.action) {
            CallAction.Answer -> CallProtocolCodec.resultCallId(response) == request.token && CallProtocolCodec.resultState(response) == "RINGING"
            CallAction.Reject -> CallProtocolCodec.resultState(response) == "IDLE"
            CallAction.Hangup -> hangupResult != null
            CallAction.Cancel -> (response.result["accepted"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true &&
                CallProtocolCodec.resultCallId(response) == request.token
        }
        if (!ok) {
            val code = response?.error?.code
            val message = if (response == null) CallFailureMessages.NETWORK
                else if (response.error == null) CallFailureMessages.INVALID else CallProtocolCodec.messageOf(response.error)
            session = session.copy(busy = dialOperation != null, operationMessage = message,
                phase = if (session.phase == CallPhase.Ending) request.previousPhase else session.phase)
            if (code == 1001 || code == 3002 || response == null || request.action == CallAction.Cancel) {
                sync = CallSyncState.Recovering
                query = true
            }
            publish()
            return@withLock CallEffects()
        }
        when (request.action) {
            CallAction.Cancel -> {
                session = session.copy(busy = false)
                publish()
                CallEffects()
            }
            CallAction.Answer -> commit(CallSessionReducer.completeAnswer(session, null))
            CallAction.Reject -> commit(CallSession(), CallHistoryCause.Reject)
            CallAction.Hangup -> if (hangupResult == CallHangupResult.Pending) {
                pendingTerminalCause = CallHistoryCause.Hangup
                session = session.copy(phase = CallPhase.Ending, busy = false)
                publish()
                query = true
                CallEffects()
            } else commit(CallSession(), CallHistoryCause.Hangup)
        }
    }
    apply(effects)
    if (query) fetchSnapshot(request.primary)
}
