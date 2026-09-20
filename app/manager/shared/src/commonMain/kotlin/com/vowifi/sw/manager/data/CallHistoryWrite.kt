package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallDirection
import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.CallPeerPhone
import com.vowifi.sw.manager.model.CallRecord
import com.vowifi.sw.manager.model.CallTimesDraft

internal class CallCorrelationKey private constructor(private val value: String) {
    internal fun wire(): String = value

    override fun equals(other: Any?): Boolean = other is CallCorrelationKey && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "CallCorrelationKey"

    companion object {
        private val BODY = Regex("^[A-Za-z0-9._:-]{1,128}$")

        fun parse(raw: String): CallCorrelationKey? {
            val lower = raw.lowercase()
            if (lower.startsWith("sip:") ||
                lower.startsWith("sips:") ||
                lower.startsWith("tel:") ||
                lower.startsWith("raw:")
            ) {
                return null
            }
            if (!BODY.matches(raw)) return null
            return CallCorrelationKey(raw)
        }
    }
}

class CallHistoryDraft(
    val correlation: String,
    val direction: CallDirection,
    val peer: String?,
    val times: CallTimesDraft,
    val outcome: CallOutcome,
) {
    override fun toString(): String = "CallHistoryDraft"
}

internal class CallTimes internal constructor(
    val startedAtMs: Long,
    val connectedAtMs: Long?,
    val endedAtMs: Long?,
    val durationSec: Long?,
) {
    override fun toString(): String = "CallTimes"
}

class CallHistoryWrite private constructor(
    internal val correlation: CallCorrelationKey,
    val direction: CallDirection,
    val peer: CallPeerPhone?,
    internal val times: CallTimes,
    val outcome: CallOutcome,
) {
    internal fun withState(
        peer: CallPeerPhone?,
        times: CallTimes,
        outcome: CallOutcome,
    ): CallHistoryWrite = CallHistoryWrite(correlation, direction, peer, times, outcome)

    internal fun toRecord(): CallRecord = CallRecord(
        direction = direction,
        peer = peer,
        startedAtMs = times.startedAtMs,
        connectedAtMs = times.connectedAtMs,
        endedAtMs = times.endedAtMs,
        durationSec = times.durationSec,
        outcome = outcome,
    )

    override fun toString(): String =
        "CallHistoryWrite(direction=$direction, outcome=$outcome, hasPeer=${peer != null})"

    companion object {
        fun parse(draft: CallHistoryDraft): CallHistoryWrite? {
            val correlation = CallCorrelationKey.parse(draft.correlation) ?: return null
            val peer = when (val raw = draft.peer) {
                null -> null
                else -> CallPeerPhone.parse(raw) ?: return null
            }
            val times = parseCallTimes(draft.times, draft.outcome) ?: return null
            return CallHistoryWrite(correlation, draft.direction, peer, times, draft.outcome)
        }
    }
}
