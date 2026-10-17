@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidEnvironmentDiagnosticsSource
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

// 真实源完成/取消回归；注入只替换实际进程创建/协程上下文。
// waitFor/stdout/退出码/codec/evaluator 仍由生产源执行。
class EnvironmentDiagnosticsRefreshAwaitHostTest {
    @Test(timeout = 10000)
    fun awaitJoinsHeldRealDiagnoseAndEqualUnhealthySnapshotStillCompletes() {
        val host = HostLogProcesses()
        val initialEntered = CountDownLatch(1)
        val initialRelease = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        host.bounded {
            runBlocking {
                val source = AndroidEnvironmentDiagnosticsSource(start = { command ->
                    assertEquals("/data/adb/modules/vowifi_sw/control.sh diagnose", command)
                    val process = host.start("printf '%s' '$diagnosticsJson'")
                    val call = calls.incrementAndGet()
                    if (call <= 2) {
                        val arrived = if (call == 1) initialEntered else entered
                        val gate = if (call == 1) initialRelease else release
                        arrived.countDown()
                        try {
                            if (!gate.await(3, TimeUnit.SECONDS)) {
                                process.destroyForcibly()
                                throw IOException("诊断测试屏障未释放")
                            }
                        } catch (error: InterruptedException) {
                            process.destroyForcibly()
                            throw error
                        }
                    }
                    process
                }, parentContext = Dispatchers.IO)
                try {
                    // 首次也等待真实结果，不把 Ready 值当 gate 已释放的证明。
                    val initial = async(start = CoroutineStart.UNDISPATCHED) {
                        source.refreshAndAwait() to source.state.value
                    }
                    withContext(Dispatchers.IO) { assertTrue(initialEntered.await(3, TimeUnit.SECONDS)) }
                    assertFalse(initial.isCompleted)
                    initialRelease.countDown()
                    val (initialResult, initialState) = withTimeout(3000) { initial.await() }
                    assertEquals(RefreshResult.Completed, initialResult)
                    val before = initialState as EnvironmentDiagnosticsUiState.Ready
                    assertEquals(DiagnosticCheckStatus.FAIL, before.severity)
                    source.refresh()
                    withContext(Dispatchers.IO) { assertTrue(entered.await(3, TimeUnit.SECONDS)) }
                    val first = async(start = CoroutineStart.UNDISPATCHED) {
                        source.refreshAndAwait() to source.state.value
                    }
                    val second = async(start = CoroutineStart.UNDISPATCHED) { source.refreshAndAwait() }
                    source.refresh()
                    assertFalse(first.isCompleted)
                    assertFalse(second.isCompleted)
                    assertEquals(before, source.state.value)
                    release.countDown()

                    assertEquals(RefreshResult.Completed to before, withTimeout(3000) { first.await() })
                    assertEquals(RefreshResult.Completed, withTimeout(3000) { second.await() })
                    assertEquals(2, calls.get())
                } finally {
                    initialRelease.countDown()
                    release.countDown()
                    source.close()
                }
            }
        }
    }

    @Test(timeout = 10000)
    fun realInvalidOutputIsFailedAndRealUnsupportedExitIsUnsupported() {
        val samples = listOf(
            "printf '%s' 'broken response'" to false,
            "printf '%s' '{\"ok\":false,\"code\":\"USAGE\",\"message\":\"unsupported\"}'; exit 1" to true,
        )
        samples.forEach { (script, unsupported) ->
            val host = HostLogProcesses()
            val started = CountDownLatch(1)
            host.bounded {
                runBlocking {
                    val source = AndroidEnvironmentDiagnosticsSource(start = { command ->
                        assertEquals("/data/adb/modules/vowifi_sw/control.sh diagnose", command)
                        host.start(script).also { started.countDown() }
                    }, parentContext = Dispatchers.IO)
                    try {
                        withContext(Dispatchers.IO) { assertTrue(started.await(3, TimeUnit.SECONDS)) }
                        val result = withTimeout(3000) { source.refreshAndAwait() }
                        if (unsupported) {
                            assertEquals(RefreshResult.Unsupported, result)
                            assertTrue(source.state.value is EnvironmentDiagnosticsUiState.Unavailable)
                        } else {
                            assertTrue(result is RefreshResult.Failed)
                            assertTrue(source.state.value is EnvironmentDiagnosticsUiState.Failure)
                        }
                    } finally { source.close() }
                }
            }
        }
    }

