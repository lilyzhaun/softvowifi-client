@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.CancelLogShellRun
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CancelLogShellRunFailureHostTest {
    private val command = "/data/adb/softvowifi/runtime/control.sh logs catalog"

    @Test
    fun startThrowingSecurityExceptionResumesWithExceptionInsteadOfHanging() = runBlocking {
        val runner = CancelLogShellRun(start = { throw SecurityException("synthetic start denied") })
        var caught: Throwable? = null
        try {
            withTimeout(1_000) { runner.run(command) }
        } catch (error: Throwable) {
            caught = error
        }
        assertTrue("expected SecurityException without hanging, got $caught", caught is SecurityException)
    }

    @Test
    fun exitValueThrowingIllegalThreadStateResumesWithExceptionAndClosesStreams() = runBlocking {
        val stdinClosed = AtomicBoolean(false)
        val stdoutClosed = AtomicBoolean(false)
        val stderrClosed = AtomicBoolean(false)
        val process = object : Process() {
            override fun getOutputStream(): OutputStream = object : ByteArrayOutputStream() {
                override fun close() { stdinClosed.set(true) }
            }
            override fun getInputStream(): InputStream = object : ByteArrayInputStream(byteArrayOf()) {
                override fun close() { stdoutClosed.set(true) }
            }
            override fun getErrorStream(): InputStream = object : ByteArrayInputStream(byteArrayOf()) {
                override fun close() { stderrClosed.set(true) }
            }
            override fun waitFor(): Int = 0
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
            override fun exitValue(): Int = throw IllegalThreadStateException("synthetic not exited")
            override fun destroy() = Unit
            override fun destroyForcibly(): Process = this
        }
        val runner = CancelLogShellRun(start = { process })
        var caught: Throwable? = null
        try {
            withTimeout(1_000) { runner.run(command) }
        } catch (error: Throwable) {
            caught = error
        }
        assertTrue("expected IllegalThreadStateException without hanging, got $caught", caught is IllegalThreadStateException)
        assertTrue("stdin not closed", stdinClosed.get())
        assertTrue("stdout not closed", stdoutClosed.get())
        assertTrue("stderr not closed", stderrClosed.get())
    }

    @Test
    fun destroyForciblyRuntimeFailureInFinallyDoesNotMaskValidOutputOrSkipStreamClose() = runBlocking {
        val stdinClosed = AtomicBoolean(false)
        val stdoutClosed = AtomicBoolean(false)
        val stderrClosed = AtomicBoolean(false)
        val process = object : Process() {
            private val input = ByteArrayInputStream("{\"ok\":true}".toByteArray())
            override fun getOutputStream(): OutputStream = object : ByteArrayOutputStream() {
                override fun close() { stdinClosed.set(true) }
            }
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int = input.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len)
                override fun available(): Int = input.available()
                override fun close() { stdoutClosed.set(true) }
            }
            override fun getErrorStream(): InputStream = object : ByteArrayInputStream(byteArrayOf()) {
                override fun close() { stderrClosed.set(true) }
            }
            override fun waitFor(): Int = 0
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
            override fun exitValue(): Int = 0
            override fun destroy() = Unit
            override fun destroyForcibly(): Process = throw IllegalStateException("synthetic destroy failed")
        }
        val runner = CancelLogShellRun(start = { process })
        val result = withTimeout(1_000) { runner.run(command) }
        assertFalse(result.timedOut)
        assertFalse(result.overflowed)
        assertEquals(0, result.exitCode)
        assertEquals("{\"ok\":true}", result.output)
        assertTrue("stdin not closed", stdinClosed.get())
        assertTrue("stdout not closed", stdoutClosed.get())
        assertTrue("stderr not closed", stderrClosed.get())
    }
}
