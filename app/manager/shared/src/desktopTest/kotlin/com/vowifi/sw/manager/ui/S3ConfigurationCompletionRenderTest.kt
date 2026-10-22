package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlin.test.*

// C3：真实控件受理与 typed 完成，不手赋 draft.status 或从 message 推导失败。
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class S3ConfigurationCompletionRenderTest {
    @Test fun synchronousCompletionsRejectOnceButBusyAndReentryCannotInventCompletion() = onMotionTestThread { runTest {
        val instance = ConfigurationInstance(ConfigurationInstanceId("sim-1"), "合成配置", ConfigurationLifecycle.STOPPED,
            ConfigurationTransport.AUTO, "", "", "", "", "epdg.invalid", "ims.invalid", "001", "01")
        val draft = ConfigurationEditorDraft(instance)
        draft.smsc.edit { replace(0, length, "+123") }
        val haptics = mutableListOf<HapticFeedbackType>()
        val recorder = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
        }
        var remount by mutableStateOf(0)
        var outcome: ConfigurationApplyOutcome = ConfigurationApplyOutcome.Failed
        var busy = false
        var calls = 0
        MotionTestEnvironment(testScheduler, width = 440, height = 1600) {
            SoftVoWiFiTheme(false) { CompositionLocalProvider(LocalHapticFeedback provides recorder, LocalDensity provides Density(1f)) {
                Scaffold(Modifier.fillMaxSize()) { key(remount) {
                    ConfigurationSimEditor(ConfigurationEditorModel(instance, ConfigurationUiState.Ready(listOf(instance)), true, null),
                        draft, onSubmit = { _, _, complete -> calls++
                            if (busy) ConfigurationApplyAcceptance.Busy else { complete(outcome); ConfigurationApplyAcceptance.Accepted } })
                } }
            } }
        }.use { environment ->
            fun frames() { repeat(3) { environment.advanceFrameBy(16) } }
            fun save() {
                val point = environment.scene.semanticsOwners.flatMap { owner ->
                    fun tree(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::tree)
                    tree(owner.rootSemanticsNode)
                }.single { it.config.contains(SemanticsActions.OnClick) &&
                    it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "保存配置" } == true }.boundsInRoot.center
                environment.press(point); environment.advanceFrameBy(16); environment.release(point); frames()
            }
            repeat(15) { environment.advanceFrameBy(16) }
            for (terminal in listOf(ConfigurationApplyOutcome.Failed, ConfigurationApplyOutcome.Unconfirmed)) {
                outcome = terminal
                val before = haptics.count { it == HapticFeedbackType.Reject }
                save()
                assertFalse(draft.pending, "同步真实 callback 可能没有可绘制的 Saving 中间帧")
                assertEquals(before + 1, haptics.count { it == HapticFeedbackType.Reject })
                val sequence = draft.completionSequence
                draft.syncFrom(instance); remount++; frames()
                assertEquals(sequence, draft.completionSequence)
                assertEquals(before + 1, haptics.count { it == HapticFeedbackType.Reject })
            }
            val sequence = draft.completionSequence
            val rejects = haptics.count { it == HapticFeedbackType.Reject }
            busy = true; save()
            assertEquals(ConfigurationEditStatus.BusyRejected, draft.status)
            assertEquals(sequence, draft.completionSequence)
            assertEquals(rejects, haptics.count { it == HapticFeedbackType.Reject })
            assertEquals(3, calls); assertTrue(draft.dirty)
        }
    } }

    @Test fun failedSaveRejectsOnceAndBusyKeepsItsActualButtonWidth() = onMotionTestThread { runTest {
        ConfigurationStateMotionRenderTest.Fixture(testScheduler).use { f ->
            f.editSmscDraft("+123"); f.advance(1000)
            val initial = f.nodes(merged = true).single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "保存配置" } == true }.boundsInRoot
            f.tapText("保存配置")
            assertEquals(1, f.submissions)
            assertTrue(f.draft.pending)
            assertTrue(f.haptics.any { it == HapticFeedbackType.Confirm }, "前提：真实保存点击的触感门控已启用")
            val pending = f.nodes(merged = true).single { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }.boundsInRoot
            assertEquals(initial.width, pending.width, .5f, "C3：实际保存/忙碌内容不能改变按钮宽度")
            f.tapPoint(initial.center); f.advance(32)
            assertEquals(1, f.submissions, "held ConfigurationSubmit 在途不能重复受理")
            val rejects = f.haptics.count { it == HapticFeedbackType.Reject }
            requireNotNull(f.completion).invoke(ConfigurationApplyOutcome.Failed)
            f.advance(32)
            assertEquals(ConfigurationEditStatus.Failed, f.draft.status)
            assertTrue(f.draft.dirty); assertEquals("+123", f.draft.smsc.text.toString())
            assertEquals(rejects + 1, f.haptics.count { it == HapticFeedbackType.Reject }, "真实失败终态只发一次 Reject")
            f.advance(1000)
            assertTrue(f.hasTextContaining(configurationEditSummary(f.draft)))
            f.draft.syncFrom(f.instance); f.remount++; f.advance(1000)
            assertEquals(rejects + 1, f.haptics.count { it == HapticFeedbackType.Reject }, "后台同值/重入不能重放旧失败触感")
            assertEquals(1, f.submissions)
        }
    } }

    @Test fun unconfirmedSaveExplanationFadesAndOldResultDoesNotReplayOnReentry() = onMotionTestThread { runTest {
        ConfigurationStateMotionRenderTest.Fixture(testScheduler).use { f ->
            f.editSmscDraft("+123"); f.advance(1000)
            f.tapText("保存配置"); assertTrue(f.draft.pending); assertEquals(1, f.submissions)
            assertTrue(f.haptics.any { it == HapticFeedbackType.Confirm }, "前提：不能把关闭触感偏好当缺 Reject")
            requireNotNull(f.completion).invoke(ConfigurationApplyOutcome.Unconfirmed)
            f.advance(1000)
            assertEquals(ConfigurationEditStatus.Unconfirmed, f.draft.status)
            val probe = f.probe(configurationSaveBarDetail(f.draft, f.instance.lifecycle), firstLineOnly = true)
            assertTrue(f.strength(probe) in .95..1.05, "前提：先校准真实结果说明首行的局部墨迹")
            val unconfirmedText = configurationSaveBarDetail(f.draft, f.instance.lifecycle)
            f.tapText("保存配置"); assertTrue(f.draft.pending); assertEquals(2, f.submissions)
            f.advance(1000)
            // The bar reuses one line for the outcome, so check by semantics: a pixel probe would correlate unrelated glyphs.
            assertFalse(f.hasText(unconfirmedText), "前提：新的真实在途态已替代旧说明，不能误采后台旧错误")
            assertTrue(f.hasText("正在保存配置，请稍候"))
            val rejects = f.haptics.count { it == HapticFeedbackType.Reject }
            requireNotNull(f.completion).invoke(ConfigurationApplyOutcome.Unconfirmed)
            f.advance(1)
            assertEquals(ConfigurationEditStatus.Unconfirmed, f.draft.status)
            f.assertNotClipped(probe)
            assertTrue(f.strength(probe) < .95, "真实 Unconfirmed 说明不能第一帧完整插入")
            f.advance(225)
            assertTrue(f.strength(probe) in .95..1.05)
            assertEquals(rejects + 1, f.haptics.count { it == HapticFeedbackType.Reject })
            f.draft.syncFrom(f.instance); f.remount++; f.advance(1000)
            assertEquals(rejects + 1, f.haptics.count { it == HapticFeedbackType.Reject })
            assertTrue(f.draft.dirty); assertEquals("+123", f.draft.smsc.text.toString())
            assertEquals(2, f.submissions)
        }
    } }

    @Test fun actualSettingsCreationKeepsThePendingActionAndReportsTypedFailureOnce() = onMotionTestThread { runTest {
        val handle = SimHandle("sim-1")
        val progress = MutableStateFlow(ConfigurationProvisionState())
        val held = CompletableDeferred<ConfigurationProvisionOutcome>()
        val haptics = mutableListOf<HapticFeedbackType>()
        val recorder = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
        }
        var calls = 0
        var remount by mutableStateOf(0)
        val sim = SimControlUiState.Ready(listOf(SimControlInstance(handle, "SIM 1 · 合成配置", SimPresence.ACTIVE_UNCONFIGURED,
            0, "合成运营商", "001", "01", EngineLifecycle.CONFIG_MISSING, canStart = false, canStop = false, canApply = false, canProvision = true)))
        MotionTestEnvironment(testScheduler, width = 440, height = 1600) {
            SoftVoWiFiTheme(false) {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalHapticFeedback provides recorder) {
                    val scope = rememberCoroutineScope()
                    Scaffold(Modifier.fillMaxSize()) { key(remount) {
                        SettingsConfigurationScreen(ConfigurationUiState.Empty, sim, SettingsConfigurationActions(
                            onSubmit = { _, _, _ -> error("不应保存配置") },
                            onProvision = { accepted ->
                                calls++
                                assertEquals(handle, accepted)
                                progress.value = progress.value.copy(pending = setOf(accepted))
                                scope.launch { val outcome = held.await(); progress.value = progress.value.copy(pending = emptySet(),
                                    completions = mapOf(accepted to ConfigurationProvisionCompletion(1, outcome))) }
                            }, onRefresh = { RefreshResult.Completed }), provisioning = progress)
                    } }
                }
            }
        }.use { environment ->
            fun frames(count: Int = 1) { repeat(count) { environment.advanceFrameBy(16) } }
            fun nodes(): List<SemanticsNode> = environment.scene.semanticsOwners.flatMap { owner ->
                fun visit(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::visit)
                visit(owner.rootSemanticsNode)
            }
            fun tap(point: Offset) { environment.press(point); frames(); environment.release(point); frames() }
            val allowedLabels = listOf(configurationProvisionLabel(), "正在创建", "正在创建配置")
            fun creationLabels() = allowedLabels.filter { label -> nodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
            } }
            fun diagnostic(stage: String) {
                val current = nodes()
                println("s3-configuration-create stage=$stage clock_ms=${testScheduler.currentTime} " +
                    "labels=${creationLabels()} merged_progress=${current.count { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }} " +
                    "scroll_owners=${current.count { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) && it.config.contains(SemanticsActions.ScrollBy) }} " +
                    "source_pending=${progress.value.pending == setOf(handle)} held_completed=${held.isCompleted}")
            }
            try {
                frames(20)
                val action = nodes().single { it.config.contains(SemanticsActions.OnClick) &&
                    it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == configurationProvisionLabel() } == true }
                val original = action.boundsInRoot
                tap(original.center)
                assertEquals(1, calls); assertEquals(setOf(handle), progress.value.pending)
                assertTrue(haptics.any { it == HapticFeedbackType.Confirm }, "前提：实际创建动作的触感门控已启用")
                assertFalse(held.isCompleted)
                diagnostic("post-release")
                val commitStarted = testScheduler.currentTime
                // Flow delivery can close PTR and move its single body in a following commit.
                // Preserve the first-frame receipt; never wait for the real held write or seconds.
                environment.advanceFrameBy(0)
                repeat(2) {
                    if ("正在创建" !in creationLabels() || nodes().count {
                        it.config.contains(SemanticsProperties.ProgressBarRangeInfo)
                    } != 1) frames()
                }
                diagnostic("bounded-commit")
                assertTrue(testScheduler.currentTime - commitStarted <= 32)
                assertEquals(setOf(handle), progress.value.pending)
                assertFalse(held.isCompleted)
                val pending = nodes().filter { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }
                assertEquals(1, pending.size, "C3：创建按钮须保留为进行中动作，不能隐藏按钮")
                assertEquals(original.width, pending.single().boundsInRoot.width, .5f)
                tap(original.center); assertEquals(1, calls)
                val rejects = haptics.count { it == HapticFeedbackType.Reject }
                held.complete(ConfigurationProvisionOutcome.Failed); frames(20)
                assertTrue(progress.value.pending.isEmpty())
                assertEquals(ConfigurationProvisionOutcome.Failed, progress.value.completions.getValue(handle).outcome)
                assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t ->
                    t.text.contains("创建") && (t.text.contains("失败") || t.text.contains("未完成")) } == true }, "typed Failed 要有持久安全说明")
                assertEquals(rejects + 1, haptics.count { it == HapticFeedbackType.Reject })
                remount++; frames(20)
                assertEquals(rejects + 1, haptics.count { it == HapticFeedbackType.Reject }, "重入不得重放旧创建失败")
                assertEquals(1, calls)
            } finally { held.cancel() }
        }
    } }
}
