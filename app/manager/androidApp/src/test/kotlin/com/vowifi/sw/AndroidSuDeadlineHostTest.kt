@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidSuCommand
import com.vowifi.sw.manager.data.AndroidConfigurationCommands
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSuDeadlineHostTest {
    @Test fun timeoutIncludesActualBlockedStdinPipe() {
        val process = ProcessBuilder("python3", "-c", "import time; print('synthetic-pipe-fixture', flush=True); time.sleep(3)").start()
        val command = AndroidSuCommand(timeoutSeconds = 1, start = { process })
        val before = System.nanoTime()
        try {
            val result = command.run("synthetic-no-root", ByteArray(1024 * 1024) { 120 })
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)
            assertTrue("one second deadline did not cover stdin: elapsed=${elapsedMs}ms", elapsedMs < 2_500)
            assertTrue(result.timedOut)
            assertEquals(-1, result.exitCode)
            assertEquals("", result.output)
            assertFalse(process.isAlive)
            assertNoInputWorker()
        } finally { process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS) }
    }

    @Test fun writeAndResponseWaitShareOneDeadline() {
        withProcess("import sys,time; time.sleep(.6); sys.stdin.buffer.read(); time.sleep(.7); print('late')") { process ->
            val result = AndroidSuCommand(timeoutSeconds = 1, start = { process }).run("synthetic", ByteArray(1024 * 1024))
            assertTrue("response wait restarted the input deadline", result.timedOut)
            assertFalse(process.isAlive)
            assertNoInputWorker()
        }
    }

    @Test fun textAndBytesReachActualPipeAndRetainUtf8() {
        for (bytes in listOf(false, true)) withProcess("import sys; sys.stdout.buffer.write(sys.stdin.buffer.read())") { process ->
            val command = AndroidSuCommand(start = { process })
            val text = "synthetic-é-漢字"
            val result = if (bytes) command.run("synthetic", text.encodeToByteArray()) else command.run("synthetic", text)
            assertFalse(result.timedOut)
            assertFalse(result.writeFailed)
            assertEquals(0, result.exitCode)
            assertEquals(text, result.output)
            assertNoInputWorker()
        }
    }

    @Test fun noInputStillClosesStdinAndReclaimsProcess() {
        withProcess("import sys; print('length=' + str(len(sys.stdin.buffer.read())))") { process ->
            val result = AndroidSuCommand(start = { process }).run("synthetic")
            assertEquals("length=0", result.output)
            assertFalse(result.writeFailed)
            assertFalse(process.isAlive)
            assertNoInputWorker()
        }
    }

    @Test fun earlyStdinCloseIsWriteFailureEvenWhenChildExitsZero() {
        withProcess("import os; os.close(0); print('not-applied')") { process ->
            val result = AndroidSuCommand(start = { process }).run("synthetic", ByteArray(1024 * 1024))
            assertFalse(result.timedOut)
            assertEquals(0, result.exitCode)
            assertTrue(result.writeFailed)
            assertNoInputWorker()
        }
    }

    @Test fun outputCeilingAndNonzeroExitAreNotSuccess() {
        for (length in listOf(63, 64, 65)) withProcess("import sys; print('x'*$length, end=''); sys.exit(7)") { process ->
            val result = AndroidSuCommand(maxBytes = 64, start = { process }).run("synthetic")
            assertEquals(7, result.exitCode)
            assertEquals(length >= 64, result.overflowed)
            assertEquals(if (length >= 64) "" else "x".repeat(length), result.output)
            assertNoInputWorker()
        }
    }

    @Test fun callerCancellationReclaimsConfigurationInputWorkerAndProcess() = runBlocking {
        val process = ProcessBuilder("python3", "-c", "import time; time.sleep(3)").start()
        val invoked = CompletableDeferred<Unit>()
        val command = AndroidSuCommand(start = { invoked.complete(Unit); process })
        val request = async(Dispatchers.IO) { AndroidConfigurationCommands(command).applyTracked("123456789012345", "x".repeat(1024 * 1024)) }
        try {
            withTimeout(2_000) { invoked.await() }
            withTimeout(2_000) { request.cancelAndJoin() }
            assertFalse(process.isAlive)
            assertNoInputWorker()
        } finally {
            process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS)
            withTimeout(2_000) { request.join() }
        }
    }

    private fun withProcess(script: String, check: (Process) -> Unit) {
        val process = ProcessBuilder("python3", "-c", script).start()
        try { check(process) } finally { process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS) }
    }

    private fun assertNoInputWorker() {
        assertTrue("input worker survived the command", Thread.getAllStackTraces().keys.none { it.name == "vowifi-su-input" && it.isAlive })
    }
}
