@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.EngineControlAction
import com.vowifi.sw.manager.model.EngineControlCompletion
import com.vowifi.sw.manager.model.EngineControlOutcome
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 先跑旧生产homeDashboardItems/行接线；受控后端快照不冒充引擎业务或新分支RED。
class S3HomeEngineCompletionRenderTest {
    @Test fun acceptedStartKeepsOneWidthAndGenericPendingTextUntilTheTypedCompletion() = onMotionTestThread { runTest {
        HomeControlFixture(testScheduler).use { f ->
            val initial = f.button("启动引擎").boundsInRoot
            f.tap("启动引擎")
            assertEquals(1, f.starts)
            assertTrue(f.instance.value.operationPending)
            assertTrue(f.hasText("正在处理"), "受理在途只显示通用处理中，不由最终lifecycle猜相反动作")
            assertEquals(initial.width, f.button("正在处理").boundsInRoot.width, .5f)
            f.instance.value = f.instance.value.copy(lifecycle = EngineLifecycle.RUNNING, canStart = false, canStop = true)
            f.frames(2)
            assertTrue(f.hasText("正在处理"))
            assertFalse(f.hasText("正在停止"))
            f.tap("正在处理")
            assertEquals(1, f.starts)
            assertEquals(0, f.stops)
            f.complete(1, EngineControlAction.Start, EngineControlOutcome.Completed, EngineLifecycle.RUNNING)
            f.frames(16)
            assertFalse(f.button("停止引擎").config.contains(SemanticsProperties.Disabled), "保原语义及可操作开关，不再要求重复可见文字")
            assertEquals(initial.width, f.button("停止引擎").boundsInRoot.width, .5f)
            assertEquals(0, f.rejects())
        }
    } }

    @Test fun aNewTypedStartFailureRejectsOnceAndStaysWithoutAStringMessage() = onMotionTestThread { runTest {
        HomeControlFixture(testScheduler).use { f ->
            f.tap("启动引擎")
            f.complete(1, EngineControlAction.Start, EngineControlOutcome.Failed, EngineLifecycle.STOPPED)
            f.frames(2)
            assertTrue(f.hasText(START_FAILURE), "失败只认typed结果；message=null也必须显示固定安全说明")
            assertEquals(1, f.rejects())
            repeat(3) {
                f.instance.value = f.instance.value.copy()
                f.frames(2)
                assertTrue(f.hasText(START_FAILURE))
                assertEquals(1, f.rejects(), "相同sequence轮询不得重复Reject")
            }
            f.frames(160)
            assertTrue(f.hasText(START_FAILURE), "失败说明不是短暂toast或动画尾巴")
            assertEquals(1, f.starts)
        }
    } }

    @Test fun initialFailureReentryAndInactiveCompletionNeverReplayReject() = onMotionTestThread { runTest {
        val initial = stopped().copy(controlCompletion = EngineControlCompletion(5, EngineControlAction.Start, EngineControlOutcome.Failed))
        HomeControlFixture(testScheduler, initial).use { f ->
            assertEquals(0, f.rejects(), "首帧既有失败不能重发触感")
            f.active.value = false
            f.frames(2)
            f.complete(6, EngineControlAction.Start, EngineControlOutcome.Failed, EngineLifecycle.STOPPED)
            f.frames(2)
            assertEquals(0, f.rejects(), "后台/失活收到新结果不触感")
            f.active.value = true
            f.frames(2)
            assertEquals(0, f.rejects(), "重入不能复活后台已见结果")
            assertTrue(f.hasText(START_FAILURE))
        }
    } }

    @Test fun aMessageOnlyPollDoesNotInventAnOperationFailureOrReject() = onMotionTestThread { runTest {
        HomeControlFixture(testScheduler).use { f ->
            f.instance.value = f.instance.value.copy(message = "合成状态读取提示")
            f.frames(2)
            assertFalse(f.hasText(START_FAILURE))
            assertFalse(f.hasText(STOP_FAILURE))
            assertEquals(0, f.rejects())
            assertEquals(0, f.starts)
        }
    } }

