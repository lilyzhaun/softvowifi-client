@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidRootDiagnosticStream
import com.vowifi.sw.manager.data.AndroidSuCommand
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class RootProcessCleanupHostTest {

    private class FakeRootProcess(
        private val stdout: ByteArray = ByteArray(0),
        private val exit: Int = 0,
        private val failInputClose: Boolean = false,
        private val blockOutputWrite: Boolean = false,
        private val reInterruptOnFirstWait: Boolean = false,
        private val stayAliveUntilDestroy: Boolean = false,
    ) : Process() {
        val destroyed = AtomicBoolean(false)
        val inputClosed = AtomicBoolean(false)
        val errorClosed = AtomicBoolean(false)
        val outputClosed = AtomicBoolean(false)
        val writeBlockedUnblockedByCloseOrInterrupt = AtomicBoolean(false)
        private val unblockWrite = CountDownLatch(1)
        private val exited = CountDownLatch(if (stayAliveUntilDestroy) 1 else 0)
        private val waitCalls = AtomicBoolean(false)

        private val delegateIn = ByteArrayInputStream(stdout)
        private val inStream = object : InputStream() {
            override fun read(): Int = delegateIn.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = delegateIn.read(b, off, len)
            override fun available(): Int = delegateIn.available()
            override fun close() {
                inputClosed.set(true)
                if (failInputClose) throw IOException("synthetic input close failure")
            }
        }
        private val errStream = object : InputStream() {
            override fun read(): Int = -1
            override fun close() {
                errorClosed.set(true)
            }
        }
        private val outStream = object : OutputStream() {
            override fun write(b: Int) {
                if (blockOutputWrite) {
                    try {
                        if (!unblockWrite.await(5, TimeUnit.SECONDS)) {
                            throw IOException("write timed out")
                        }
                        writeBlockedUnblockedByCloseOrInterrupt.set(true)
                        throw IOException("output closed")
                    } catch (_: InterruptedException) {
                        writeBlockedUnblockedByCloseOrInterrupt.set(true)
                        throw IOException("output interrupted")
                    }
                }
            }

            override fun close() {
                outputClosed.set(true)
                unblockWrite.countDown()
            }
        }

        override fun getOutputStream(): OutputStream = outStream
        override fun getInputStream(): InputStream = inStream
        override fun getErrorStream(): InputStream = errStream
        override fun waitFor(): Int {
            exited.await()
            return exit
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (reInterruptOnFirstWait && waitCalls.compareAndSet(false, true)) {
                Thread.currentThread().interrupt()
                throw InterruptedException("synthetic interrupt")
            }
            if (Thread.interrupted()) {
                throw InterruptedException("caller still interrupted")
            }
            return exited.await(timeout, unit)
        }

        override fun exitValue(): Int = exit
        override fun destroy() {
            destroyed.set(true)
            exited.countDown()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    @Test
    fun suCommandClosesErrorStreamAndPreservesResultWhenInputStreamCloseThrows() {
        val process = FakeRootProcess(stdout = "ok\n".toByteArray(), exit = 0, failInputClose = true)
        val result = AndroidSuCommand(timeoutSeconds = 2, start = { process }).run("synthetic")
        assertFalse(result.timedOut)
        assertEquals(0, result.exitCode)
        assertEquals("ok", result.output)
        assertTrue("inputStream.close() was not called", process.inputClosed.get())
        assertTrue("errorStream.close() was skipped after inputStream.close() threw", process.errorClosed.get())
    }

    @Test(timeout = 6000)
    fun suCommandInterruptsBlockedInputWorkerAndClosesOutputStreamOnTimeout() {
        val process = FakeRootProcess(blockOutputWrite = true, stayAliveUntilDestroy = true)
        val result = AndroidSuCommand(timeoutSeconds = 1, start = { process }).run("synthetic", "payload")
        assertTrue("expected timedOut=true", result.timedOut)
        assertTrue("outputStream was not closed in finally", process.outputClosed.get())
        assertTrue("blocked input worker was not unblocked in finally", process.writeBlockedUnblockedByCloseOrInterrupt.get())
        assertTrue("errorStream was not closed", process.errorClosed.get())
    }

    @Test
    fun suCommandCleanupStillClosesBothStreamsWhenInterruptArrivesBeforeFinally() {
        val process = FakeRootProcess(reInterruptOnFirstWait = true, stayAliveUntilDestroy = true)
        var caught: Throwable? = null
        try {
            AndroidSuCommand(timeoutSeconds = 2, start = { process }).run("synthetic")
        } catch (error: Throwable) {
            caught = error
        } finally {
            Thread.interrupted()
        }
        assertTrue("expected InterruptedException, got $caught", caught is InterruptedException)
        assertTrue("inputStream was not closed when interrupted before finally", process.inputClosed.get())
        assertTrue("errorStream was not closed when interrupted before finally", process.errorClosed.get())
    }

    @Test
    fun diagnosticStreamClosesErrorStreamAndPreservesReceiptWhenInputStreamCloseThrows() {
        val receipt =
            """{"diagnostic_export":1,"complete":true,"records":0,"invalid":0,"unavailable":0,"legacy_excluded":true,"older_runs_may_lack_dns_io":true}""" + "\n"
        val process = FakeRootProcess(stdout = receipt.toByteArray(), exit = 0, failInputClose = true)
        val result = AndroidRootDiagnosticStream(start = { process }, seconds = 2).read(0, 2000) {}
        assertTrue("expected complete receipt despite inputStream.close() failure", result.complete)
        assertTrue("inputStream was not closed", process.inputClosed.get())
        assertTrue("errorStream was skipped after inputStream.close() threw", process.errorClosed.get())
    }
}