    @Test(timeout = 10000)
    fun cancellingOneWaiterKeepsTheSameHeldDiagnoseForTheOtherWaiter() {
        val host = HostLogProcesses()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        host.bounded {
            runBlocking {
                val source = AndroidEnvironmentDiagnosticsSource(start = { command ->
                    assertEquals("/data/adb/modules/vowifi_sw/control.sh diagnose", command)
                    calls.incrementAndGet()
                    val process = host.start("printf '%s' '$diagnosticsJson'")
                    entered.countDown()
                    try { assertTrue(release.await(3, TimeUnit.SECONDS)) }
                    catch (error: InterruptedException) { process.destroyForcibly(); throw error }
                    process
                }, parentContext = Dispatchers.IO)
                try {
                    withContext(Dispatchers.IO) { assertTrue(entered.await(3, TimeUnit.SECONDS)) }
                    val leaving = async(start = CoroutineStart.UNDISPATCHED) { source.refreshAndAwait() }
                    val remaining = async(start = CoroutineStart.UNDISPATCHED) { source.refreshAndAwait() }
                    assertFalse(leaving.isCompleted)
                    assertFalse(remaining.isCompleted)
                    leaving.cancel()
                    withTimeout(3000) { leaving.join() }
                    assertTrue(leaving.isCancelled)
                    assertFalse(remaining.isCompleted)
                    assertEquals(1, calls.get())
                    release.countDown()
                    assertEquals(RefreshResult.Completed, withTimeout(3000) { remaining.await() })
                    assertEquals(DiagnosticCheckStatus.FAIL,
                        (source.state.value as EnvironmentDiagnosticsUiState.Ready).severity)
                    assertEquals(1, calls.get())
                } finally { release.countDown(); source.close() }
            }
        }
    }

    @Test(timeout = 10000)
    fun closeReleasesHeldWaiterAndCannotPublishALateDiagnoseOrCancelItsParent() {
        val host = HostLogProcesses()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val parent = SupervisorJob()
        host.bounded {
            runBlocking {
                val source = AndroidEnvironmentDiagnosticsSource(start = {
                    val process = host.start("printf '%s' '$diagnosticsJson'")
                    entered.countDown()
                    // 故意让进程工厂忽略第一次取消，验证源不发布迟到的真实读回。
                    try { assertTrue(release.await(3, TimeUnit.SECONDS)) }
                    catch (_: InterruptedException) { assertTrue(release.await(3, TimeUnit.SECONDS)) }
                    process
                }, parentContext = Dispatchers.IO + parent)
                try {
                    withContext(Dispatchers.IO) { assertTrue(entered.await(3, TimeUnit.SECONDS)) }
                    val waiting = async(start = CoroutineStart.UNDISPATCHED) { source.refreshAndAwait() }
                    assertFalse(waiting.isCompleted)
                    val before = source.state.value
                    source.close()
                    withTimeout(3000) { waiting.join() }
                    assertTrue(waiting.isCancelled)
                    assertEquals(RefreshResult.Unsupported, source.refreshAndAwait())
                    assertTrue("源关闭只能取消自身工作", parent.isActive)
                    release.countDown()
                    withTimeout(3000) { parent.children.toList().forEach { it.join() } }
                    assertEquals(before, source.state.value)
                } finally { release.countDown(); source.close(); parent.cancel() }
            }
        }
    }

    private val diagnosticsJson = """{"ok":true,
        "module":{"installed":false,"markers":{"disable":false,"remove":false,"update":false}},
        "root":{"capable":true},"config":{"count":1},
        "observer":{"packagePid":true,"processRecord":true,"foreground":true},
        "engine":{"instanceCount":1,"configMissingCount":0,"stoppedCount":1,"startingCount":0,"runningCount":0,"stoppingCount":0},
        "wfc":{"readable":true,"global":0}}
    """.trimIndent()
}