    @Test fun aNewAttemptClearsTheOldFailureAndSuccessfulCompletionDoesNotReject() = onMotionTestThread { runTest {
        HomeControlFixture(testScheduler).use { f ->
            f.tap("启动引擎")
            f.complete(1, EngineControlAction.Start, EngineControlOutcome.Failed, EngineLifecycle.STOPPED)
            f.frames(2)
            assertTrue(f.hasText(START_FAILURE))
            f.tap("启动引擎")
            assertEquals(2, f.starts)
            assertFalse(f.hasText(START_FAILURE), "新的真实pending尝试可清旧错误，不等待动画完成")
            f.complete(2, EngineControlAction.Start, EngineControlOutcome.Completed, EngineLifecycle.RUNNING)
            f.frames(16)
            assertFalse(f.hasText(START_FAILURE))
            assertEquals(1, f.rejects())
        }
    } }

    @Test fun stopFailureUsesItsTypedActionAndClearsOnlyWhenTheTargetIsReallyStopped() = onMotionTestThread { runTest {
        HomeControlFixture(testScheduler, stopped().copy(lifecycle = EngineLifecycle.RUNNING, canStart = false, canStop = true)).use { f ->
            f.tap("停止引擎")
            assertEquals(1, f.stops)
            f.complete(1, EngineControlAction.Stop, EngineControlOutcome.Failed, EngineLifecycle.RUNNING)
            f.frames(2)
            assertTrue(f.hasText(STOP_FAILURE))
            assertFalse(f.hasText(START_FAILURE))
            assertEquals(1, f.rejects())
            f.instance.value = f.instance.value.copy(lifecycle = EngineLifecycle.STOPPED, canStart = true, canStop = false)
            f.frames(16)
            assertFalse(f.hasText(STOP_FAILURE), "真实停止已解决该失败，不能永久残留旧错误")
            assertEquals(1, f.rejects())
        }
    } }

    @Test fun failureHeightAndInkReverseFromTheirCurrentFrameAndExitWithoutAnEmptyJump() = onMotionTestThread { runTest {
        HomeControlFixture(testScheduler, durationScale = 1f).use { f ->
            val baseline = f.factsTop()
            assertEquals(0L, f.errorInk())
            f.complete(1, EngineControlAction.Start, EngineControlOutcome.Failed, EngineLifecycle.STOPPED)
            f.flush() // 同刻提交真实状态并建立动画首帧，再计两帧动画时间；不额外推进时钟。
            f.frames(2)
            val early = f.factsTop()
            f.frames(80)
            val expanded = f.factsTop()
            val fullInk = f.errorInk()
            assertTrue(expanded > baseline + 8f, "真实错误占用高度必须增加")
            assertTrue(early > baseline + .5f && early < expanded - 1f,
                "错误出现必须有高度中帧，不能仅淡入但整行瞬跳：$baseline/$early/$expanded")
            assertTrue(fullInk > 0L, "真实错误字形须已绘制")

            f.instance.value = f.instance.value.copy(operationPending = true)
            f.flush()
            f.frames(2)
            assertFalse(f.hasText(START_FAILURE), "新请求立即撤旧错误语义，不能等退场动画")
            val exiting = f.factsTop()
            val exitingInk = f.errorInk()
            println("HOME_FAILURE_MOTION|baseline=$baseline|early=$early|expanded=$expanded|exiting=$exiting|ink=$exitingInk/$fullInk")
            assertTrue(exiting > baseline + .5f && exiting < expanded,
                "退场必须保留收拢中帧：$baseline/$exiting/$expanded")
            assertTrue(exitingInk > 0L && exitingInk < fullInk, "退场必须保留并淡出原字形，而非立即清空文字")

            f.flush()
            val beforeReverse = f.factsTop()
            f.complete(2, EngineControlAction.Start, EngineControlOutcome.Failed, EngineLifecycle.STOPPED)
            f.flush()
            val afterReverse = f.factsTop()
            println("HOME_FAILURE_REVERSE|before=$beforeReverse|after=$afterReverse")
            assertEquals(beforeReverse, afterReverse, .5f, "同一时刻反向不能重启高度")
            f.frames(80)
            assertEquals(expanded, f.factsTop(), .5f)
            assertTrue(f.hasText(START_FAILURE))
            f.instance.value = f.instance.value.copy(operationPending = true)
            f.frames(80)
            assertEquals(baseline, f.factsTop(), .5f)
            assertEquals(0L, f.errorInk(), "退场完成后不留错误字形或空高度")
        }
    } }

