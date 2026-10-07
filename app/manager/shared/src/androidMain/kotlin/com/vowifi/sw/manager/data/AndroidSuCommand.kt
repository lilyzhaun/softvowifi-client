package com.vowifi.sw.manager.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class AndroidSuResult(
    val timedOut: Boolean,
    val exitCode: Int,
    val output: String,
    val overflowed: Boolean,
    val writeFailed: Boolean = false,
) {
    fun toOutcome(): ConfigurationCommandOutcome = ConfigurationCommandOutcome(
        timedOut = timedOut,
        overflowed = overflowed,
        writeFailed = writeFailed,
        exitCode = exitCode,
        output = output,
    )
}

internal class AndroidSuCommand @JvmOverloads constructor(
    private val timeoutSeconds: Long = 20,
    private val maxBytes: Int = 262_144,
    private val start: (String) -> Process = { ProcessBuilder("su", "-c", it).redirectErrorStream(true).start() },
) {
    fun run(command: String): AndroidSuResult = execute(command, stdin = null)

    fun run(
        command: String,
        stdinText: String,
        timeoutSeconds: Long = this.timeoutSeconds,
    ): AndroidSuResult = execute(command, stdinText.toByteArray(Charsets.UTF_8), timeoutSeconds)

    fun run(
        command: String,
        stdinBytes: ByteArray,
        timeoutSeconds: Long = this.timeoutSeconds,
    ): AndroidSuResult = execute(command, stdinBytes, timeoutSeconds)

    private fun execute(
        command: String,
        stdin: ByteArray?,
        timeoutSeconds: Long = this.timeoutSeconds,
    ): AndroidSuResult {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        val process = start(command)
        val collected = ByteArrayOutputStream()
        var readFailed = false
        val writeFailed = AtomicBoolean(false)
        val inputWorker = Thread({
            try {
                process.outputStream.use { stream ->
                    if (stdin != null) {
                        stream.write(stdin)
                        stream.flush()
                    }
                }
            } catch (_: IOException) {
                writeFailed.set(true)
            }
        }, "vowifi-su-input").apply { isDaemon = true }
        var finished = false
        var timedOut = false
        inputWorker.start()
        try {
            val input = process.inputStream
            val buffer = ByteArray(4096)
            while (true) {
                if (Thread.interrupted()) throw InterruptedException()
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) { timedOut = true; break }
                if (!readFailed) try {
                    val available = input.available()
                    if (available > 0) {
                        val n = input.read(buffer, 0, minOf(available, buffer.size))
                        if (n > 0) {
                            val allowed = maxBytes - collected.size()
                            if (allowed > 0) collected.write(buffer, 0, minOf(n, allowed))
                            continue
                        }
                    }
                } catch (_: IOException) {
                    readFailed = true
                }
                if (finished) break
                finished = process.waitFor(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(20)), TimeUnit.NANOSECONDS)
            }
            val remaining = deadline - System.nanoTime()
            if (remaining > 0) TimeUnit.NANOSECONDS.timedJoin(inputWorker, remaining)
            timedOut = timedOut || inputWorker.isAlive
        } finally {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            inputWorker.join(1_000)
            process.inputStream.close()
            process.errorStream.close()
            if (inputWorker.isAlive) throw IOException("Root command input worker did not stop")
        }
        if (timedOut) {
            return AndroidSuResult(
                timedOut = true,
                exitCode = -1,
                output = "",
                overflowed = false,
                writeFailed = writeFailed.get(),
            )
        }
        val overflowed = readFailed || collected.size() >= maxBytes
        val failedWrite = writeFailed.get()
        val output = if (overflowed) {
            ""
        } else {
            collected.toString(Charsets.UTF_8.name()).trim()
        }
        return AndroidSuResult(
            timedOut = false,
            exitCode = process.exitValue(),
            output = output,
            overflowed = overflowed,
            writeFailed = failedWrite,
        )
    }
}
