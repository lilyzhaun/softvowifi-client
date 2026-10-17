@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// C3：真实HomeScreen消费外部typed状态；held结果只模拟后端边界，不冒充实机配置创建。
class S3HomeProvisionCompletionRenderTest {
    @Test fun acceptedProvisionKeepsItsOwnButtonWidthAndProgressWithoutChangingAnotherUnconfiguredRow() = onMotionTestThread { runTest {
        Fixture(testScheduler).use { f ->
            val original = f.button(0).boundsInRoot
            val other = f.button(1).boundsInRoot
            val initialStatusLabels = f.texts().count { it == "未配置" }
            assertEquals(3, initialStatusLabels, "前提：首页主卡和两条实际SIM行各有原始未配置标记")
            f.tap(0)
            assertEquals(listOf(FIRST), f.requests)
            assertEquals(setOf(FIRST), f.progress.value.pending)
            assertFalse(f.held.getValue(FIRST).isCompleted, "前提：受理后的后端结果仍未完成")
            assertTrue(f.hasText("正在创建"), "C3：首页创建按钮必须消费该目标的真实pending")
            val pending = f.button(0)
            assertEquals(original.width, pending.boundsInRoot.width, .5f)
            assertTrue(pending.config.contains(SemanticsProperties.ProgressBarRangeInfo), "真实目标须有Progress语义")
            assertEquals(other, f.button(1).boundsInRoot)
            assertFalse(f.button(1).config.contains(SemanticsProperties.ProgressBarRangeInfo), "另一row不能误标busy")
            assertFalse(f.button(1).config.contains(SemanticsProperties.Disabled), "另一row保持真实canProvision准入，不伪造全局pending")
            assertEquals(1, f.texts().count { it == "创建配置" })
            f.tap(0)
            assertEquals(listOf(FIRST), f.requests, "在途重复点按不得再次提交")
            assertTrue(f.instances.all { it.presence == SimPresence.ACTIVE_UNCONFIGURED })
            assertEquals(initialStatusLabels, f.texts().count { it == "未配置" }, "pending不能改写主卡或两条实际SIM行的配置状态")
        }
    } }

    @Test fun typedFailureIsPersistentAndRejectsOnlyANewActiveCompletionThenClearsOnARealRetry() = onMotionTestThread { runTest {
        val initial = ConfigurationProvisionState(completions = mapOf(FIRST to ConfigurationProvisionCompletion(7, ConfigurationProvisionOutcome.Failed)))
        Fixture(testScheduler, initial).use { f ->
            assertEquals(0, f.rejects(), "初次进入既有失败不发Reject")
            assertTrue(f.hasText(FAILURE), "固定安全失败说明只来自typed完成，不读取message")
            f.tap(0)
            assertEquals(1, f.requests.size)
            assertFalse(f.hasText(FAILURE), "新请求真正受理/pending后即时清旧错误")
            f.finish(ConfigurationProvisionOutcome.Failed)
            assertEquals(8L, f.progress.value.completions.getValue(FIRST).sequence)
            assertTrue(f.hasText(FAILURE))
            assertEquals(1, f.rejects())
            repeat(3) { f.progress.value = f.progress.value.copy(); f.frames() }
            f.frames(160)
            assertTrue(f.hasText(FAILURE), "失败说明必须持久，不随短提示到期消失")
            assertEquals(1, f.rejects(), "相同sequence重复状态不重发")
            f.tap(0)
            assertEquals(2, f.requests.size)
            assertFalse(f.hasText(FAILURE))
            f.active.value = false; f.frames()
            f.finish(ConfigurationProvisionOutcome.Failed)
            assertEquals(1, f.rejects(), "失活收到新失败只消费，不触感")
            f.active.value = true; f.frames()
            assertEquals(1, f.rejects(), "重入不能重放后台结果")
            f.mounted.value = false; f.frames()
            f.mounted.value = true; f.frames(4)
            assertEquals(1, f.rejects(), "移除后重建仍不能重发既有失败")
            assertTrue(f.hasText(FAILURE))
            f.tap(0)
            assertEquals(3, f.requests.size)
            assertFalse(f.hasText(FAILURE))
            f.finish(ConfigurationProvisionOutcome.Failed)
            assertEquals(2, f.rejects(), "后继真实新受理的可见失败仅再发一次")
            assertTrue(f.instances.all { it.presence == SimPresence.ACTIVE_UNCONFIGURED })
            f.tap(0)
            assertEquals(4, f.requests.size)
            assertFalse(f.hasText(FAILURE))
            f.finish(ConfigurationProvisionOutcome.Unconfirmed)
            assertTrue(f.hasText("创建配置结果未确认，请刷新核对"), "未确认必须明确说明，不能当成已创建或普通失败")
            assertEquals(3, f.rejects())
            repeat(2) { f.progress.value = f.progress.value.copy(); f.frames() }
            assertEquals(3, f.rejects(), "同一未确认结果也不能重复触感")
        }
    } }

