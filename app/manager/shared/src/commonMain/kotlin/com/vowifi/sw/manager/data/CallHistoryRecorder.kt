package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallDirection
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.CallPeerPhone
import com.vowifi.sw.manager.model.CallPhase
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.StateFlow

internal class CallHistoryRecorder(
    private val binding: CallHistoryBinding?,
    private val clock: () -> Long,
    parentContext: CoroutineContext,
) {
    private val store = CallHistoryStore(binding?.repository, parentContext, clock)
    val state: StateFlow<CallHistoryState> = store.state
    val owners = store.owners
    fun selectHistory(choice: CallHistoryOwnerChoice) = store.selectHistory(choice)

    private var nextSequence = 0L
    private var open: OpenCall? = null

    fun claimDial(iccid: String?, number: String, generation: Long): CallHistoryEffect? {
        val owner = ownerOf(iccid) ?: return identityFailure()
        val correlation = CallCorrelationKey.parse(binding?.nextCorrelation?.invoke().orEmpty())
            ?: return identityFailure()
        val created = OpenCall(
            owner = owner,
            correlation = correlation,
            direction = CallDirection.OUTGOING,
            generation = generation,
            engineToken = null,
            peer = callHistoryDialPeer(number),
            startedAtMs = clock(),
            connectedAtMs = null,
        )
        open = created
        return effect(owner, created.toWrite(CallOutcome.STARTED) ?: return identityFailure())
    }

    fun observe(
        iccid: String?,
        generation: Long,
        before: CallSession,
        after: CallSession,
        cause: CallHistoryCause,
    ): CallHistoryEffect? {
        if (binding == null) return null
        ringing(iccid, generation, before, after)?.let { return it }
        bindToken(generation, after)
        connected(generation, after)?.let { return it }
        if (before.phase != CallPhase.Idle && after.phase == CallPhase.Idle) {
            return closeOpen(generation, cause)
        }
        return null
    }

    fun ownerFor(iccid: String?): SimOwnerId? = ownerOf(iccid)

    fun claimInterrupt(): CallHistoryEffect? = closeOpen(open?.generation ?: -1L, CallHistoryCause.Interrupted)

    fun enqueue(effect: CallHistoryEffect) = store.write(effect)
    fun refreshOwner(owner: SimOwnerId) = store.select(owner)
    fun clear(expected: CallHistoryState.Ready) {
        if (open == null) store.clear(expected)
    }
    fun close() = store.close()

    private fun ringing(
        iccid: String?,
        generation: Long,
        before: CallSession,
        after: CallSession,
    ): CallHistoryEffect? {
        if (after.phase != CallPhase.Ringing) return null
        val token = after.token ?: return null
        val current = open
        if (current != null && current.generation == generation && current.engineToken == token) {
            if (current.peer != null) return null
            current.peer = callHistoryPeer(after.peer) ?: return null
            return effect(current.owner, current.toWrite(CallOutcome.STARTED) ?: return null)
        }
        if (current != null) return null
        if (before.phase != CallPhase.Idle) return null
        val owner = ownerOf(iccid) ?: return identityFailure()
        val correlation = CallCorrelationKey.parse(token)
            ?: CallCorrelationKey.parse(binding?.nextCorrelation?.invoke().orEmpty())
            ?: return identityFailure()
        val created = OpenCall(
            owner = owner,
            correlation = correlation,
            direction = CallDirection.INCOMING,
            generation = generation,
            engineToken = token,
            peer = callHistoryPeer(after.peer),
            startedAtMs = clock(),
            connectedAtMs = null,
        )
        open = created
        return effect(owner, created.toWrite(CallOutcome.STARTED) ?: return identityFailure())
    }

    private fun bindToken(generation: Long, after: CallSession) {
        val current = open ?: return
        if (current.generation != generation || current.engineToken != null) return
        current.engineToken = after.token
    }

    private fun connected(generation: Long, after: CallSession): CallHistoryEffect? {
        if (after.phase != CallPhase.InCall) return null
        val current = open ?: return null
        if (current.generation != generation) return null
        if (current.engineToken != null && after.token != null && current.engineToken != after.token) {
            return null
        }
        if (current.engineToken == null) current.engineToken = after.token
        if (current.connectedAtMs != null) return null
        current.connectedAtMs = clock()
        return effect(current.owner, current.toWrite(CallOutcome.CONNECTED) ?: return null)
    }

    private fun closeOpen(generation: Long, cause: CallHistoryCause): CallHistoryEffect? {
        val current = open ?: return null
        if (generation >= 0L && current.generation != generation) return null
        if (cause is CallHistoryCause.EventEnded) {
            val token = cause.effect.token
            if (current.engineToken != null && current.engineToken != token) return null
            if (current.engineToken == null) current.engineToken = token
            current.peer = current.peer ?: callHistoryPeer(cause.effect.peer)
        }
        val now = clock()
        val connected = current.connectedAtMs != null
        val outcome = terminalOutcome(cause, connected)
        val duration = callHistoryDurationSec(
            (cause as? CallHistoryCause.EventEnded)?.effect?.durationSec,
            current.connectedAtMs,
            now,
        )
        val write = current.toWrite(outcome, now, duration) ?: return null
        open = null
        return effect(current.owner, write)
    }

    private fun terminalOutcome(cause: CallHistoryCause, connected: Boolean): CallOutcome = when (cause) {
        is CallHistoryCause.EventEnded -> callHistoryOutcome(cause.effect.reason, connected)
        CallHistoryCause.Hangup -> if (connected) CallOutcome.COMPLETED else CallOutcome.CANCELED
        CallHistoryCause.Reject -> CallOutcome.REJECTED
        CallHistoryCause.DialFailed -> CallOutcome.FAILED
        CallHistoryCause.DialCanceled -> CallOutcome.CANCELED
        CallHistoryCause.Interrupted, CallHistoryCause.Session -> CallOutcome.INTERRUPTED
    }

    private fun ownerOf(iccid: String?): SimOwnerId? {
        val raw = iccid ?: return null
        return binding?.ownerIdFor?.invoke(raw)
    }

    private fun identityFailure(): CallHistoryEffect? {
        store.fail()
        return null
    }

    private fun effect(owner: SimOwnerId, write: CallHistoryWrite): CallHistoryEffect {
        nextSequence += 1L
        return CallHistoryEffect(nextSequence, owner, write)
    }

    private class OpenCall(
        val owner: SimOwnerId,
        val correlation: CallCorrelationKey,
        val direction: CallDirection,
        val generation: Long,
        var engineToken: String?,
        var peer: CallPeerPhone?,
        val startedAtMs: Long,
        var connectedAtMs: Long?,
    ) {
        fun toWrite(
            outcome: CallOutcome,
            endedAtMs: Long? = null,
            durationSec: Long? = null,
        ): CallHistoryWrite? = callHistoryWrite(
            correlation = correlation,
            direction = direction,
            peer = peer,
            startedAtMs = startedAtMs,
            connectedAtMs = connectedAtMs,
            endedAtMs = endedAtMs,
            durationSec = durationSec,
            outcome = outcome,
        )
    }

}
