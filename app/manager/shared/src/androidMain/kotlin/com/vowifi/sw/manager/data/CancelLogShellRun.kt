package com.vowifi.sw.manager.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal class CancelLogShellRun(
    private val start: (String) -> Process = { ProcessBuilder("su", "-c", it).redirectErrorStream(true).start() },
    private val timeoutSeconds: Long = 20,
) : LogCommand {
    override suspend fun run(command: String): AndroidSuResult = withContext(Dispatchers.IO) {
        require(allowed.matches(command))
        val stopped = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        try {
            suspendCancellableCoroutine { continuation ->
                val worker = thread(start = false, isDaemon = true, name = "log-shell") {
                    try {
                        val result = execute(command, cancelled)
                        continuation.resume(result)
                    } catch (error: IOException) {
                        continuation.resumeWithException(error)
                    } catch (_: InterruptedException) {
                        continuation.cancel()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
                continuation.invokeOnCancellation {
                    cancelled.set(true)
                    worker.interrupt()
                }
                worker.start()
            }
        } finally {
            withContext(NonCancellable) { stopped.await() }
        }
    }

    private fun execute(command: String, cancelled: AtomicBoolean): AndroidSuResult {
        if (cancelled.get() || Thread.interrupted()) throw InterruptedException()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        val process = start(command)
        val collected = ByteArrayOutputStream()
        var overflow = false
        var readFailed = false
        var finished = false
        try {
            if (cancelled.get() || Thread.interrupted()) throw InterruptedException()
            process.outputStream.close()
            val input = process.inputStream
            val buffer = ByteArray(4096)
            while (true) {
                if (cancelled.get() || Thread.interrupted()) throw InterruptedException()
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return AndroidSuResult(true, -1, "", false)
                if (!readFailed) {
                    try {
                        val ready = input.available()
                        if (ready > 0) {
                            val count = input.read(buffer, 0, minOf(buffer.size, ready))
                            if (count > 0) {
                                val capacity = MAX_BYTES - collected.size()
                                if (count > capacity) overflow = true
                                if (capacity > 0) collected.write(buffer, 0, minOf(count, capacity))
                                continue
                            }
                        }
                    } catch (_: IOException) {
                        readFailed = true
                    }
                }
                if (finished) break
                finished = process.waitFor(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(20)), TimeUnit.NANOSECONDS)
            }
            val invalid = overflow || readFailed
            return AndroidSuResult(false, process.exitValue(), if (invalid) "" else collected.toString("UTF-8").trim(), invalid)
        } finally {
            Thread.interrupted()
            try {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
            } finally {
                Thread.interrupted()
                closeStream { process.outputStream.close() }
                closeStream { process.inputStream.close() }
                closeStream { process.errorStream.close() }
            }
        }
    }

    private fun closeStream(close: () -> Unit) {
        runCatching(close)
    }

    private companion object {
        const val MAX_BYTES = 262_144
        val allowed = Regex("/data/adb/modules/vowifi_sw/control\\.sh logs (?:catalog|list|read [0-9a-f]{64}|tail f(?:[1-9]|[12][0-9]|3[0-2]))")
    }
}
