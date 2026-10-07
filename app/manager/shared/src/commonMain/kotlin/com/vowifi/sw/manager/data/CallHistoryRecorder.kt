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
    private val lock = Any()
    private val open = mutableMapOf<Key, OpenCall>()
    private val store = CallHistoryStore(binding?.repository, parentContext, clock, ::canClear)
    val state: StateFlow<CallHistoryState> = store.state
    val owners = store.owners
    fun selectHistory(choice: CallHistoryOwnerChoice) = store.selectHistory(choice)

    private var nextSequence = 0L
    private data class Key(val owner: SimOwnerId, val generation: Long)

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
        val write = created.toWrite(CallOutcome.STARTED) ?: return identityFailure()
        return synchronized(lock) {
            val key = Key(owner, generation)
            if (key in open) return@synchronized null
            open[key] = created
            effect(owner, write)
        }
    }

    fun observe(
        iccid: String?,
        generation: Long,
        before: CallSession,
        after: CallSession,
        cause: CallHistoryCause,
    ): CallHistoryEffect? {
        if (binding == null) return null
        val owner = ownerOf(iccid) ?: return if (before.phase == CallPhase.Idle && after.phase == CallPhase.Ringing) identityFailure() else null
        val key = Key(owner, generation)
        val now = clock()
        val correlation = if (before.phase == CallPhase.Idle && after.phase == CallPhase.Ringing) {
            CallCorrelationKey.parse(after.token.orEmpty())
                ?: CallCorrelationKey.parse(binding.nextCorrelation()) ?: return identityFailure()
        } else null
        return synchronized(lock) {
            ringing(key, before, after, correlation, now)?.let { return@synchronized it }
            val current = open[key] ?: return@synchronized null
            if (after.phase == CallPhase.Idle) {
                if (before.phase == CallPhase.Idle) return@synchronized null
                return@synchronized closeOpen(key, before.token, cause, now)
            }
            if (current.engineToken != null && current.engineToken != after.token) return@synchronized null
            if (current.engineToken == null) current.engineToken = after.token
            if (after.phase != CallPhase.InCall || current.connectedAtMs != null) return@synchronized null
            current.connectedAtMs = now
            current.toWrite(CallOutcome.CONNECTED)?.let { effect(owner, it) }
        }
    }

    fun ownerFor(iccid: String?): SimOwnerId? = ownerOf(iccid)

    fun claimInterrupt(iccid: String?, generation: Long, token: String?): CallHistoryEffect? {
        val owner = ownerOf(iccid) ?: return null
        val now = clock()
        return synchronized(lock) { closeOpen(Key(owner, generation), token, CallHistoryCause.Interrupted, now) }
    }

    fun enqueue(effect: CallHistoryEffect) = store.write(effect)
    fun refreshOwner(owner: SimOwnerId) = store.select(owner)
    fun selectOutgoingOwner(owner: SimOwnerId) = store.select(owner, outgoingSelection = true)
    fun clear(expected: CallHistoryState.Ready) {
        if (canClear()) store.clear(expected)
    }
    fun delete(expected: CallHistoryState.Ready, ids: Set<com.vowifi.sw.manager.model.CallHistoryRecordId>) {
        if (canClear()) store.delete(expected, ids)
    }
    private fun canClear(): Boolean = synchronized(lock) { open.isEmpty() }
    fun close() = store.close()

    private fun ringing(
        key: Key,
        before: CallSession,
        after: CallSession,
        correlation: CallCorrelationKey?,
        now: Long,
    ): CallHistoryEffect? {
        if (after.phase != CallPhase.Ringing) return null
        val token = after.token ?: return null
        val current = open[key]
        if (current != null && current.engineToken == token) {
            if (current.peer != null) return null
            current.peer = callHistoryPeer(after.peer) ?: return null
            return effect(current.owner, current.toWrite(CallOutcome.STARTED) ?: return null)
        }
        if (current != null) return null
        if (before.phase != CallPhase.Idle) return null
        val created = OpenCall(
            owner = key.owner,
            correlation = correlation ?: return null,
            direction = CallDirection.INCOMING,
            generation = key.generation,
            engineToken = token,
            peer = callHistoryPeer(after.peer),
            startedAtMs = now,
            connectedAtMs = null,
        )
        open[key] = created
        return effect(key.owner, created.toWrite(CallOutcome.STARTED) ?: return null)
    }

    private fun closeOpen(key: Key, token: String?, cause: CallHistoryCause, now: Long): CallHistoryEffect? {
        val current = open[key] ?: return null
        if (current.engineToken != null && current.engineToken != token) return null
        if (cause is CallHistoryCause.EventEnded) {
            val token = cause.effect.token
            if (current.engineToken != null && current.engineToken != token) return null
            if (current.engineToken == null) current.engineToken = token
            current.peer = current.peer ?: callHistoryPeer(cause.effect.peer)
        }
        val connected = current.connectedAtMs != null
        val outcome = terminalOutcome(cause, connected)
        val duration = callHistoryDurationSec(
            (cause as? CallHistoryCause.EventEnded)?.effect?.durationSec,
            current.connectedAtMs,
            now,
        )
        val write = current.toWrite(outcome, now, duration) ?: return null
        open.remove(key)
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
