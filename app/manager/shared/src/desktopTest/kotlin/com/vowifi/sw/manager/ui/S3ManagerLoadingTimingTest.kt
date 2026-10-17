package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import top.yukonga.miuix.kmp.basic.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class S3ManagerLoadingTimingTest {
    @Test
    fun quickInitialReadNeverDisplaysTheLoadingPanel() = onMotionTestThread { runTest {
        LoadingDriver(testScheduler).use { driver ->
            driver.frame(1)
            driver.frame(148)
            assertTrue(driver.state.hidden)
            assertFalse(driver.state.showLoading)
            driver.loading = false
            // Deliver the result to the actual composition at 149ms. Advancing first to
            // 150ms ran the still-true input's timer before the next frame saw this result.
            driver.frame(0)
            driver.frame(1)
            assertFalse(driver.state.hidden, driver.trace())
            assertFalse(driver.state.showLoading, driver.trace())
            driver.frame(250)
            assertEquals(1f, driver.state.alpha.value, driver.trace())
        }
    } }

    @Test
    fun slowInitialReadWaits150msAndVisibleLoadingHolds300msBeforeItsFadeOut() = onMotionTestThread { runTest {
        LoadingDriver(testScheduler).use { driver ->
            driver.frame(1)
            driver.frame(148)
            assertTrue(driver.state.hidden)
            driver.frame(2)
            assertTrue(driver.state.showLoading)
            driver.loading = false
            driver.frame(0)
            driver.frame(298)
            assertTrue(driver.state.showLoading)
            driver.frame(2)
            assertTrue(driver.state.showLoading, "最短显示后仍有100ms的退出呈现")
            driver.frame(120)
            assertFalse(driver.state.hidden)
            assertFalse(driver.state.showLoading)
            driver.frame(220)
            assertEquals(1f, driver.state.alpha.value, driver.trace())
        }
    } }

    @Test
    fun refreshWithUsableContentDoesNotReplaceThatContentWithLoading() = onMotionTestThread { runTest {
        LoadingDriver(testScheduler, hasContent = true).use { driver ->
            repeat(30) { driver.frame() }
            assertFalse(driver.state.hidden)
            assertFalse(driver.state.showLoading)
            assertEquals(1f, driver.state.alpha.value)
            driver.loading = false
            driver.frame()
            driver.loading = true
            repeat(30) { driver.frame() }
            assertFalse(driver.state.hidden)
            assertFalse(driver.state.showLoading)
            assertEquals(1f, driver.state.alpha.value)
        }
    } }

    @Test
    fun zeroAnimationUsesTheSameDelayAndMinimumButHasNoResidualFade() = onMotionTestThread { runTest {
        LoadingDriver(testScheduler, durationScale = 0f).use { driver ->
            driver.frame(1)
            driver.frame(151)
            assertTrue(driver.state.showLoading)
            driver.loading = false
            driver.frame(301)
            driver.frame(1)
            assertFalse(driver.state.showLoading)
            assertEquals(1f, driver.state.alpha.value)
        }
    } }

    private class LoadingDriver(private val scheduler: TestCoroutineScheduler, hasContent: Boolean = false, durationScale: Float = 1f) : AutoCloseable {
        var loading by mutableStateOf(true)
        lateinit var state: ManagerLoadingState
        val environment = MotionTestEnvironment(scheduler, durationScale = durationScale) {
            val current = rememberManagerLoadingState(loading, hasContent)
            SideEffect { state = current }
            if (!current.hidden) Box(Modifier.fillMaxSize().managerLoadingAlpha(current)) {
                Text(if (current.showLoading) "正在读取测试数据" else "测试结果")
            }
        }
        // Coroutine deadlines and animation frame start are distinct. A single 220ms
        // jump supplied only the tween's first frame, so its real elapsed play time was 0.
        fun frame(ms: Long = 16) {
            repeat((ms / 16).toInt()) { environment.advanceFrameBy(16) }
            val remainder = ms % 16
            if (remainder != 0L || ms == 0L) environment.advanceFrameBy(remainder)
        }
        fun trace() = "t=${scheduler.currentTime}ms inputLoading=$loading phase=${state.phase} alpha=${state.alpha.value}"
        override fun close() = environment.close()
    }
}
