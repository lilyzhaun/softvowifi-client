package com.vowifi.sw.manager.data

import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class DiagnosticWriterStatus(
    val memory: DiagnosticStats, val writeDropped: Long, val failure: DiagnosticReason?,
)

internal class AsyncDiagnosticWriter(
    directory: () -> Path?,
    pid: Int,
    private val monotonic: () -> Long,
    private val wall: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val open: (Path) -> DiagnosticFiles = { DiagnosticFiles(it) },
) {
    private val lock = Any()
    private val startedAt = monotonic()
    private val buffer = DiagnosticBuffer(UUID.randomUUID().toString(), pid)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var failed: DiagnosticReason? = null
    private var writeDropped = 0L
    private var closing = false
    private val job = scope.launch {
        try {
            val path = directory()
            if (path == null) { fail(DiagnosticReason.UNAVAILABLE); return@launch }
            val files = open(path)
            var writeWindow = monotonic()
            var writes = 0
            files.use {
                for (signal in wake) {
                    while (true) {
                        val bytes = synchronized(lock) { buffer.poll() } ?: break
                        // Admission and disk output are both bounded: a stalled writer cannot flush
                        // its entire backlog as an unbounded recovery burst.
                        if (files.failure == null) {
                            val elapsed = (monotonic() - writeWindow).coerceAtLeast(0)
                            if (writes >= 20 && elapsed < 1000) delay(1000 - elapsed)
                            if (monotonic() - writeWindow >= 1000) { writeWindow = monotonic(); writes = 0 }
                            writes++
                        }
                        if (!files.append(bytes)) synchronized(lock) {
                            failed = files.failure ?: DiagnosticReason.IO_FAILED
                            writeDropped = (writeDropped + 1).coerceAtMost(1_000_000_000L)
                        }
                    }
                }
            }
            files.failure?.let(::fail)
        } catch (_: IOException) { fail(DiagnosticReason.IO_FAILED) }
        catch (_: SecurityException) { fail(DiagnosticReason.PERMISSION_DENIED) }
    }

    fun emit(event: DiagnosticEvent): Boolean = synchronized(lock) {
        if (closing) return@synchronized false
        if (failed != null) {
            writeDropped = (writeDropped + 1).coerceAtMost(1_000_000_000L)
            return@synchronized false
        }
        buffer.offer(event, wall(), (monotonic() - startedAt).coerceAtLeast(0)).also { wake.trySend(Unit) }
    }

    fun status(): DiagnosticWriterStatus = synchronized(lock) { DiagnosticWriterStatus(buffer.stats(), writeDropped, failed) }

    /** Nonblocking request. Android process death has no guaranteed callback and can lose this tail. */
    fun close() = synchronized(lock) {
        if (!closing) {
            buffer.finish(wall(), (monotonic() - startedAt).coerceAtLeast(0))
            closing = true
            wake.trySend(Unit)
            wake.close()
        }
    }

    internal suspend fun awaitClosed() = job.join()

    private fun fail(reason: DiagnosticReason) = synchronized(lock) {
        failed = reason
        while (buffer.poll() != null) writeDropped = (writeDropped + 1).coerceAtMost(1_000_000_000L)
        wake.close()
    }
}
