package com.vowifi.sw.manager.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun CallAudioSession.sameCall(other: CallAudioSession): Boolean =
    iccid == other.iccid && token == other.token && generation == other.generation && transport === other.transport

internal fun CallAudioSession.sameSession(other: CallAudioSession): Boolean =
    sameCall(other) && mode == other.mode && earlyMediaRevision == other.earlyMediaRevision

internal class CallSessionAudio(private val delegate: CallAudioController) {
    private val lock = Any()
    private val starts = Mutex()
    private var current: Claim? = null
    private var last: Claim? = null
    private var ending: Claim? = null
    private var closed = false

    fun child(): Scoped = Scoped()

    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            current = null
            last = null
            ending = null
            delegate.stop()
        }
    }

    inner class Scoped : CallAudioController {
        private var retired = false
        private var revision = 0L
        override val events get() = delegate.events
        override val controls get() = delegate.controls

        override suspend fun start(session: CallAudioSession) {
            val ticket = synchronized(lock) {
                check(!retired && !closed)
                ++revision
            }
            starts.withLock {
                val claim = synchronized(lock) {
                    check(!retired && !closed && revision == ticket)
                    check(current == null || current?.scope === this)
                    Claim(this, session).also { current = it; last = it; ending = null }
                }
                try {
                    delegate.start(session)
                    synchronized(lock) {
                        if (retired || closed || revision != ticket || current !== claim) {
                            delegate.stop()
                            if (current === claim) current = null
                        }
                    }
                } catch (failure: Exception) {
                    synchronized(lock) {
                        delegate.stop()
                        if (current === claim) current = null
                    }
                    throw failure
                }
            }
        }

        override fun stop() = synchronized(lock) { stopCurrent(null) }
        override fun stopIf(session: CallAudioSession) = synchronized(lock) { stopCurrent(session) }
        override fun callEnded(session: CallAudioSession) = synchronized(lock) {
            val owned = last?.takeIf { it.scope === this && it.session.sameCall(session) } ?: return@synchronized
            if (retired || closed || current != null && current !== owned) return@synchronized
            revision++
            current = null
            last = null
            ending = owned
            delegate.callEnded(session)
        }
        fun retire() = synchronized(lock) {
            retired = true; stopCurrent(null)
            if (last?.scope === this) last = null
            if (ending?.scope === this) { ending = null; delegate.stop() }
        }

        private fun stopCurrent(expected: CallAudioSession?) {
            val owned = current?.takeIf { it.scope === this }
            if (expected != null && (owned == null || !owned.session.sameSession(expected))) return
            revision++
            if (owned != null) {
                current = null
                delegate.stop()
            }
        }
    }

    private class Claim(val scope: Scoped, val session: CallAudioSession)
}
