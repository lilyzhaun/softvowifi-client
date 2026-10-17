package com.vowifi.sw.manager.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vowifi.sw.manager.data.EnvironmentDiagnosticsSource
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 真 controller + source-owned await 屏障；测试源是受控模型，不冒充 Android IO。 */
@OptIn(ExperimentalCoroutinesApi::class)
class S3ManagerRefreshTimingTest {
    @Test
    fun heldReadIsSingleFlightAndTerminalResultPrecedesThe400msPresentationTail() = onMotionTestThread { runTest {
        RefreshDriver(testScheduler).use { driver ->
            driver.frame()
            assertFalse(driver.indicator.value)
            assertEquals(0f, driver.rotation.value)
            assertTrue(driver.controller.request())
            assertFalse(driver.controller.request())
            driver.frame(32)
            assertEquals(1, driver.source.reads)
            assertTrue(driver.controller.running)
            assertTrue(driver.indicator.value)
            driver.source.release(RefreshResult.Completed)
            testScheduler.runCurrent()
            assertFalse(driver.controller.running, "真实完成不能等400ms展示尾巴")
            assertEquals(RefreshResult.Completed, driver.controller.result)
            assertEquals(driver.source.ready, driver.source.state.value)
            driver.frame(200)
            assertTrue(driver.indicator.value)
            driver.frame(220)
            assertFalse(driver.indicator.value)
            assertEquals(1, driver.source.reads)
        }
    } }

    @Test
    fun synchronousEqualCompletionHasASeparateSequenceAndAVisiblePresentationWindow() = onMotionTestThread { runTest {
        RefreshDriver(testScheduler, synchronous = true).use { driver ->
            driver.frame()
            val before = driver.source.state.value
            assertTrue(driver.controller.request())
            assertFalse(driver.controller.running)
            assertEquals(RefreshResult.Completed, driver.controller.result)
            assertEquals(1L, driver.controller.sequence)
            driver.frame(16)
            assertTrue(driver.indicator.value, "同值/同步完成不能丢掉本次用户展示")
            driver.frame(450)
            assertFalse(driver.indicator.value)
            assertTrue(driver.controller.request())
            assertEquals(2L, driver.controller.sequence)
            driver.frame(16)
            assertTrue(driver.indicator.value)
            assertEquals(before, driver.source.state.value)
            assertEquals(2, driver.source.reads)
        }
    } }

    @Test
    fun rejectedAndFailedResultsNeverBecomeSuccessAndTheFailurePersistsUntilRetry() = onMotionTestThread { runTest {
        RefreshDriver(testScheduler, synchronous = true).use { driver ->
            driver.frame()
            listOf(RefreshResult.Busy, RefreshResult.Unsupported).forEach { result ->
                driver.source.next = result
                driver.controller.request()
                driver.frame(32)
                assertEquals(result, driver.controller.result)
                assertFalse(driver.controller.running)
                assertFalse(driver.indicator.value, "明确未受理不能冒充400ms正在读取")
                assertEquals(0f, driver.rotation.value)
            }
            driver.source.next = RefreshResult.Failed("测试读取失败")
            driver.controller.request()
            driver.frame(800)
            assertEquals(RefreshResult.Failed("测试读取失败"), driver.controller.result)
            driver.source.next = RefreshResult.Completed
            driver.controller.request()
            assertEquals(RefreshResult.Completed, driver.controller.result)
            driver.allowed = false
            driver.frame()
            val calls = driver.source.reads
            assertFalse(driver.controller.request())
            assertEquals(calls, driver.source.reads)
        }
    } }

    @Test
    fun leavingThePageCancelsOnlyTheWaiterNotTheProducer() = onMotionTestThread { runTest {
        RefreshDriver(testScheduler).use { driver ->
            driver.frame()
            driver.controller.request()
            driver.frame(32)
            val controller = driver.controller
            driver.active = false
            driver.frame()
            assertFalse(controller.running)
            assertTrue(driver.source.producer?.isActive == true)
            driver.source.release(RefreshResult.Completed)
            testScheduler.runCurrent()
            assertEquals(driver.source.ready, driver.source.state.value)
            assertTrue(driver.source.producer?.isCompleted == true)
            assertEquals(1, driver.source.reads)
        }
    } }

