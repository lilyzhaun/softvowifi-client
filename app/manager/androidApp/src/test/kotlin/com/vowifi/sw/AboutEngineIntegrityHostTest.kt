@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidEngineIntegrityReader
import com.vowifi.sw.manager.data.AndroidSuCommand
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class AboutEngineIntegrityHostTest {
    @Test fun fixedReadOnlyCommandComparesEveryRunningPidWithoutSendingAnIdentity() = runBlocking {
        val commands = mutableListOf<String>()
        var process: Process? = null
        val reader = AndroidEngineIntegrityReader(AndroidSuCommand(timeoutSeconds = 2, start = { script ->
            commands += script
            // Execute the actual production shell, replacing only hashing: no host /data or /proc file is touched.
            ProcessBuilder("bash", "-c", """
                sha256sum() {
                    case "${'$'}1" in
                        /data/adb/softvowifi/runtime/bin/vowifi-engine-arm64|/proc/101/exe)
                            printf '%s  %s\n' '${"a".repeat(64)}' "${'$'}1";;
                        /proc/202/exe) printf '%s  %s\n' '${"b".repeat(64)}' "${'$'}1";;
                        *) return 1;;
                    esac
                }
                $script
            """.trimIndent()).start().also { process = it }
        }))
        try {
            val result = reader.read(ready(101, 202, null))
            val command = commands.single()
            assertTrue(command.contains("/data/adb/softvowifi/runtime/bin/vowifi-engine-arm64"))
            assertTrue(command.contains("/proc/101/exe"))
            assertTrue(command.contains("/proc/202/exe"))
            assertFalse(command.contains("/proc/303/exe"))
            assertFalse(command.contains("synthetic-owner"))
            for (write in listOf("settings put", "kill ", "stop", "install", "cp ", "config/")) assertFalse(command.contains(write))
            assertEquals("a".repeat(12), result.fileFingerprint)
            assertEquals(listOf(101L, 202L, null), result.running!!.map { it.pid })
            assertEquals(listOf(EngineExecutableMatch.SAME, EngineExecutableMatch.DIFFERENT, EngineExecutableMatch.UNREADABLE),
                result.running!!.map { it.match })
            assertFalse(process!!.isAlive)
            assertNoInputWorker()
        } finally { process?.let { it.destroyForcibly(); it.waitFor(1, TimeUnit.SECONDS) } }
    }

    @Test fun anUnknownRosterIsUnreadableNotAnEmptyListAndHasNoProcCommand() = runBlocking {
        Fixture("print('F=${"a".repeat(64)}')").use { f ->
            val result = f.reader.read(EngineControlUiState.Failure("合成名册未知"))
            assertEquals("a".repeat(12), result.fileFingerprint)
            assertNull(result.running)
            assertFalse(f.commands.single().contains("/proc/"))
        }
    }

    @Test fun missingFileMalformedOutputAndRefusalNeverClaimAnExecutableMatches() = runBlocking {
        for (script in listOf("print('F=\\nP0=${"a".repeat(64)}')", "print('F=not-a-sha\\nP0=bad')",
            "print('F=${"a".repeat(64)}\\nF=${"a".repeat(64)}\\nP0=${"a".repeat(64)}')",
            "import sys; sys.exit(13)")) {
            Fixture(script).use { f ->
                val result = f.reader.read(ready(101))
                assertEquals(1, f.commands.size)
                assertNull(result.fileFingerprint)
                assertEquals(listOf(EngineExecutableMatch.UNREADABLE), result.running!!.map { it.match })
                assertFalse(f.process!!.isAlive)
            }
        }
    }

    @Test fun realCommandDeadlineEndsTheReadAndReclaimsItsChild() = runBlocking {
        Fixture("import time; time.sleep(3)", timeout = 1).use { f ->
            val started = System.nanoTime()
            val result = f.reader.read(ready(101))
            assertEquals(1, f.commands.size)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_500)
            assertNull(result.fileFingerprint)
            assertEquals(EngineExecutableMatch.UNREADABLE, result.running!!.single().match)
            assertFalse(f.process!!.isAlive)
            assertNoInputWorker()
        }
    }

    @Test fun pageCancellationInterruptsTheOwnedReadAndCleansTheProcessAndInputWorker() = runBlocking {
        Fixture("import time; time.sleep(3)").use { f ->
            val read = async { f.reader.read(ready(101)) }
            try {
                withTimeout(2_000) { f.started.await() }
                withTimeout(2_000) { read.cancelAndJoin() }
                assertFalse(f.process!!.isAlive)
                assertNoInputWorker()
            } finally { read.cancelAndJoin() }
        }
    }

    private class Fixture(script: String, timeout: Long = 2) : AutoCloseable {
        val commands = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        var process: Process? = null
        val reader = AndroidEngineIntegrityReader(AndroidSuCommand(timeoutSeconds = timeout, maxBytes = 4096, start = { command ->
            commands += command
            ProcessBuilder("python3", "-c", script).start().also { process = it; started.complete(Unit) }
        }))
        override fun close() { process?.let { it.destroyForcibly(); it.waitFor(1, TimeUnit.SECONDS) } }
    }

    private fun ready(vararg pids: Long?) = EngineControlUiState.Ready(pids.mapIndexed { index, pid ->
        EngineInstanceState("synthetic-owner-$index", EngineLifecycle.RUNNING, pid)
    } + EngineInstanceState("synthetic-stopped", EngineLifecycle.STOPPED, 303))

    private fun assertNoInputWorker() = assertTrue(Thread.getAllStackTraces().keys.none {
        it.name == "vowifi-su-input" && it.isAlive })
}
