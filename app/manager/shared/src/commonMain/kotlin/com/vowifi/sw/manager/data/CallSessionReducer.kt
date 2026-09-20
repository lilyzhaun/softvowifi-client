package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallAudioState
import com.vowifi.sw.manager.model.CallFailureMessages
import com.vowifi.sw.manager.model.CallPeer
import com.vowifi.sw.manager.model.CallPhase

internal data class CallSession(
    val token: String? = null,
    val phase: CallPhase = CallPhase.Idle,
    val audio: CallAudioState = CallAudioState.Idle,
    val busy: Boolean = false,
    val operationMessage: String? = null,
    val peer: CallPeer? = null,
    val direction: com.vowifi.sw.manager.model.CallDirection? = null,
    val cancelRequested: Boolean = false,
) {
    override fun toString(): String =
        "CallSession(phase=$phase, busy=$busy, hasPeer=${peer != null})"
}

internal class CallEndedEffect(
    val token: String,
    val reason: CallWireReason,
    val peer: CallPeer?,
    val durationSec: Int?,
) {
    override fun toString(): String =
        "CallEndedEffect(reason=$reason, hasPeer=${peer != null}, hasDuration=${durationSec != null})"
}

internal class CallSessionChange(
    val session: CallSession,
    val ended: CallEndedEffect? = null,
)

internal object CallSessionReducer {
    fun applyEvent(session: CallSession, event: CallWireEvent): CallSession =
        reduce(session, event).session

    fun reduce(session: CallSession, event: CallWireEvent): CallSessionChange = when (event) {
        is CallWireEvent.Ringing -> CallSessionChange(ringing(session, event))
        is CallWireEvent.Dialing -> CallSessionChange(dialing(session, event.token))
        is CallWireEvent.Ending -> CallSessionChange(
            if (session.token == event.token && session.phase != CallPhase.Idle) session.copy(phase = CallPhase.Ending) else session,
        )
        is CallWireEvent.InCall -> CallSessionChange(
            claimOrOwned(session, event.token) { current ->
                current.copy(
                    token = event.token,
                    phase = CallPhase.InCall,
                    busy = false,
                    operationMessage = null,
                    peer = retainedPeer(current, event.peer),
                )
            },
        )
        is CallWireEvent.Ended -> ended(session, event)
    }

    fun beginDial(session: CallSession): CallSession? =
        if (session.phase == CallPhase.Idle && !session.busy) {
            session.copy(
                token = null,
                phase = CallPhase.Dialing,
                audio = CallAudioState.Idle,
                busy = true,
                operationMessage = null,
                peer = null,
            )
        } else {
            null
        }

    fun completeDial(session: CallSession, resultToken: String?, operationMessage: String?): CallSession =
        when (session.phase) {
            CallPhase.Idle, CallPhase.InCall -> session.copy(busy = false)
            CallPhase.Dialing -> when {
                operationMessage != null -> CallSession(operationMessage = operationMessage)
                resultToken.isNullOrEmpty() -> CallSession(operationMessage = CallFailureMessages.INVALID)
                else -> CallSession(token = resultToken, phase = CallPhase.InCall)
            }
            CallPhase.Ringing, CallPhase.Connecting, CallPhase.Canceling, CallPhase.Ending -> session.copy(busy = false)
        }

    fun beginAnswer(session: CallSession): CallSession? =
        begin(session, CallPhase.Ringing)

    fun completeAnswer(session: CallSession, operationMessage: String?): CallSession {
        if (session.phase != CallPhase.Ringing) return session.copy(busy = false)
        return if (operationMessage == null) {
            session.copy(phase = CallPhase.Connecting, busy = false, operationMessage = null)
        } else {
            session.copy(busy = false, operationMessage = operationMessage)
        }
    }

    fun beginReject(session: CallSession): CallSession? =
        begin(session, CallPhase.Ringing)

    fun completeReject(session: CallSession, operationMessage: String?): CallSession {
        if (session.phase != CallPhase.Ringing) return session.copy(busy = false)
        return if (operationMessage == null) {
            CallSession()
        } else {
            session.copy(busy = false, operationMessage = operationMessage)
        }
    }

    fun beginHangup(session: CallSession): CallSession? =
        if (session.phase == CallPhase.InCall && !session.busy && session.token != null) {
            session.copy(busy = true, operationMessage = null)
        } else {
            null
        }

    fun completeHangup(session: CallSession, operationMessage: String?): CallSession {
        if (session.phase != CallPhase.InCall && session.phase != CallPhase.Dialing) {
            return session.copy(busy = false)
        }
        return if (operationMessage == null) {
            CallSession()
        } else {
            session.copy(busy = false, operationMessage = operationMessage)
        }
    }

    fun audioStarted(session: CallSession): CallSession =
        if (session.phase == CallPhase.InCall) session.copy(audio = CallAudioState.Active) else session

    fun audioFailed(session: CallSession, message: String = CallFailureMessages.AUDIO): CallSession =
        if (session.phase == CallPhase.InCall) {
            session.copy(audio = CallAudioState.Failed(message))
        } else {
            session
        }

    private fun ringing(session: CallSession, event: CallWireEvent.Ringing): CallSession = when {
        session.token != null && session.token != event.token -> session
        session.phase == CallPhase.Ringing && session.token == event.token ->
            session.copy(peer = session.peer ?: event.peer)
        session.phase != CallPhase.Idle -> session
        else -> session.copy(
            token = event.token,
            phase = CallPhase.Ringing,
            operationMessage = null,
            peer = event.peer,
        )
    }

    private fun ended(session: CallSession, event: CallWireEvent.Ended): CallSessionChange {
        var effect: CallEndedEffect? = null
        val next = claimOrOwned(session, event.token) { current ->
            effect = CallEndedEffect(
                token = event.token,
                reason = event.reason,
                peer = current.peer ?: event.peer,
                durationSec = event.durationSec,
            )
            CallSession()
        }
        return CallSessionChange(next, effect)
    }

    private fun retainedPeer(current: CallSession, incoming: CallPeer?): CallPeer? =
        if (current.phase == CallPhase.Dialing) current.peer else current.peer ?: incoming

    private fun dialing(session: CallSession, token: String): CallSession {
        if (session.phase !in setOf(CallPhase.Dialing, CallPhase.Canceling) || session.token != null) return session
        return session.copy(token = token, operationMessage = null)
    }

    private fun begin(session: CallSession, required: CallPhase): CallSession? =
        if (session.phase == required && !session.busy && session.token != null) {
            session.copy(busy = true, operationMessage = null)
        } else {
            null
        }

    private fun claimOrOwned(
        session: CallSession,
        token: String,
        transform: (CallSession) -> CallSession,
    ): CallSession {
        val claim = session.phase in setOf(CallPhase.Dialing, CallPhase.Canceling) && session.token == null
        return if (claim || session.token == token) transform(session) else session
    }
}
