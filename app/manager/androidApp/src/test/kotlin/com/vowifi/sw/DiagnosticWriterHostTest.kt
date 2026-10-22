@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.*
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticWriterHostTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun event(count: Int = 0) = DiagnosticEvent(DiagnosticComponent.APP, DiagnosticLevel.ERROR,
        DiagnosticName.REJECTED, DiagnosticStage.PROCESS, DiagnosticResult.FAILURE,
        DiagnosticReason.IO_FAILED, count = count)
    private fun now() = System.nanoTime() / 1_000_000

    @Test fun blockedDiskDoesNotHoldProducerLockOrSpawnPerEventWorkers() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val writerThreads = mutableSetOf<Long>()
        val writer = AsyncDiagnosticWriter({ temporary.root.toPath().resolve("diagnostics-v1") }, 123, ::now,
            open = { path -> DiagnosticFiles(path, writeBytes = { channel, bytes ->
                writerThreads += Thread.currentThread().id
                entered.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) throw IOException("synthetic blocked disk")
                channel.write(bytes)
            }) })
        try {
            writer.emit(event())
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(executor.submit<Boolean> { writer.emit(event(1)) }.get(2, TimeUnit.SECONDS))
            assertTrue(writer.status().memory.queued <= 256)
        } finally {
            release.countDown(); writer.close()
            withTimeout(5000) { writer.awaitClosed() }
            executor.shutdownNow()
        }
        assertEquals(1, writerThreads.size)
    }

    @Test fun diskFullDisablesFurtherWritesAndCountsLossWithoutRecursiveLogging() = runBlocking {
        var writes = 0
        val directory = temporary.root.toPath().resolve("diagnostics-v1")
        val writer = AsyncDiagnosticWriter({ directory }, 123, ::now, open = { path ->
            DiagnosticFiles(path, writeBytes = { _, _ -> writes++; throw IOException("synthetic ENOSPC") })
        })
        repeat(1000) { writer.emit(event(it)) }
        writer.close()
        withTimeout(5000) { writer.awaitClosed() }
        assertEquals(1, writes)
        assertEquals(DiagnosticReason.IO_FAILED, writer.status().failure)
        assertTrue(writer.status().writeDropped > 0)
        assertTrue(Files.size(directory.resolve("app.jsonl")) <= 1024 * 1024)
        assertFalse(writer.emit(event()))
    }
}