    @Test
    fun rotationFinishesIts600msCircleAndNewRequestContinuesFromCurrentAngle() = onMotionTestThread { runTest {
        RefreshDriver(testScheduler).use { driver ->
            driver.frame()
            driver.controller.request()
            repeat(10) { driver.frame() }
            val started = driver.rotation.value
            assertTrue(started in 20f..150f, "600ms一圈应有真实中间角度，实际$started")
            driver.source.release(RefreshResult.Completed)
            driver.frame(16)
            val before = driver.rotation.value
            driver.source.prepareHeldRead()
            driver.controller.request()
            driver.frame(16)
            assertTrue(driver.rotation.value >= before, "新请求承接收尾角度，不归零重转")
            repeat(40) { driver.frame() }
            assertTrue(driver.rotation.value >= 360f)
            driver.source.release(RefreshResult.Completed)
            repeat(45) { driver.frame() }
            assertEquals(0f, driver.rotation.value)
            assertEquals(2, driver.source.reads)
        }
    } }

    @Test
    fun rejectionDuringThePreviousCircleTailDoesNotFreezeTheIconOffAxis() = onMotionTestThread { runTest {
        RefreshDriver(testScheduler, synchronous = true).use { driver ->
            driver.frame()
            driver.controller.request()
            repeat(10) { driver.frame() }
            assertTrue(driver.rotation.value > 0f)
            val before = driver.rotation.value
            driver.source.next = RefreshResult.Busy
            driver.controller.request()
            driver.frame()
            assertFalse(driver.controller.running)
            assertFalse(driver.indicator.value)
            assertTrue(driver.rotation.value >= before, "拒绝新请求不应冻结/倒转上一圈")
            repeat(40) { driver.frame() }
            assertEquals(0f, driver.rotation.value)
            assertEquals(RefreshResult.Busy, driver.controller.result)
        }
    } }

    @Test
    fun zeroMotionHasNoInfiniteSynchronousLoopAndStillTracksActualWork() = onMotionTestThread { runTest {
        RefreshDriver(testScheduler, durationScale = 0f).use { driver ->
            driver.frame()
            driver.controller.request()
            repeat(8) { driver.frame() }
            assertEquals(0f, driver.rotation.value)
            assertTrue(driver.controller.running)
            assertTrue(driver.indicator.value)
            driver.source.release(RefreshResult.Completed)
            driver.frame(500)
            assertFalse(driver.controller.running)
            assertFalse(driver.indicator.value)
            assertEquals(0f, driver.rotation.value)
        }
    } }

    private class RefreshDriver(
        scheduler: TestCoroutineScheduler,
        synchronous: Boolean = false,
        durationScale: Float = 1f,
    ) : AutoCloseable {
        val source = HeldDiagnostics(scheduler, synchronous)
        var active by mutableStateOf(true)
        var allowed by mutableStateOf(true)
        lateinit var controller: ManagerRefreshController
        lateinit var indicator: State<Boolean>
        lateinit var rotation: State<Float>
        val environment = MotionTestEnvironment(scheduler, durationScale = durationScale) {
            if (active) {
                val current = rememberManagerRefreshController(source::refreshAndAwait, enabled = allowed)
                val shown = rememberManagerRefreshIndicator(current)
                val angle = rememberManagerRefreshRotation(current)
                SideEffect { controller = current; indicator = shown; rotation = angle }
            }
        }
        fun frame(ms: Long = 16) = environment.advanceFrameBy(ms)
        override fun close() { environment.close(); source.close() }
    }

    private class HeldDiagnostics(scheduler: TestCoroutineScheduler, private val synchronous: Boolean) : EnvironmentDiagnosticsSource {
        private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
        val ready = EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.PASS, emptyList())
        override val state = kotlinx.coroutines.flow.MutableStateFlow<EnvironmentDiagnosticsUiState>(ready)
        private var gate = CompletableDeferred<RefreshResult>()
        var producer: kotlinx.coroutines.Deferred<RefreshResult>? = null
        var reads = 0
        var next: RefreshResult = RefreshResult.Completed
        override fun refresh() { read() }
        override suspend fun refreshAndAwait(): RefreshResult = read().await()
        private fun read(): kotlinx.coroutines.Deferred<RefreshResult> {
            producer?.takeIf { it.isActive }?.let { return it }
            return scope.async {
                reads++
                val result = if (synchronous) next else gate.await()
                if (result == RefreshResult.Completed) state.value = ready
                result
            }.also { producer = it }
        }
        fun release(result: RefreshResult) { gate.complete(result) }
        fun prepareHeldRead() { gate = CompletableDeferred() }
        override fun close() { scope.cancel() }
    }
}
