@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.CancelLogShellRun
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CancelLogShellRunTest {
    private val command = "/data/adb/modules/vowifi_sw/control.sh logs catalog"

    @Test fun cancelDestroysOwnedProcessClosesStreamsAndStopsDrainBeforeReturning() = runBlocking {
        val process = BlockedProcess()
        val runner = CancelLogShellRun({ process })
        val job = async { runner.run(command) }
        withContext(Dispatchers.IO) { assertTrue(process.waiting.await(3, TimeUnit.SECONDS)) }
        withTimeout(3000) { job.cancelAndJoin() }
        assertEquals(1, process.destroyed.get())
        assertTrue(process.stdinClosed)
        assertTrue(process.output.closed)
        assertTrue(process.errorClosed)
        assertEquals(0, process.output.reads.get())
        assertTrue(HostLogProcesses.logThreads().isEmpty())
    }

    @Test fun normalOutputAndOverflowAreBoundedWithoutRoot() = runBlocking {
        val runner = CancelLogShellRun({ ProcessBuilder("sh", "-c", "printf '{\"ok\":true}'").start() })
        val result = runner.run(command)
        assertEquals("{\"ok\":true}", result.output)
        assertFalse(result.overflowed)
        val large = ImmediateProcess(ByteArray(262145) { 65 })
        assertTrue(CancelLogShellRun({ large }).run(command).overflowed)
    }

    @Test fun timeoutReturnsTypedOutcomeAndStillCleans() = runBlocking {
        val process = BlockedProcess()
        val result = CancelLogShellRun({ process }, timeoutSeconds = 1).run(command)
        assertTrue(result.timedOut)
        assertTrue(process.observedTimeout in 1..TimeUnit.MILLISECONDS.toNanos(20))
        assertEquals(1, process.destroyed.get())
    }

    @Test fun rejectsCommandsOutsideTheReadOnlyAllowlist() = runBlocking {
        var started = false
        try {
            CancelLogShellRun({ started = true; ImmediateProcess(byteArrayOf()) }).run("$command; echo unsafe")
            fail("unsafe command accepted")
        } catch (_: IllegalArgumentException) { assertFalse(started) }
    }

    private class BlockedInput : InputStream() {
        private val release = CountDownLatch(1)
        val reads = AtomicInteger()
        @Volatile var closed = false
        override fun read(): Int { reads.incrementAndGet(); release.await(); return -1 }
        override fun close() { closed = true; release.countDown() }
    }
    private class BlockedProcess : Process() {
        val waiting = CountDownLatch(1)
        val destroyed = AtomicInteger()
        val output = BlockedInput()
        @Volatile var stdinClosed = false
        @Volatile var errorClosed = false
        var observedTimeout = 0L
        override fun getOutputStream(): OutputStream = object : ByteArrayOutputStream() {
            override fun close() { stdinClosed = true }
        }
        override fun getInputStream(): InputStream = output
        override fun getErrorStream(): InputStream = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { errorClosed = true }
        }
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (destroyed.get() > 0) return true
            observedTimeout = unit.toNanos(timeout)
            waiting.countDown()
            CountDownLatch(1).await(timeout, unit)
            return false
        }
        override fun exitValue() = 0
        override fun destroy() { destroyed.incrementAndGet() }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
    private class ImmediateProcess(bytes: ByteArray) : Process() {
        private val input = ByteArrayInputStream(bytes)
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue() = 0
        override fun destroy() = Unit
    }
}