    @Test fun zeroMotionFailureAppearsAndClearsWithNoRetainedHeightOrInk() = onMotionTestThread { runTest {
        HomeControlFixture(testScheduler).use { f ->
            val baseline = f.factsTop()
            f.complete(1, EngineControlAction.Start, EngineControlOutcome.Failed, EngineLifecycle.STOPPED)
            f.frames(2)
            assertTrue(f.factsTop() > baseline + 8f)
            assertTrue(f.errorInk() > 0L)
            f.instance.value = f.instance.value.copy(operationPending = true)
            f.frames(2)
            assertFalse(f.hasText(START_FAILURE))
            assertEquals(baseline, f.factsTop(), .5f)
            assertEquals(0L, f.errorInk())
        }
    } }

    private class HomeControlFixture(private val scheduler: TestCoroutineScheduler,
        initial: SimControlInstance = stopped(), durationScale: Float = 0f) : AutoCloseable {
        val instance = mutableStateOf(initial)
        val active = mutableStateOf(true)
        var starts = 0
        var stops = 0
        private val haptics = mutableListOf<HapticFeedbackType>()
        private val recorder = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
        }
        val environment = MotionTestEnvironment(scheduler, width = 390, height = 1000, durationScale = durationScale) {
            SoftVoWiFiTheme(false) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalHapticFeedback provides recorder,
                    LocalAppRouteActive provides active.value) {
                    val presentation = homeDashboardPresentation(RegistrationUiState.Empty,
                        SimControlUiState.Ready(listOf(instance.value)),
                        EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.PASS, emptyList()), nowMs = 1000)
                    LazyColumn {
                        homeDashboardItems(presentation, { starts++; instance.value = instance.value.copy(operationPending = true) },
                            { stops++; instance.value = instance.value.copy(operationPending = true) },
                            { error("启停测试不得创建配置") }, { error("启停测试不得导航") },
                            onRefresh = { error("启停测试不得刷新") })
                    }
                }
            }
        }
        init { frames(3) }
        fun frames(count: Int = 1) { repeat(count) { environment.advanceFrameBy(16) } }
        fun flush() { repeat(2) { environment.advanceFrameBy(0) } }
        private fun nodes(merged: Boolean = false): List<SemanticsNode> = environment.scene.semanticsOwners.flatMap { owner ->
            fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
            visit(if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode)
        }
        fun hasText(value: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        fun factsTop() = nodes().single {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "活动 SIM" } == true
        }.boundsInRoot.top
        fun errorInk(): Long = environment.scene.render(scheduler.currentTime * 1_000_000L).use { image ->
            Bitmap.makeFromImage(image).use { bitmap ->
                var ink = 0L
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getColor(x, y)
                    val red = pixel ushr 16 and 255
                    val green = pixel ushr 8 and 255
                    val blue = pixel and 255
                    if (red > green + 30 && red > blue + 20) ink += red - maxOf(green, blue)
                }
                ink
            }
        }
        fun button(value: String) = nodes(merged = true).single {
            it.config.contains(SemanticsActions.OnClick) &&
                (it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true ||
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true)
        }
        fun tap(value: String) {
            val point = button(value).boundsInRoot.center
            environment.press(point); environment.advanceFrameBy(8); environment.release(point); frames(2)
        }
        fun complete(sequence: Long, action: EngineControlAction, outcome: EngineControlOutcome, lifecycle: EngineLifecycle) {
            instance.value = instance.value.copy(operationPending = false, lifecycle = lifecycle,
                canStart = lifecycle == EngineLifecycle.STOPPED, canStop = lifecycle == EngineLifecycle.RUNNING,
                controlCompletion = EngineControlCompletion(sequence, action, outcome), message = null)
        }
        fun rejects() = haptics.count { it == HapticFeedbackType.Reject }
        override fun close() = environment.close()
    }
    companion object {
        private const val START_FAILURE = "启动引擎失败，请重试"
        private const val STOP_FAILURE = "停止引擎失败，请重试"
        private fun stopped() = SimControlInstance(SimHandle("sim-1"), "SIM 1 · 合成线路", SimPresence.ACTIVE_MATCHED,
            0, "合成运营商", "001", "01", EngineLifecycle.STOPPED, canStart = true, canStop = false, canApply = true)
    }
}
