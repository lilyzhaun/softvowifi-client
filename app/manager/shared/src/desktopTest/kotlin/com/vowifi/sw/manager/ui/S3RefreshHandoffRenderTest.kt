package com.vowifi.sw.manager.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.ExperimentalComposeUiApi
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.ConfigurationProvisionState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.ui.S3HomeRefreshEntryDraftTest.HomeEntryDriver
import com.vowifi.sw.manager.ui.S3HomeRefreshEntryDraftTest.HomeEntryDriver.HandoffFrame
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import top.yukonga.miuix.kmp.basic.RefreshState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual pointer/library/production-helper handoff. No device or screenshot acceptance claim. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class S3RefreshHandoffRenderTest {
    @Test
    fun heldRealHomeReadHasNoTerminalTextOrCollapseAtReleaseAndAcceptedHandoff() = onMotionTestThread { runTest {
        HomeEntryDriver(testScheduler).use { driver ->
            driver.frames(30)
            driver.holdReads = true
            val rest = driver.pullOccupation()
            val owner = driver.listIdentity()
            driver.beginDownwardDrag(180f)
            driver.assertRealDownwardInput()
            assertEquals(1f, driver.pullProgress(), driver.refreshTrace())
            val armed = driver.handoffFrame()
            assertTrue("松开刷新" in armed.texts,
                "文字采样前提：达阈值时真实库文字须有非零可见语义bounds；$armed")
            assertEquals(0, driver.refreshCalls)
            driver.finishDrag()
            val history = mutableListOf<HandoffFrame>()
            sampleOffsets(driver, history) { assertNoTerminal(it) }
            reachAccepted(driver, history) { assertNoTerminal(it) }
            val accepted = sampleOffsets(driver, history) { frame ->
                assertNoTerminal(frame)
                assertTrue(frame.producerActive, "实际callback仍held；$history")
                assertEquals(null, frame.completedAtMillis, "不能用呈现时间结束IO；$history")
                assertEquals(owner, frame.listOwner)
                assertTrue(frame.occupation > rest + 8f, "不能先收掉真实指示再拉开；$history")
            }
            val initialOccupation = accepted.first().occupation
            assertTrue(accepted.all { it.occupation >= initialOccupation - 1f },
                "接受请求后的0/1/16/32ms不能开始完成收缩；$history")
            assertEquals(1, driver.refreshCalls)
            driver.releaseRead(RefreshResult.Completed)
        }
    } }

    @Test
    fun synchronousCompletedHomeReadKeeps400msAndNeverShowsFailure() = onMotionTestThread { runTest {
        HomeEntryDriver(testScheduler).use { driver ->
            driver.frames(30)
            val rest = driver.pullOccupation()
            val owner = driver.listIdentity()
            driver.beginDownwardDrag(180f)
            driver.assertRealDownwardInput()
            assertEquals(1f, driver.pullProgress(), driver.refreshTrace())
            val armed = driver.handoffFrame()
            assertTrue("松开刷新" in armed.texts, "库文字采样前提不成立；$armed")
            driver.finishDrag()
            val history = mutableListOf<HandoffFrame>()
            sampleOffsets(driver, history) { assertNoFalseSuccess(it) }
            reachAccepted(driver, history) { assertNoFalseSuccess(it) }
            assertEquals(RefreshResult.Completed, driver.completedResult)
            val started = requireNotNull(driver.requestStartedAtMillis)
            sampleOffsets(driver, history) { frame ->
                assertNoFalseSuccess(frame)
                assertTrue(frame.occupation > rest + 8f, "同步同值读取也须保留真实指示；$history")
                assertEquals(owner, frame.listOwner)
            }
            while (testScheduler.currentTime < started + 399L) {
                val delta = minOf(16L, started + 399L - testScheduler.currentTime)
                val frame = driver.handoffFrame(delta).also { history += it }
                assertNoFalseSuccess(frame)
                assertTrue(frame.occupation > rest + 8f,
                    "400ms仅展示最短时间，不可被库提前收起；$history")
            }
            repeat(80) { history += driver.handoffFrame(16) }
            history.forEach(::assertNoFalseSuccess)
            assertTrue(history.any { "刷新完成" in it.texts },
                "真实成功收尾须有可采样的库文字，缺文字属于采样前提而非成功证据；$history")
            assertEquals(1, driver.refreshCalls)
            assertTrue(driver.producer?.isCompleted == true)
        }
    } }

    @Test
    fun actualPublicLibraryCannotEnterCompletionWhileItsCallbackIsHeld() = onMotionTestThread { runTest {
        // Public library visual state + real production bridge helpers are interface/desktop
        // signals, not an image of Xiaomi. This is the fallback when Home label bounds clip.
        HomeEntryDriver(testScheduler, handoffProbeOnly = true).use { driver ->
            driver.frames(12)
            driver.holdReads = true
            driver.beginDownwardDrag(180f)
            driver.assertRealDownwardInput()
            assertEquals(1f, driver.pullProgress())
            driver.finishDrag()
            val history = mutableListOf<HandoffFrame>()
            sampleOffsets(driver, history) {}
            reachAccepted(driver, history) { frame ->
                if (frame.producerActive) assertNotCompletedLibrary(frame)
            }
            sampleOffsets(driver, history, ::assertNotCompletedLibrary)
            assertTrue(history.any { "松开刷新" in it.texts || "正在刷新" in it.texts },
                "真实库组件的文字采样必须至少一次成立；$history")
            assertEquals(1, driver.refreshCalls)
            assertEquals(null, driver.readCompletedAtMillis)
            driver.releaseRead(RefreshResult.Completed)
        }
    } }

    @Test
    fun belowThresholdIsNotAFailureAndActualFailedPersistsUntilSuccessfulRetry() = onMotionTestThread { runTest {
        HomeEntryDriver(testScheduler).use { driver ->
            driver.frames(30)
            driver.beginDownwardDrag(36f)
            driver.assertRealDownwardInput()
            assertTrue(driver.pullProgress() in 0f..1f && driver.pullProgress() != 0f && driver.pullProgress() != 1f)
            driver.finishDrag()
            val history = mutableListOf<HandoffFrame>()
            sampleOffsets(driver, history, ::assertNoTerminal)
            driver.until("未达阈值只复位，不能发读取或伪造失败") { driver.pullProgress() == 0f }
            assertEquals(0, driver.refreshCalls)
            assertFalse(driver.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "刷新未完成" } == true })

            driver.holdReads = true
            driver.beginDownwardDrag(180f)
            driver.assertRealDownwardInput()
            driver.finishDrag()
            driver.until("失败用例必须先进入真实held callback") { driver.refreshCalls == 1 }
            assertTrue(driver.producer?.isActive == true)
            driver.releaseRead(RefreshResult.Failed("测试读取失败"))
            driver.until("真实Failed须同位呈现，不得改成成功") {
                driver.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "刷新未完成" } == true }
            }
            driver.frames(100)
            assertEquals(RefreshResult.Failed("测试读取失败"), driver.completedResult)
            assertTrue(driver.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "刷新未完成" } == true })
            assertEquals(1, driver.refreshCalls)
            val retry = driver.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "重试刷新" } == true }
            driver.holdReads = false
            driver.nextResult = RefreshResult.Completed
            val point = retry.boundsInRoot.center
            driver.environment.press(point); driver.frames(2); driver.environment.release(point)
            driver.until("后继真实Completed须恢复，不保留旧失败") { driver.refreshCalls == 2 && driver.completedResult == RefreshResult.Completed }
            driver.frames(100)
            assertFalse(driver.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "刷新未完成" } == true })
            assertEquals(2, driver.refreshCalls)
            assertEquals(0, driver.otherActions)
        }
    } }

    @Test
    fun acceptedCompletedTailRetainsItsOwnerWhenProvisioningClosesAdmission() = onMotionTestThread { runTest {
        HomeEntryDriver(testScheduler).use { driver ->
            driver.frames(30)
            val rest = driver.pullOccupation()
            val owner = driver.listIdentity()
            driver.beginDownwardDrag(180f)
            driver.assertRealDownwardInput()
            driver.finishDrag()
            val history = mutableListOf<HandoffFrame>()
            reachAccepted(driver, history, ::assertNoFalseSuccess)
            assertEquals(RefreshResult.Completed, driver.completedResult)
            val accepted = driver.handoffFrame().also { history += it }
            val indicatorOwner = driver.pullIndicator().id
            driver.provisioning.value = ConfigurationProvisionState(pending = setOf(SimHandle("test-sim")))
            sampleOffsets(driver, history) { frame ->
                assertNoFalseSuccess(frame)
                assertEquals(owner, frame.listOwner, "准入关闭不重建真实列表；$history")
                assertEquals(indicatorOwner, driver.pullIndicator().id, "已受理尾巴不卸载库指示；$history")
                assertTrue(frame.occupation > rest + 8f && frame.occupation >= accepted.occupation - 1f,
                    "已受理同步完成的400ms尾巴不能被写门禁抢先收起；$history")
            }
            assertEquals(1, driver.refreshCalls)
            assertTrue(driver.refreshButton().config.contains(SemanticsProperties.Disabled))
        }
    } }

    @Test
    fun cancellingOnlyTheWaiterProducesCancelledAndTerminalTextsRemainTyped() = runTest {
        // API-signal guard, not phone imagery or an Android IO substitute.
        val gate = CompletableDeferred<RefreshResult>()
        val producer = backgroundScope.async { gate.await() }
        val controller = ManagerRefreshController(backgroundScope, { true }, { producer.await() })
        assertTrue(controller.request())
        assertTrue(controller.running)
        controller.cancelWaiter()
        runCurrent()
        assertFalse(controller.running)
        assertEquals(RefreshResult.Cancelled, controller.result)
        assertTrue(producer.isActive, "页面等待者取消不得取消源持有producer")
        gate.complete(RefreshResult.Completed)
        assertEquals(RefreshResult.Completed, producer.await())
        assertEquals("", managerRefreshTexts(null).last(), "未开始/未结束不等于失败或成功")
        assertEquals("刷新完成", managerRefreshTexts(RefreshResult.Completed).last())
        assertEquals("刷新未完成", managerRefreshTexts(RefreshResult.Failed("读取失败")).last())
        assertEquals("当前忙碌", managerRefreshTexts(RefreshResult.Busy).last())
        assertEquals("刷新不可用", managerRefreshTexts(RefreshResult.Unsupported).last())
        assertEquals("刷新已取消", managerRefreshTexts(controller.result).last())
        assertNull(managerRefreshFailure(controller.result), "主动离页/被取代的读取不是失败，重入不能留下红色错误卡")
    }

    private fun sampleOffsets(driver: HomeEntryDriver, history: MutableList<HandoffFrame>, check: (HandoffFrame) -> Unit): List<HandoffFrame> {
        // Absolute offsets 0/1/16/32ms, not 0/1/17/49ms.
        return listOf(0L, 1L, 15L, 16L).map { delta ->
            driver.handoffFrame(delta).also { history += it; check(it) }
        }
    }

    private fun reachAccepted(driver: HomeEntryDriver, history: MutableList<HandoffFrame>, check: (HandoffFrame) -> Unit) {
        repeat(1800) {
            if (driver.refreshCalls > 0) return
            driver.handoffFrame(1).also { history += it; check(it) }
        }
        assertTrue(driver.refreshCalls > 0, "实际callback启动前提失败（最多1800ms真实帧）；$history")
    }

    private fun assertNoTerminal(frame: HandoffFrame) {
        assertTrue(frame.texts.intersect(setOf("刷新完成", "刷新未完成")).isEmpty(),
            "真实callback未完成时不能进入成功/失败文字；$frame")
    }

    private fun assertNoFalseSuccess(frame: HandoffFrame) {
        assertFalse("刷新未完成" in frame.texts, "成功读取不得经过虚构失败；$frame")
        if ("刷新完成" in frame.texts) {
            assertEquals(RefreshResult.Completed, frame.completedResult, "成功文字必须来自真实终态；$frame")
            assertTrue(frame.completedAtMillis != null && frame.completedAtMillis <= frame.timeMillis, "$frame")
        }
    }

    private fun assertNotCompletedLibrary(frame: HandoffFrame) {
        assertTrue(frame.producerActive && frame.controllerRunning == true, "held producer/实际controller前提失败；$frame")
        assertEquals(null, frame.completedAtMillis)
        assertTrue(frame.libraryState != RefreshState.RefreshComplete && frame.libraryState != RefreshState.Idle,
            "真实producer未结束，真实公共库状态不能提前进入完成/收起；$frame")
        assertNoTerminal(frame)
    }
}
