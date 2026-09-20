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

internal class AndroidSuCommand(
    private val timeoutSeconds: Long = 20,
    private val maxBytes: Int = 262_144,
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
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val collected = ByteArrayOutputStream()
        val readFailed = AtomicBoolean(false)
        val writeFailed = AtomicBoolean(false)
        val drain = Thread {
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val n = process.inputStream.read(buffer)
                    if (n < 0) break
                    val allowed = maxBytes - collected.size()
                    if (allowed > 0) collected.write(buffer, 0, minOf(n, allowed))
                }
            } catch (_: IOException) {
                readFailed.set(true)
            }
        }
        drain.start()
        if (stdin != null) {
            try {
                process.outputStream.use { stream ->
                    stream.write(stdin)
                    stream.flush()
                }
            } catch (_: IOException) {
                writeFailed.set(true)
            }
        } else {
            runCatching { process.outputStream.close() }
        }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            drain.join(1_000)
            return AndroidSuResult(
                timedOut = true,
                exitCode = -1,
                output = "",
                overflowed = false,
                writeFailed = writeFailed.get(),
            )
        }
        drain.join(1_000)
        val overflowed = readFailed.get() || collected.size() >= maxBytes
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
