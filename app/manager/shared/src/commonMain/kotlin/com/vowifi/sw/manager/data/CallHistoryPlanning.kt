package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallDirection
import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.CallPeer
import com.vowifi.sw.manager.model.CallPeerPhone
import com.vowifi.sw.manager.model.CallTimesDraft

internal sealed interface CallHistoryCause {
    data object Session : CallHistoryCause
    class EventEnded(val effect: CallEndedEffect) : CallHistoryCause
    data object Hangup : CallHistoryCause
    data object Reject : CallHistoryCause
    data object DialFailed : CallHistoryCause
    data object DialCanceled : CallHistoryCause
    data object Interrupted : CallHistoryCause
}

internal class CallHistoryEffect(
    val sequence: Long,
    val owner: SimOwnerId,
    val write: CallHistoryWrite,
) {
    override fun toString(): String = "CallHistoryEffect(outcome=${write.outcome})"
}

internal fun callHistoryOutcome(reason: CallWireReason, connected: Boolean): CallOutcome = when (reason) {
    CallWireReason.REJECTED -> CallOutcome.REJECTED
    CallWireReason.CANCELED -> CallOutcome.CANCELED
    CallWireReason.TIMEOUT -> CallOutcome.MISSED
    CallWireReason.HANGUP -> if (connected) CallOutcome.COMPLETED else CallOutcome.CANCELED
    CallWireReason.REMOTE -> if (connected) CallOutcome.COMPLETED else CallOutcome.CANCELED
    CallWireReason.FAILED -> CallOutcome.FAILED
}

internal fun callHistoryPeer(peer: CallPeer?): CallPeerPhone? = peer?.text()?.let(CallPeerPhone::parse)

internal fun callHistoryDialPeer(number: String): CallPeerPhone? = CallPeerPhone.parse(number)

internal fun callHistoryDurationSec(
    eventDurationSec: Int?,
    connectedAtMs: Long?,
    endedAtMs: Long,
): Long? {
    if (eventDurationSec != null && eventDurationSec in 0..CallProtocolCodec.MAX_DURATION_SEC) {
        return eventDurationSec.toLong()
    }
    if (connectedAtMs == null) return null
    return ((endedAtMs - connectedAtMs) / 1000L).coerceIn(0L, CallProtocolCodec.MAX_DURATION_SEC.toLong())
}

internal fun callHistoryWrite(
    correlation: CallCorrelationKey,
    direction: CallDirection,
    peer: CallPeerPhone?,
    startedAtMs: Long,
    connectedAtMs: Long?,
    endedAtMs: Long?,
    durationSec: Long?,
    outcome: CallOutcome,
): CallHistoryWrite? = CallHistoryWrite.parse(
    CallHistoryDraft(
        correlation = correlation.wire(),
        direction = direction,
        peer = peer?.digits(),
        times = CallTimesDraft(startedAtMs, connectedAtMs, endedAtMs, durationSec),
        outcome = outcome,
    ),
)
