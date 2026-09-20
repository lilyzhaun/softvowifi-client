package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallAudioState
import com.vowifi.sw.manager.model.CallOrigin
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState

internal fun CallSession.toReadyUi(): CallUiState.Ready =
    CallUiState.Ready(phase, audio, CallOrigin.ENGINE, busy, operationMessage, peer)

internal fun pendingCallAudio(
    before: CallSession,
    session: CallSession,
    transport: EngineMessageTransport,
    iccid: String?,
    generation: Long,
): PendingCallAudio? {
    if (before.phase == CallPhase.InCall || session.phase != CallPhase.InCall) return null
    val token = session.token ?: return null
    val ownerIccid = iccid ?: return null
    return PendingCallAudio(token, transport, ownerIccid, generation)
}

internal fun needsAudioStop(snapshot: CallSession): Boolean =
    snapshot.phase != CallPhase.Idle || snapshot.audio !is CallAudioState.Idle

internal class PlannedCall(
    val request: PlannedCallRequest? = null,
    val history: CallHistoryEffect? = null,
)

internal fun rpcCause(
    request: PlannedCallRequest,
    before: CallSession,
    next: CallSession,
): CallHistoryCause = when (request) {
    is PlannedCallRequest.Dial ->
        if (before.phase == CallPhase.Dialing && next.phase == CallPhase.Idle) {
            CallHistoryCause.DialFailed
        } else {
            CallHistoryCause.Session
        }
    is PlannedCallRequest.Control -> when {
        request.action == CallControlAction.Hangup &&
            before.phase != CallPhase.Idle && next.phase == CallPhase.Idle -> CallHistoryCause.Hangup
        request.action == CallControlAction.Reject && next.phase == CallPhase.Idle -> CallHistoryCause.Reject
        else -> CallHistoryCause.Session
    }
}
