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
    val mode = session.playbackMode() ?: return null
    val earlyRevision = if (mode == CallAudioMode.Progress) session.earlyMediaRevision else 0L
    val ringback = mode == CallAudioMode.Progress && session.ringback
    if (before.token == session.token && before.playbackMode() == mode &&
        (mode == CallAudioMode.Conversation || before.earlyMediaRevision == earlyRevision && before.ringback == ringback)) return null
    val token = session.token ?: return null
    val ownerIccid = iccid ?: return null
    return PendingCallAudio(token, transport, ownerIccid, generation, mode, earlyRevision, ringback)
}

internal fun CallSession.playbackMode(): CallAudioMode? = when {
    phase == CallPhase.InCall -> CallAudioMode.Conversation
    phase == CallPhase.Dialing && !cancelRequested && (ringback || earlyMediaRevision > 0) -> CallAudioMode.Progress
    else -> null
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
