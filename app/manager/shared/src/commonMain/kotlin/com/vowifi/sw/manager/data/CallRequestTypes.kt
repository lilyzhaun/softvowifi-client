package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallPhase

internal data class CallRequestOwner(
    val generation: Long,
    val operationId: Long,
    val version: CallVersion? = null,
)

internal enum class CallControlAction(
    val method: String,
    val timeoutMillis: Long,
) {
    Answer("answer", CallProtocolCodec.ANSWER_TIMEOUT_MILLIS),
    Reject("reject", CallProtocolCodec.CONTROL_TIMEOUT_MILLIS),
    Hangup("hangup", CallProtocolCodec.CONTROL_TIMEOUT_MILLIS),
    ;

    fun begin(session: CallSession): CallSession? = when (this) {
        Answer -> CallSessionReducer.beginAnswer(session)
        Reject -> CallSessionReducer.beginReject(session)
        Hangup -> CallSessionReducer.beginHangup(session)
    }

    fun complete(session: CallSession, message: String?): CallSession = when (this) {
        Answer -> CallSessionReducer.completeAnswer(session, message)
        Reject -> CallSessionReducer.completeReject(session, message)
        Hangup -> CallSessionReducer.completeHangup(session, message)
    }
}

internal sealed interface PlannedCallRequest {
    val transport: EngineMessageTransport
    val owner: CallRequestOwner

    data class Control(
        override val transport: EngineMessageTransport,
        val action: CallControlAction,
        val token: String,
        override val owner: CallRequestOwner,
    ) : PlannedCallRequest

    data class Dial(
        override val transport: EngineMessageTransport,
        val number: String,
        override val owner: CallRequestOwner,
    ) : PlannedCallRequest
}

internal data class CallRequestResult(
    val resultToken: String? = null,
    val operationMessage: String? = null,
    val errorCode: Int? = null,
)

internal data class PendingCallAudio(
    val token: String,
    val transport: EngineMessageTransport,
    val iccid: String,
    val generation: Long,
    val mode: CallAudioMode = CallAudioMode.Conversation,
    val earlyMediaRevision: Long = 0,
    val ringback: Boolean = false,
) {
    fun session(): CallAudioSession = CallAudioSession(iccid, token, generation, transport, mode, earlyMediaRevision, ringback)

    fun isLive(session: CallSession, generation: Long): Boolean =
        session.token == token && this.generation == generation &&
            if (mode == CallAudioMode.Conversation) session.phase == CallPhase.InCall
            else session.phase == CallPhase.Dialing && !session.cancelRequested && session.earlyMediaRevision == earlyMediaRevision && session.ringback == ringback
}

internal data class CallEffects(
    val stopAudio: Boolean = false,
    val audio: PendingCallAudio? = null,
    val history: CallHistoryEffect? = null,
    val historyOwner: SimOwnerId? = null,
    val stopSession: CallAudioSession? = null,
    val endedSession: CallAudioSession? = null,
)