    private class Fixture(scheduler: TestCoroutineScheduler, initial: ConfigurationProvisionState = ConfigurationProvisionState()) : AutoCloseable {
        val progress = MutableStateFlow(initial)
        val active = mutableStateOf(true)
        val mounted = mutableStateOf(true)
        val requests = mutableListOf<SimHandle>()
        val held = mutableMapOf<SimHandle, CompletableDeferred<ConfigurationProvisionOutcome>>()
        val instances = listOf(row(FIRST, 0), row(SimHandle("sim-2"), 1))
        private var sequence = initial.completions.values.maxOfOrNull { it.sequence } ?: 0L
        private val haptics = mutableListOf<HapticFeedbackType>()
        private val recorder = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
        }
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val back = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        private val environment = MotionTestEnvironment(scheduler, width = 390, height = 1400, durationScale = 0f) {
            val scope = rememberCoroutineScope() // 外部完成边界不随HomeScreen移除而取消。
            SoftVoWiFiTheme(false) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalHapticFeedback provides recorder,
                    LocalLifecycleOwner provides lifecycle, LocalNavigationEventDispatcherOwner provides back,
                    LocalAppRouteActive provides active.value) {
                    if (mounted.value) HomeScreen(RegistrationUiState.Empty, SimControlUiState.Ready(instances),
                        EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.PASS, emptyList()),
                        onRefresh = { RefreshResult.Completed }, onStartEngine = { error("未配置不得启动") },
                        onStopEngine = { error("创建测试不得停止") }, onProvision = { handle ->
                            requests += handle
                            val result = CompletableDeferred<ConfigurationProvisionOutcome>()
                            held[handle] = result
                            progress.value = progress.value.copy(pending = progress.value.pending + handle)
                            scope.launch {
                                val outcome = result.await()
                                progress.value = progress.value.copy(pending = progress.value.pending - handle,
                                    completions = progress.value.completions + (handle to ConfigurationProvisionCompletion(++sequence, outcome)))
                            }
                        }, onOpenNotice = { error("创建测试不得导航") }, provisioning = progress)
                }
            }
        }
        init { frames(4) }
        fun frames(count: Int = 2) { repeat(count) { environment.advanceFrameBy(16) } }
        private fun nodes(merged: Boolean = false): List<SemanticsNode> = environment.scene.semanticsOwners.flatMap { owner ->
            fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
            tree(if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode)
        }
        fun texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
        fun hasText(value: String) = value in texts()
        fun button(index: Int) = nodes(merged = true).filter { node ->
            node.config.contains(SemanticsActions.OnClick) && node.config.getOrNull(SemanticsProperties.Text)
                ?.any { it.text == "创建配置" || it.text == "正在创建" } == true
        }.sortedBy { it.boundsInRoot.top }.also {
            assertEquals(2, it.size, "前提：两行创建按钮必须保持真实渲染")
            assertTrue(it.all { node -> node.boundsInRoot.height >= 48f && node.boundsInRoot.bottom <= 1400f })
        }[index]
        fun tap(index: Int) {
            val point = button(index).boundsInRoot.center
            environment.press(point); environment.advanceFrameBy(8); environment.release(point); frames()
        }
        fun finish(outcome: ConfigurationProvisionOutcome) { held.getValue(FIRST).complete(outcome); frames() }
        fun rejects() = haptics.count { it == HapticFeedbackType.Reject }
        override fun close() { held.values.forEach { it.cancel() }; environment.close(); back.navigationEventDispatcher.dispose() }
    }
    companion object {
        private val FIRST = SimHandle("sim-1")
        private const val FAILURE = "创建配置失败，请重试"
        private fun row(handle: SimHandle, slot: Int) = SimControlInstance(handle, "SIM ${slot + 1} · 合成线路",
            SimPresence.ACTIVE_UNCONFIGURED, slot, "合成运营商", "001", "01", EngineLifecycle.CONFIG_MISSING,
            canStart = false, canStop = false, canApply = false, canProvision = true)
    }
}
