@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidOperationalLogSource
import com.vowifi.sw.manager.data.AndroidSuResult
import com.vowifi.sw.manager.data.LogCommand
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogLoadInfo
import com.vowifi.sw.manager.model.OperationalLogOrigin
import com.vowifi.sw.manager.model.OperationalLogUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

// U4：真实source与合法结构化合成行；所有命令立即答复，不等待尚未实现的第二次read。
class UnifiedOperationalLogSourceHostTest {
    @Test(timeout = 5000)
    fun groupedMembersMergeByTimeAndOriginWithoutJoiningAnotherMatchingSuffix() = runBlocking {
        Fixture().use { f ->
            val waiting = async(f.dispatcher, start = CoroutineStart.UNDISPATCHED) { f.source.refreshAndAwait() }
            f.drain()
            assertTrue("前提：旧API正常完成真实目录与现有read，不以缺少第二请求当超时", waiting.isCompleted)
            assertEquals(RefreshResult.Completed, withTimeout(3000) { waiting.await() })
            val (entries, info) = f.snapshot()
            assertFalse(info.reading); assertNull(info.issue)
            assertTrue("前提：实际读出首源合法结构化行", entries.any { it.safeSummary == "alpha-engine-first" })
            assertEquals(listOf("alpha-engine-first", "alpha-startup-middle", "alpha-engine-tied", "alpha-startup-tied"),
                entries.map { it.safeSummary })
            assertEquals(listOf(OperationalLogOrigin.ENGINE, OperationalLogOrigin.STARTUP,
                OperationalLogOrigin.ENGINE, OperationalLogOrigin.STARTUP), entries.map { it.origin })
            assertEquals(listOf(f.engineA, f.startupA).toSet(), f.readKeys().toSet())
            assertEquals(2, f.readKeys().size)
            assertFalse("groupKey不是成员read key，禁止发错root请求", f.groupA in f.readKeys())
            f.source.selectFile(f.engineB); f.drain()
            val second = f.snapshot()
            assertEquals(listOf("beta-engine", "beta-startup"), second.first.map { it.safeSummary })
            assertNull(second.second.issue)
            assertEquals(listOf(f.engineB, f.startupB).toSet(), f.readKeys().takeLast(2).toSet())
            assertFalse("相同末四位不构成同SIM", second.first.any { it.safeSummary.startsWith("alpha-") })
        }
    }

    @Test(timeout = 5000)
    fun missingOrEmptyStartupNeverErasesEngineRowsOrClaimsPartialReadCompleted() = runBlocking {
        for (failed in listOf(true, false)) Fixture(startupFails = failed, startupEmpty = !failed).use { f ->
            val waiting = async(f.dispatcher, start = CoroutineStart.UNDISPATCHED) { f.source.refreshAndAwait() }
            f.drain()
            assertTrue("前提：即时命令已排空，不等不存在的第二read", waiting.isCompleted)
            val outcome = withTimeout(3000) { waiting.await() }
            val (entries, info) = f.snapshot()
            assertFalse(info.reading)
            assertEquals(listOf("alpha-engine-first", "alpha-engine-tied"), entries.map { it.safeSummary })
            if (failed) {
                assertTrue("一源失败必须是真实Failed而非合流成功", outcome is RefreshResult.Failed)
                assertNotNull("保另一源条目时必须明确部分失败", info.issue)
            } else {
                assertEquals(RefreshResult.Completed, outcome); assertNull(info.issue)
            }
            assertEquals(listOf(f.engineA, f.startupA).toSet(), f.readKeys().toSet())
            assertEquals(2, f.readKeys().size)
        }
    }

    @Test(timeout = 5000)
    fun unknownTimesAndDuplicateBodiesSurviveAndMissingRepresentativeKeepsTheGroup() = runBlocking {
        Fixture(startupUnknown = true).use { f ->
            val first = async(f.dispatcher, start = CoroutineStart.UNDISPATCHED) { f.source.refreshAndAwait() }
            f.drain(); assertEquals(RefreshResult.Completed, withTimeout(3000) { first.await() })
            val entries = f.snapshot().first
            assertEquals("时间未知", entries.first().timestamp)
            assertEquals(OperationalLogOrigin.STARTUP, entries.first().origin)
            assertEquals(2, entries.count { it.safeSummary == "alpha-engine-first" })
            assertEquals(listOf(OperationalLogOrigin.ENGINE, OperationalLogOrigin.STARTUP),
                entries.filter { it.safeSummary == "alpha-engine-first" }.map { it.origin })
            f.catalogRows = listOf(Triple(f.engineB, "engine.log", "f".repeat(64)),
                Triple(f.startupB, "launcher.log", "f".repeat(64)), Triple(f.startupA, "launcher.log", f.groupA))
            val next = async(f.dispatcher, start = CoroutineStart.UNDISPATCHED) { f.source.refreshAndAwait() }
            f.drain(); assertEquals(RefreshResult.Completed, withTimeout(3000) { next.await() })
            assertEquals("目录排序改变且代表成员丢失后仍须选原group", f.startupA, f.selectedId())
            assertEquals(f.startupA, f.readKeys().last())
            assertTrue(f.snapshot().first.all { it.origin == OperationalLogOrigin.STARTUP })
            assertFalse(f.snapshot().first.any { it.safeSummary.startsWith("beta-") })
        }
    }

