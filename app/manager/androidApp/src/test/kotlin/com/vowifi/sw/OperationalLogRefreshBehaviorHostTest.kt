@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidOperationalLogSource
import com.vowifi.sw.manager.data.AndroidSuResult
import com.vowifi.sw.manager.data.LogCommand
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

// R5/L2：旧 API 刷新行为回归，辅助模式沿 OperationalLogLatestReadTest，
// 只驱动真实 AndroidOperationalLogSource 的命令边界，调度器复用现成测试类。
class OperationalLogRefreshBehaviorHostTest {
    @Test(timeout = 5000)
    fun refreshKeepsOwnVisibleRowsWhileCatalogAndTailAreHeldButStillPerformsIo() {
        S3LogRefreshFixture().use { fixture ->
            fixture.warm()
            fixture.source.setLevelFilter(OperationalLogLevel.ERROR)
            val before = fixture.ready()
            assertEquals(1, before.entries.size)
            assertTrue(before.filteredEntries.isEmpty())

            fixture.source.refresh()
            val accepted = fixture.ready()
            assertEquals(before.entries, accepted.entries)
            assertEquals(before.selectedFileId, accepted.selectedFileId)
            assertEquals(before.filter, accepted.filter)
            assertTrue(accepted.loadInfo.reading)
            assertFalse("展示旧行不能当作有效 IO 缓存", accepted.loadInfo.cached)

            fixture.drain()
            val catalog = fixture.next("catalog")
            fixture.reply(catalog, fixture.catalog())
            assertEquals(before.entries, fixture.ready().entries)
            assertTrue(fixture.ready().loadInfo.reading)
            val read = fixture.next("read ${fixture.first}")
            fixture.reply(read, fixture.tail(fixture.first))

            assertEquals(before, fixture.ready())
            assertEquals(4, fixture.commands.size)
            assertFalse(fixture.ready().loadInfo.reading)
        }
    }

    @Test(timeout = 5000)
    fun repeatedRefreshDoesNotCancelHeldReadOrStartAnotherCatalog() {
        S3LogRefreshFixture().use { fixture ->
            fixture.warm()
            fixture.source.refresh()
            fixture.drain()
            fixture.reply(fixture.next("catalog"), fixture.catalog())
            val held = fixture.next("read ${fixture.first}")
            assertEquals(4, fixture.commands.size)

            fixture.source.refresh()
            fixture.source.refresh()
            fixture.drain()

            assertFalse("同种刷新应合并，不应取消真实读", held.cancelled)
            assertEquals("重复输入不增发 catalog/read", 4, fixture.commands.size)
            fixture.assertNoQueuedCommand()
            fixture.reply(held, fixture.tail(fixture.first))
            assertEquals(1, fixture.ready().entries.size)
            assertFalse(fixture.ready().loadInfo.reading)
            assertEquals(4, fixture.commands.size)
        }
    }
}

// 供同目录 pending 日志测试复用，不创建新的调度/生命周期状态模型。
internal class S3LogRefreshFixture(private val lateReplies: Boolean = false) : AutoCloseable {
    val first = "a".repeat(64)
    val second = "b".repeat(64)
    val dispatcher = HomeControlDispatcher()
    private val job = SupervisorJob()
    val commands = mutableListOf<String>()
    private val queue = ArrayDeque<S3LogPending>()
    private val allPending = mutableListOf<S3LogPending>()
    val source = AndroidOperationalLogSource(LogCommand { command ->
        commands += command
        val pending = S3LogPending(command)
        queue.addLast(pending)
        allPending += pending
        try {
            if (lateReplies) withContext(NonCancellable) { pending.response.await() }
            else pending.response.await()
        } catch (error: CancellationException) {
            pending.cancelled = true
            throw error
        }
    }, CoroutineScope(job + dispatcher), { 0L })

    fun drain() = dispatcher.runCurrent()
    fun next(suffix: String): S3LogPending = queue.removeFirst().also {
        assertEquals("/data/adb/softvowifi/runtime/control.sh logs $suffix", it.command)
    }
    fun reply(pending: S3LogPending, output: String, exit: Int = 0) {
        assertTrue(pending.response.complete(AndroidSuResult(false, exit, output, false)))
        drain()
    }
    fun ready() = source.state.value as OperationalLogUiState.Ready
    fun assertNoQueuedCommand() = assertTrue("出现了额外命令", queue.isEmpty())
    fun warm() {
        drain()
        reply(next("catalog"), catalog())
        reply(next("read $first"), tail(first))
        assertEquals(1, ready().entries.size)
        assertFalse(ready().loadInfo.reading)
    }
    fun catalog(ids: List<String> = listOf(first, second)) =
        """{"ok":true,"rotatedCount":0,"files":[${ids.joinToString(",") {
            """{"key":"$it","name":"engine.log","suffix":"0001"}"""
        }}]}"""
    fun tail(key: String) =
        """{"ok":true,"key":"$key","truncated":false,"lines":["2026-09-11 12:00:00.000 [INFO] [ims] [0001] safe event"]}"""

    override fun close() {
        source.close()
        // 非合作迟到响应只用于 generation 测试；失败路径同样必须释放它们。
        allPending.forEach {
            it.response.complete(AndroidSuResult(false, 1, """{"ok":false,"code":"CANCELLED","message":"cancelled"}""", false))
        }
        drain()
        assertTrue("源关闭后仍有未结束工作", job.isCompleted)
    }
}

internal class S3LogPending(val command: String) {
    val response = CompletableDeferred<AndroidSuResult>()
    var cancelled = false
}
