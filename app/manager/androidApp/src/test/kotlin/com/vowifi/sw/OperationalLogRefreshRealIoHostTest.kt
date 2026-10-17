@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidOperationalLogSource
import com.vowifi.sw.manager.data.CancelLogShellRun
import com.vowifi.sw.manager.model.OperationalLogUiState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

// R5/L2：旧 API 刷新行为回归，复用现有 runner/真实进程清理辅助。
class OperationalLogRefreshRealIoHostTest {
    @Test(timeout = 10000)
    fun repeatedRefreshKeepsOneRealCatalogAndTailChainIncludingSameValueReadback() {
        val host = HostLogProcesses()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<String>()
        val reads = AtomicInteger()
        val key = "a".repeat(64)
        val catalog = """{"ok":true,"rotatedCount":0,"files":[{"key":"$key","name":"engine.log","suffix":"0001"}]}"""
        val tail = """{"ok":true,"key":"$key","truncated":false,"lines":["2026-09-11 12:00:00.000 [INFO] [ims] [0001] safe event"]}"""

        host.bounded {
            runBlocking {
                val job = SupervisorJob()
                val runner = CancelLogShellRun({ command ->
                    calls += command
                    val isRead = command.endsWith("read $key")
                    val output = if (isRead) tail else catalog
                    val process = host.start("printf '%s' '$output'")
                    if (isRead && reads.incrementAndGet() == 2) {
                        // 屏障位于生产 runner 的实际进程工厂，不替换 IO/codec。
                        entered.countDown()
                        try {
                            if (!release.await(3, TimeUnit.SECONDS)) {
                                process.destroyForcibly()
                                throw java.io.IOException("日志测试屏障未释放")
                            }
                        } catch (_: InterruptedException) {
                            // 和 RealLogShellRunTest 一致：返回已启动进程供 runner 回收。
                            Thread.currentThread().interrupt()
                        }
                    }
                    process
                })
                val source = AndroidOperationalLogSource(runner, CoroutineScope(job + Dispatchers.Default))
                suspend fun finished() = withTimeout(3000) {
                    source.state.first { it is OperationalLogUiState.Ready && !it.loadInfo.reading }
                        as OperationalLogUiState.Ready
                }
                try {
                    val before = finished()
                    source.refresh()
                    withContext(Dispatchers.IO) { assertTrue(entered.await(3, TimeUnit.SECONDS)) }
                    source.refresh()
                    source.refresh()
                    release.countDown()
                    val done = finished()

                    assertEquals(before, done)
                    assertEquals(2, reads.get())
                    assertEquals(listOf("catalog", "read $key", "catalog", "read $key"),
                        calls.map { it.substringAfter(" logs ") })
                } finally {
                    release.countDown()
                    source.close()
                    withTimeout(3000) { job.join() }
                    host.assertStopped()
                }
            }
        }
    }
}
