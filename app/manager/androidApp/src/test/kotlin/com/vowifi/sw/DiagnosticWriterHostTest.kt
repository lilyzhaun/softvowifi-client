@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.*
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.cancel
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
        val currentWorker = ThreadLocal<Job?>()
        // IO coroutines may resume on another thread; identify the consumer Job, not its carrier thread.
        val identity = object : ThreadContextElement<Job?> {
            override val key = object : CoroutineContext.Key<ThreadContextElement<Job?>> {}
            override fun updateThreadContext(context: CoroutineContext): Job? {
                val previous = currentWorker.get()
                currentWorker.set(context[Job])
                return previous
            }
            override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) {
                currentWorker.set(oldState)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + identity)
        val writerWorkers = ConcurrentHashMap.newKeySet<Job>()
        val writes = AtomicInteger()
        val activeWrites = AtomicInteger()
        val maxActiveWrites = AtomicInteger()
        val writer = AsyncDiagnosticWriter({ temporary.root.toPath().resolve("logs") }, 123, ::now,
            scope = scope,
            open = { path -> DiagnosticFiles(path, writeBytes = { channel, bytes ->
                writerWorkers += requireNotNull(currentWorker.get())
                writes.incrementAndGet()
                maxActiveWrites.accumulateAndGet(activeWrites.incrementAndGet(), ::maxOf)
                try {
                    entered.countDown()
                    if (!release.await(5, TimeUnit.SECONDS)) throw IOException("synthetic blocked disk")
                    channel.write(bytes)
                } finally { activeWrites.decrementAndGet() }
            }) })
        try {
            writer.emit(event())
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(executor.submit<Boolean> { writer.emit(event(1)) }.get(2, TimeUnit.SECONDS))
            assertTrue(writer.status().memory.queued <= 256)
        } finally {
            release.countDown(); writer.close()
            try { withTimeout(5000) { writer.awaitClosed() } }
            finally { scope.cancel(); executor.shutdownNow() }
        }
        assertTrue("至少两次真实写入来自同一个消费者", writes.get() >= 2)
        assertEquals("不能为每个事件启动新的消费者协程", 1, writerWorkers.size)
        assertNotSame("记录的是实际消费者而非共用父Job", scope.coroutineContext[Job], writerWorkers.single())
        assertEquals("磁盘写入必须串行", 1, maxActiveWrites.get())
    }

    @Test fun diskFullDisablesFurtherWritesAndCountsLossWithoutRecursiveLogging() = runBlocking {
        var writes = 0
        val directory = temporary.root.toPath().resolve("logs")
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
