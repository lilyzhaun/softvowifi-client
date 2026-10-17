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
            assertTrue(f.hasText("停止引擎"))
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

    private class HomeControlFixture(scheduler: TestCoroutineScheduler, initial: SimControlInstance = stopped()) : AutoCloseable {
        val instance = mutableStateOf(initial)
        val active = mutableStateOf(true)
        var starts = 0
        var stops = 0
        private val haptics = mutableListOf<HapticFeedbackType>()
        private val recorder = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
        }
        val environment = MotionTestEnvironment(scheduler, width = 390, height = 1000, durationScale = 0f) {
            SoftVoWiFiTheme(false) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalHapticFeedback provides recorder,
                    LocalAppRouteActive provides active.value) {
                    val presentation = homeDashboardPresentation(RegistrationUiState.Empty,
                        SimControlUiState.Ready(listOf(instance.value)),
                        EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.PASS, emptyList()), nowMs = 1000)
                    LazyColumn {
                        homeDashboardItems(presentation, { starts++; instance.value = instance.value.copy(operationPending = true) },
                            { stops++; instance.value = instance.value.copy(operationPending = true) },
                            { error("启停测试不得创建配置") }, { error("启停测试不得导航") })
                    }
                }
            }
        }
        init { frames(3) }
        fun frames(count: Int = 1) { repeat(count) { environment.advanceFrameBy(16) } }
        private fun nodes(merged: Boolean = false): List<SemanticsNode> = environment.scene.semanticsOwners.flatMap { owner ->
            fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
            visit(if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode)
        }
        fun hasText(value: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        fun button(value: String) = nodes(merged = true).single {
            it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
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
