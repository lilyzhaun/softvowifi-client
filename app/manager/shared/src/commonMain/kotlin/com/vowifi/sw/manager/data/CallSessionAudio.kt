package com.vowifi.sw.manager.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun CallAudioSession.sameSession(other: CallAudioSession): Boolean =
    iccid == other.iccid && token == other.token && generation == other.generation && transport === other.transport

internal class CallSessionAudio(private val delegate: CallAudioController) {
    private val lock = Any()
    private val starts = Mutex()
    private var current: Claim? = null
    private var closed = false

    fun child(): Scoped = Scoped()

    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            current = null
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
                    Claim(this, session).also { current = it }
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
        fun retire() = synchronized(lock) { retired = true; stopCurrent(null) }

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