    @Test(timeout = 5000)
    fun changingGroupCancelsHeldMemberAndLateOldReplyCannotOverwriteTheNewGroup() = runBlocking {
        val held = CompletableDeferred<AndroidSuResult>()
        var block = false
        var entered = false
        Fixture(intercept = { command ->
            if (block && command.endsWith("read ${"b".repeat(64)}")) {
                entered = true; withContext(NonCancellable) { held.await() }
            } else null
        }).use { f ->
            try {
            f.drain(); assertEquals(4, f.snapshot().first.size)
            block = true
            val old = async(f.dispatcher, start = CoroutineStart.UNDISPATCHED) { f.source.refreshAndAwait() }
            f.drain(); assertTrue("前提：真实旧group的第二成员read已在途", entered)
            f.source.selectFile(f.engineB); f.drain()
            assertEquals(f.engineB, f.selectedId()); assertTrue(f.snapshot().first.isEmpty())
            assertNull(f.snapshot().second.issue)
            held.complete(AndroidSuResult(false, 1, "invalid late synthetic response", false)); f.drain()
            assertEquals(RefreshResult.Cancelled, withTimeout(3000) { old.await() })
            assertEquals(f.engineB, f.selectedId())
            assertEquals(listOf("beta-engine", "beta-startup"), f.snapshot().first.map { it.safeSummary })
            assertNull(f.snapshot().second.issue); assertFalse(f.snapshot().second.reading)
            } finally {
                held.complete(AndroidSuResult(false, 1, "synthetic cleanup", false)); f.drain()
            }
        }
    }

    private class Fixture(private val startupFails: Boolean = false, private val startupEmpty: Boolean = false,
        private val startupUnknown: Boolean = false,
        private val intercept: (suspend (String) -> AndroidSuResult?)? = null) : AutoCloseable {
        val engineA = "a".repeat(64); val startupA = "b".repeat(64)
        val engineB = "c".repeat(64); val startupB = "d".repeat(64)
        val groupA = "e".repeat(64); private val groupB = "f".repeat(64)
        var catalogRows = listOf(Triple(engineA, "engine.log", groupA), Triple(startupA, "launcher.log", groupA),
            Triple(engineB, "engine.log", groupB), Triple(startupB, "launcher.log", groupB))
        val dispatcher = HomeControlDispatcher()
        private val job = SupervisorJob()
        private val commands = mutableListOf<String>()
        val source = AndroidOperationalLogSource(LogCommand { command ->
            commands += command
            val operation = command.removePrefix("/data/adb/softvowifi/runtime/control.sh logs ")
            intercept?.invoke(command) ?: when (operation) {
                "catalog" -> result(catalog())
                "read $engineA" -> result(tail(engineA, listOf(line("00", "alpha-engine-first"), line("02", "alpha-engine-tied"))))
                "read $startupA" -> if (startupFails) result("""{"ok":false,"code":"IO_FAILED","message":"synthetic read failure"}""", 1)
                    else result(tail(startupA, when {
                        startupEmpty -> emptyList()
                        startupUnknown -> listOf("错误: --iccid 必填", line("00", "alpha-engine-first"))
                        else -> listOf(line("01", "alpha-startup-middle"), line("02", "alpha-startup-tied"))
                    }))
                "read $engineB" -> result(tail(engineB, listOf(line("00", "beta-engine"))))
                "read $startupB" -> result(tail(startupB, listOf(line("01", "beta-startup"))))
                else -> error("未约定的合成日志命令")
            }
        }, CoroutineScope(job + dispatcher), { 0L })

        fun drain() = dispatcher.runCurrent()
        fun readKeys() = commands.filter { it.contains(" logs read ") }.map { it.substringAfterLast(' ') }
        fun selectedId() = when (val state = source.state.value) {
            is OperationalLogUiState.Ready -> state.selectedFileId
            is OperationalLogUiState.Warning -> state.selectedFileId
            else -> error("前提：应保真实目录数据态")
        }
        fun snapshot(): Pair<List<OperationalLogEntry>, OperationalLogLoadInfo> = when (val state = source.state.value) {
            is OperationalLogUiState.Ready -> state.entries to state.loadInfo
            is OperationalLogUiState.Warning -> state.entries to state.loadInfo
            else -> error("前提：目录与合法首源必须完成为实际数据态，实际=${state::class.simpleName}")
        }
        private fun catalog(): String {
            return """{"ok":true,"rotatedCount":0,"files":[${catalogRows.joinToString(",") { (key, name, group) ->
                """{"key":"$key","name":"$name","suffix":"0001","groupKey":"$group"}"""
            }}]}"""
        }
        private fun line(second: String, message: String) = "2026-09-11 12:00:$second.000 [INFO] [engine] [0001] $message"
        private fun tail(key: String, lines: List<String>) =
            """{"ok":true,"key":"$key","truncated":false,"lines":[${lines.joinToString(",") { "\"$it\"" }}]}"""
        private fun result(output: String, exit: Int = 0) = AndroidSuResult(false, exit, output, false)
        override fun close() { source.close(); drain(); assertTrue("源关闭后不得遗留producer", job.isCompleted) }
    }
}
