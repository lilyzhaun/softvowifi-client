package com.vowifi.sw.manager.model

enum class CallDirection {
    INCOMING,
    OUTGOING,
}

enum class CallOutcome {
    STARTED,
    CONNECTED,
    COMPLETED,
    MISSED,
    REJECTED,
    CANCELED,
    FAILED,
    INTERRUPTED,
}

class CallPeerPhone private constructor(private val value: String) {
    fun digits(): String = value

    override fun equals(other: Any?): Boolean = other is CallPeerPhone && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "CallPeerPhone"

    companion object {
        fun parse(raw: String): CallPeerPhone? {
            val lower = raw.lowercase()
            if (lower.startsWith("raw:") ||
                lower.startsWith("sip:") ||
                lower.startsWith("sips:") ||
                lower.startsWith("tel:")
            ) {
                return null
            }
            val start = if (raw.startsWith("+")) 1 else 0
            val digits = raw.substring(start)
            if (digits.isEmpty() || digits.length > 20) return null
            for (ch in digits) {
                if (ch !in '0'..'9') return null
            }
            return CallPeerPhone(digits)
        }
    }
}

class CallTimesDraft(
    val startedAtMs: Long,
    val connectedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val durationSec: Long? = null,
) {
    override fun toString(): String = "CallTimesDraft"
}

class CallRecord internal constructor(
    val direction: CallDirection,
    val peer: CallPeerPhone?,
    val startedAtMs: Long,
    val connectedAtMs: Long?,
    val endedAtMs: Long?,
    val durationSec: Long?,
    val outcome: CallOutcome,
) {
    override fun equals(other: Any?): Boolean = other is CallRecord &&
        direction == other.direction &&
        peer == other.peer &&
        startedAtMs == other.startedAtMs &&
        connectedAtMs == other.connectedAtMs &&
        endedAtMs == other.endedAtMs &&
        durationSec == other.durationSec &&
        outcome == other.outcome

    override fun hashCode(): Int {
        var result = direction.hashCode()
        result = 31 * result + (peer?.hashCode() ?: 0)
        result = 31 * result + startedAtMs.hashCode()
        result = 31 * result + (connectedAtMs?.hashCode() ?: 0)
        result = 31 * result + (endedAtMs?.hashCode() ?: 0)
        result = 31 * result + (durationSec?.hashCode() ?: 0)
        result = 31 * result + outcome.hashCode()
        return result
    }

    override fun toString(): String =
        "CallRecord(direction=$direction, outcome=$outcome, hasPeer=${peer != null})"
}

sealed interface CallHistoryState {
    data object Loading : CallHistoryState
    data object Empty : CallHistoryState
    class Ready(val records: List<CallRecord>) : CallHistoryState {
        override fun equals(other: Any?): Boolean = other is Ready && records == other.records
        override fun hashCode(): Int = records.hashCode()
        override fun toString(): String = "Ready(recordCount=${records.size})"
    }
    class Failure(val message: String) : CallHistoryState {
        override fun equals(other: Any?): Boolean = other is Failure && message == other.message
        override fun hashCode(): Int = message.hashCode()
        override fun toString(): String = "Failure"
    }
}
