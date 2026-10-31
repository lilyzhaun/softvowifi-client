@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidOperationalLogSource
import com.vowifi.sw.manager.data.CancelLogShellRun
import com.vowifi.sw.manager.model.OperationalLogUiState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class RealLogShellRunTest {
    private val command = "/data/adb/softvowifi/runtime/control.sh logs catalog"

    @Test fun cancellationWithGrandchildHoldingStdoutIsBounded() {
        val host = HostLogProcesses()
        host.bounded {
            runBlocking {
                val pending = async(Dispatchers.Default) { CancelLogShellRun({ host.start("sleep 30 & wait") }).run(command) }
                val child = host.captureChild()
                host.awaitWorkerWaiting()
                val started = System.nanoTime()
                pending.cancelAndJoin()
                println("grandchild cancellation ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)} childPid=${child.pid()} childAlive=${child.isAlive}")
                host.assertStopped()
            }
        }
    }

    @Test fun cancellationAtRealProcessFactoryReturnReapsProcess() {
        val host = HostLogProcesses()
        val entered = CountDownLatch(1)
        host.bounded {
            runBlocking {
                val pending = async(Dispatchers.Default) {
                    CancelLogShellRun({
                        val process = host.start("exec sleep 30")
                        entered.countDown()
                        try { CountDownLatch(1).await(2, TimeUnit.SECONDS) }
                        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                        process
                    }).run(command)
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                pending.cancelAndJoin()
                host.assertStopped()
            }
        }
    }

    @Test fun realSleepTimesOutAfterOneSecondAndReapsProcess() {
        val host = HostLogProcesses()
        host.bounded {
            runBlocking {
                val started = System.nanoTime()
                val result = CancelLogShellRun({ host.start("exec sleep 30") }, timeoutSeconds = 1).run(command)
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                println("real timeout ms=$elapsed")
                assertTrue(result.timedOut)
                assertEquals(-1, result.exitCode)
                assertTrue(result.output.isEmpty())
                assertTrue("timeout returned early: $elapsed", elapsed >= 900)
                host.assertStopped()
            }
        }
    }

    @Test fun realOutputOverflowIsRejectedAndRepeatedRunsReleasePipes() {
        val host = HostLogProcesses()
        host.bounded {
            runBlocking {
                val runner = CancelLogShellRun({ host.start("printf '{\"ok\":true}'") })
                assertEquals("{\"ok\":true}", runner.run(command).output)
                host.assertStopped()
                val before = HostLogProcesses.pipeFds()
                repeat(8) { assertEquals("{\"ok\":true}", runner.run(command).output) }
                val result = CancelLogShellRun({ host.start("exec dd if=/dev/zero bs=4096 count=80 2>/dev/null") }).run(command)
                assertFalse(result.timedOut)
                assertTrue(result.overflowed)
                assertEquals("", result.output)
                host.assertStopped()
                val leaked = HostLogProcesses.pipeFds() - before
                println("new pipe descriptors after repeated runs=$leaked")
                assertTrue("leaked pipe descriptors: $leaked", leaked.isEmpty())
            }
        }
    }

    @Test fun utf8MultiChunkJsonAndExitTailAreReceivedExactly() {
        val host = HostLogProcesses()
        host.bounded {
            runBlocking {
                val expected = """{"ok":true,"lines":["${"public-\u65e5\u5fd7-".repeat(700)}"],"tail":"public-tail"}"""
                val bytes = expected.toByteArray(Charsets.UTF_8)
                val script = bytes.toList().chunked(2047).joinToString("; sleep 0.02; ") { chunk ->
                    "printf '" + chunk.joinToString("") { "\\" + (it.toInt() and 255).toString(8).padStart(3, '0') } + "'"
                }
                repeat(3) {
                    val result = CancelLogShellRun({ host.start(script) }).run(command)
                    assertFalse(result.timedOut)
                    assertFalse(result.overflowed)
                    assertEquals(0, result.exitCode)
                    assertEquals(expected, result.output)
                }
                val exited = host.start("printf '%s' '{\"ok\":true,\"tail\":\"public-exit-tail\"}'")
                assertTrue(exited.waitFor(2, TimeUnit.SECONDS))
                assertEquals("{\"ok\":true,\"tail\":\"public-exit-tail\"}", CancelLogShellRun({ exited }).run(command).output)
                host.assertStopped()
            }
        }
    }

    @Test fun blockedRealReadThenRapidSelectionsPublishOnlyC() {
        val host = HostLogProcesses()
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        val third = "c".repeat(64)
        val reads = CopyOnWriteArrayList<String>()
        val published = CopyOnWriteArrayList<String?>()
        val job = SupervisorJob()
        val runner = CancelLogShellRun({ request ->
            when {
                request.endsWith("catalog") -> {
                    val files = listOf(first, second, third).joinToString(",") {
                        """{"key":"$it","name":"engine.log","suffix":"0001"}"""
                    }
                    host.start("printf '%s' '{\"ok\":true,\"rotatedCount\":0,\"files\":[$files]}'")
                }
                request.endsWith(third) -> {
                    reads.add(third)
                    host.start("printf '%s' '{\"ok\":true,\"key\":\"$third\",\"truncated\":false,\"lines\":[\"2026-09-11 12:00:00.000 [INFO] [ims] [0001] safe C event\"]}'")
                }
                else -> {
                    reads.add(request.substringAfterLast(' '))
                    host.start(if (request.endsWith(first)) "sleep 30 & wait" else "exec sleep 30")
                }
            }
        })
        var source: AndroidOperationalLogSource? = null
        try {
            host.bounded {
                runBlocking {
                    val active = AndroidOperationalLogSource(runner, CoroutineScope(job + Dispatchers.Default))
                    source = active
                    val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                        active.state.collect { state ->
                            if (state is OperationalLogUiState.Ready && state.entries.isNotEmpty()) published.add(state.selectedFileId)
                        }
                    }
                    try {
                        host.captureChild()
                        host.awaitWorkerWaiting()
                        assertEquals(listOf(first), reads.toList())
                        active.selectFile(second)
                        active.selectFile(third)
                        val done = active.state.first {
                            it is OperationalLogUiState.Ready && !it.loadInfo.reading && it.selectedFileId == third
                        } as OperationalLogUiState.Ready
                        assertEquals(1, done.entries.size)
                        yield()
                        active.close()
                        job.join()
                        host.assertStopped()
                        assertTrue("C was not observed", published.isNotEmpty())
                        assertTrue("stale publication: $published", published.all { it == third })
                        assertEquals(third, reads.last())
                    } finally { active.close(); observer.cancelAndJoin() }
                }
            }
        } finally { source?.close(); job.cancel(); host.close() }
    }
}
