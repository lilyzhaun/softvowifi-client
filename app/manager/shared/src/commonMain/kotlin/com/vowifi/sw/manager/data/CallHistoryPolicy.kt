package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.CallTimesDraft

internal object CallHistoryPolicy {
    const val MAX_RECORDS_PER_OWNER = 500
}

internal fun CallOutcome.progress(): Int = when (this) {
    CallOutcome.STARTED -> 0
    CallOutcome.CONNECTED -> 1
    CallOutcome.COMPLETED,
    CallOutcome.MISSED,
    CallOutcome.REJECTED,
    CallOutcome.CANCELED,
    CallOutcome.FAILED,
    CallOutcome.INTERRUPTED,
    -> 2
}

internal fun CallOutcome.isTerminal(): Boolean = progress() == 2

internal fun CallHistoryWrite.interrupted(endedAtMs: Long): CallHistoryWrite? {
    val connectedAtMs = times.connectedAtMs
    val ended = maxOf(endedAtMs, times.startedAtMs, connectedAtMs ?: times.startedAtMs)
    val durationSec = if (connectedAtMs == null) {
        null
    } else {
        ((ended - connectedAtMs) / 1000L).coerceIn(0L, CallProtocolCodec.MAX_DURATION_SEC.toLong())
    }
    return CallHistoryWrite.parse(
        CallHistoryDraft(
            correlation = correlation.wire(),
            direction = direction,
            peer = peer?.dialNumber(),
            times = CallTimesDraft(times.startedAtMs, connectedAtMs, ended, durationSec),
            outcome = CallOutcome.INTERRUPTED,
        ),
    )
}

internal fun parseCallTimes(draft: CallTimesDraft, outcome: CallOutcome): CallTimes? {
    if (draft.startedAtMs < 0) return null
    val connected = draft.connectedAtMs
    val ended = draft.endedAtMs
    val duration = draft.durationSec
    if (connected != null && (connected < 0 || connected < draft.startedAtMs)) return null
    if (ended != null && (ended < 0 || ended < draft.startedAtMs)) return null
    if (connected != null && ended != null && ended < connected) return null
    if (duration != null && duration < 0) return null
    return when (outcome) {
        CallOutcome.STARTED ->
            if (connected != null || ended != null || duration != null) {
                null
            } else {
                CallTimes(draft.startedAtMs, null, null, null)
            }
        CallOutcome.CONNECTED ->
            if (connected == null || ended != null || duration != null) {
                null
            } else {
                CallTimes(draft.startedAtMs, connected, null, null)
            }
        CallOutcome.COMPLETED,
        CallOutcome.MISSED,
        CallOutcome.REJECTED,
        CallOutcome.CANCELED,
        CallOutcome.FAILED,
        CallOutcome.INTERRUPTED,
        ->
            if (ended == null) {
                null
            } else {
                CallTimes(draft.startedAtMs, connected, ended, duration)
            }
    }
}

internal fun mergeCallHistory(current: CallHistoryWrite?, incoming: CallHistoryWrite): CallHistoryWrite {
    if (current == null) return incoming
    val currentProgress = current.outcome.progress()
    val incomingProgress = incoming.outcome.progress()
    if (currentProgress > incomingProgress) return current
    if (currentProgress == incomingProgress && current.outcome.isTerminal()) return current
    val startedAtMs = minOf(current.times.startedAtMs, incoming.times.startedAtMs)
    val connectedAtMs = laterTime(current.times.connectedAtMs, incoming.times.connectedAtMs)
    val endedAtMs = incoming.times.endedAtMs ?: current.times.endedAtMs
    val durationSec = incoming.times.durationSec ?: current.times.durationSec
    val outcome = if (incomingProgress > currentProgress) incoming.outcome else current.outcome
    val times = parseCallTimes(
        CallTimesDraft(startedAtMs, connectedAtMs, endedAtMs, durationSec),
        outcome,
    ) ?: return current
    return current.withState(
        peer = current.peer ?: incoming.peer,
        times = times,
        outcome = outcome,
    )
}

private fun laterTime(left: Long?, right: Long?): Long? = when {
    left == null -> right
    right == null -> left
    else -> maxOf(left, right)
}
