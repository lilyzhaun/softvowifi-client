package com.vowifi.sw.manager.data

import kotlin.time.TimeSource

class CallSessionClock internal constructor(confirmedElapsedMs: Long, private val now: () -> Long) {
    private val initial = confirmedElapsedMs.coerceIn(0, MAX_MILLIS)
    private val anchor = now()
    fun elapsedMs(): Long = initial + (now() - anchor).coerceIn(0, MAX_MILLIS - initial)
    fun formatted(): String {
        val seconds = elapsedMs() / 1000
        val minutes = (seconds / 60 % 60).toString().padStart(2, '0')
        val remainder = (seconds % 60).toString().padStart(2, '0')
        return if (seconds < 3600) "$minutes:$remainder"
        else "${(seconds / 3600).toString().padStart(2, '0')}:$minutes:$remainder"
    }
    override fun toString(): String = "CallSessionClock"
    companion object {
        private const val MAX_MILLIS = 359_999_000L
        internal fun monotonic(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
    }
}
