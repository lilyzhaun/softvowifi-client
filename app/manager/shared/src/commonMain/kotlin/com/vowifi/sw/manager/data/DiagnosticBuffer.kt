package com.vowifi.sw.manager.data

/** Single-owner core; Android synchronizes only these in-memory operations, never disk IO. */
internal class DiagnosticBuffer(private val run: String, private val pid: Int, maxQueuedBytes: Int = 512 * 1024) {
    private val byteLimit = maxQueuedBytes.coerceIn(2048, 512 * 1024)
    private data class Key(val component: DiagnosticComponent, val slot: Int?, val stage: DiagnosticStage, val reason: DiagnosticReason)
    private data class Seen(val event: DiagnosticEvent, val first: Long, var last: Long,
        var reportedAt: Long, var pending: Long = 0)
    private data class Pending(val bytes: ByteArray, val priority: Boolean)
    private val queue = ArrayDeque<Pending>()
    private val seen = LinkedHashMap<Key, Seen>()
    private var queuedBytes = 0
    private var dropped = 0L
    private var evicted = 0L
    private var coalesced = 0L
    private var window = Long.MIN_VALUE
    private var totalInWindow = 0
    private var ordinaryInWindow = 0
    private var closed = false

    fun offer(event: DiagnosticEvent, wall: Long, mono: Long): Boolean {
        if (closed) return false
        if (!event.valid() || wall < 0 || mono < 0) { dropped = increment(dropped); return false }
        val key = Key(event.component, event.slot, event.stage, event.reason)
        // Reason changes and recovery settle pending repeats, including their actual last occurrence.
        val obsolete = seen.keys.filter { it.component == key.component && it.slot == key.slot &&
            it.stage == key.stage && it != key }
        for (previous in obsolete) settle(seen.remove(previous)!!, wall, mono)
        val previous = seen.remove(key)
        if (previous != null && previous.event == event) {
            previous.pending = increment(previous.pending)
            previous.last = mono
            coalesced = increment(coalesced)
            seen[key] = previous
            if (mono - previous.reportedAt >= 60_000) {
                previous.reportedAt = mono
                return settle(previous, wall, mono)
            }
            return true // accepted into the bounded coalescer, not a disk acknowledgement
        }
        if (previous != null) settle(previous, wall, mono)
        if (seen.size >= 128) {
            settle(seen.remove(seen.keys.first())!!, wall, mono)
            evicted = increment(evicted)
        }
        seen[key] = Seen(event, mono, mono, mono)
        return enqueue(event, wall, mono)
    }

    private fun settle(value: Seen, wall: Long, mono: Long): Boolean {
        if (value.pending == 0L) return true
        val accepted = enqueue(value.event.copy(name = DiagnosticName.SUPPRESSED), wall, mono,
            value.pending, value.first, value.last)
        if (accepted) value.pending = 0
        return accepted
    }

    private fun enqueue(event: DiagnosticEvent, wall: Long, mono: Long, suppressed: Long = 0,
        first: Long = mono, last: Long = mono): Boolean {
        // Fixed monotonic one-second buckets; no state/reason/error can bypass this budget.
        val second = mono / 1000
        if (second != window) { window = second; totalInWindow = 0; ordinaryInWindow = 0 }
        if (totalInWindow >= 20 || !event.priority && ordinaryInWindow >= 16) {
            dropped = increment(dropped); return false
        }
        var bytes = DiagnosticRecord(event, run, pid, wall, mono, suppressed, first, last, dropped, evicted).bytes()
        if (bytes.size > 2048) { dropped = increment(dropped); return false }
        if (event.priority) {
            // Finite priority: evict only an ordinary pending record, never allocate beyond the cap.
            while (queue.size >= 256 || queuedBytes + bytes.size > byteLimit) {
                val index = queue.indexOfFirst { !it.priority }
                if (index < 0) break
                queuedBytes -= queue.removeAt(index).bytes.size
                dropped = increment(dropped)
                bytes = DiagnosticRecord(event, run, pid, wall, mono, suppressed, first, last, dropped, evicted).bytes()
            }
        }
        if (queue.size >= 256 || queuedBytes + bytes.size > byteLimit) {
            dropped = increment(dropped); return false
        }
        queue.addLast(Pending(bytes, event.priority)); queuedBytes += bytes.size
        totalInWindow++
        if (!event.priority) ordinaryInWindow++
        return true
    }

    fun poll(): ByteArray? = queue.removeFirstOrNull()?.bytes?.also { queuedBytes -= it.size }
    fun stats() = DiagnosticStats(queue.size, queuedBytes, seen.size, dropped, evicted, coalesced)

    fun finish(wall: Long, mono: Long) {
        if (closed) return
        for (value in seen.values) settle(value, wall, mono)
        seen.clear()
        offer(DiagnosticEvent(DiagnosticComponent.APP, DiagnosticLevel.INFO, DiagnosticName.PROCESS_STOPPING,
            DiagnosticStage.STOP, DiagnosticResult.STOPPED), wall, mono)
        closed = true
    }

    private fun increment(value: Long): Long = (value + 1).coerceAtMost(1_000_000_000L)
}
