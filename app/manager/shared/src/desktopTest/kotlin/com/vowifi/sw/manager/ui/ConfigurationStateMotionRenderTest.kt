package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 单卡详情页：固定保存栏(C9)、字段对话框校验(C8)、在途冻结(C3)与离开守卫；真实 draft、真实指针、统一帧时钟。 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConfigurationStateMotionRenderTest {
    @Test fun saveBarSlidesInFromBelowRatherThanAppearingInPlace() = onMotionTestThread { runTest {
        Fixture(testScheduler).use { f ->
            assertFalse(f.hasText("保存配置"), "干净草稿不显示保存栏")
            f.editSmscDraft("+123")
            f.advance(1000)
            val probe = f.probe("放弃修改", naturalBounds = true)
            val settled = f.text("保存配置").boundsInRoot
            assertTrue(settled.bottom <= f.viewport.bottom, "前提：保存栏完整位于视口内")
            f.draft.discard()
            f.advance(1000)
            assertFalse(f.draft.dirty)

            f.editSmscDraft("+123")
            f.advance(1)
            assertTrue(f.draft.dirty, "草稿变脏不等待视觉动画")
            assertTrue(f.strength(probe) < .95, "C9：第一帧不能在终点直接出现完整按钮文字；${probe.metrics}")
            f.advance(1000)
            assertEquals(settled, f.text("保存配置").boundsInRoot, "保存栏落位后位置稳定")
            assertTrue(f.strength(probe) in .95..1.05, probe.metrics)
            assertEquals(0, f.submissions, "呈现不得自动提交配置")
        }
    } }

    @Test fun exitingBarCannotSubmitFromItsOldPosition() = onMotionTestThread { runTest {
        Fixture(testScheduler).use { f ->
            f.editSmscDraft("+123")
            f.advance(1000)
            val oldSavePoint = f.text("保存配置").boundsInRoot.center
            f.draft.discard()
            f.advance(20)
            f.tapPoint(oldSavePoint)
            f.advance(1000)
            assertEquals(0, f.submissions, "退场中的保存栏只留绘制，不得由旧位置提交")
            assertFalse(f.hasText("保存配置"))
        }
    } }

    @Test fun lastRowScrollsClearOfThePinnedBar() = onMotionTestThread { runTest {
        Fixture(testScheduler).use { f ->
            f.editSmscDraft("+123")
            f.advance(1000)
            val list = f.nodes().first { it.config.contains(SemanticsActions.ScrollBy) }
            requireNotNull(list.config[SemanticsActions.ScrollBy].action).invoke(0f, 20_000f)
            f.advance(1000)
            val last = f.text("Digest 认证域").boundsInRoot
            val barTop = f.nodes().filter { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "1 项修改未保存" } == true }
                .single().boundsInRoot.top
            // The bar title sits 16dp inside the card; the row must end above the card, not merely above the title.
            assertTrue(last.height > 0f && last.bottom + 16f <= barTop, "最后一行须能滚到保存栏上方，不被遮挡：last=$last barTop=$barTop")
        }
    } }

    @Test fun fieldDialogValidatesBeforeWritingAndLegalInputClearsTheErrorWithoutAnotherConfirm() = onMotionTestThread { runTest {
        Fixture(testScheduler).use { f ->
            f.tapText("短信中心")
            f.advance(1000)
            f.setDialogText("invalid")
            f.tapText("确定")
            f.advance(1000)
            assertEquals("", f.draft.smsc.text.toString(), "非法值不得写入草稿")
            assertFalse(f.draft.dirty)
            assertTrue(f.hasErrorSemantics(), "错误就地显示在对话框字段上")
            assertTrue(f.hasText("SMSC 须以 + 开头，或留空"))
            f.setDialogText("+123")
            assertFalse(f.hasErrorSemantics(), "输入一变为合法，错误在下一帧即消失，不必再次确定")
            f.tapText("确定")
            f.advance(1000)
            assertEquals("+123", f.draft.smsc.text.toString())
            assertFalse(f.nodes().any { it.config.contains(SemanticsActions.SetText) }, "确定后对话框关闭")
            assertTrue(f.hasText("1 项修改未保存"), "写入草稿后保存栏立即说明待保存")
            assertTrue(f.hasTextContaining("+123 · 未保存"), "被修改的行标出未保存")
            assertEquals(0, f.submissions, "对话框只改草稿，不调用后端")
        }
    } }

    @Test fun cancellingTheFieldDialogLeavesTheDraftUntouched() = onMotionTestThread { runTest {
        Fixture(testScheduler).use { f ->
            f.tapText("DNS")
            f.advance(1000)
            f.setDialogText("dns.user.invalid")
            f.tapText("取消")
            f.advance(1000)
            assertEquals("", f.draft.dns.text.toString())
            assertFalse(f.draft.dirty)
            assertFalse(f.hasText("保存配置"))
        }
    } }

    @Test fun pendingSaveFreezesRowsAndDiscardAndFailureKeepsTheDraft() = onMotionTestThread { runTest {
        Fixture(testScheduler).use { f ->
            f.editSmscDraft("+123")
            f.advance(1000)
            f.tapText("保存配置")
            assertEquals(1, f.submissions, "真实单次点按应受理一次保存")
            assertTrue(f.draft.pending)
            f.advance(16)
            assertTrue(f.nodes(merged = true).any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "短信中心" } == true &&
                    node.config.contains(SemanticsProperties.Disabled)
            }, "pending 期间参数行立即禁用")
            f.tapText("放弃修改")
            f.tapText("正在保存")
            f.advance(600)
            assertTrue(f.draft.pending)
            assertEquals("+123", f.draft.smsc.text.toString())
            assertEquals(1, f.submissions, "pending 重复点击不能再提交")
            assertFalse(f.hasText("放弃修改？"), "pending 不能打开放弃确认")
            requireNotNull(f.completion).invoke(ConfigurationApplyOutcome.Failed)
            f.advance(1000)
            assertFalse(f.draft.pending)
            assertTrue(f.draft.dirty)
            assertEquals(ConfigurationEditStatus.Failed, f.draft.status)
            assertEquals("+123", f.draft.smsc.text.toString())
            assertTrue(f.hasTextContaining("保存失败，修改已保留"))
            assertEquals(1, f.submissions, "失败不可由动画或呈现自动重试")
        }
    } }

    @Test fun runningInstanceSaveConfirmsTheRestartFirst() = onMotionTestThread { runTest {
        Fixture(testScheduler, lifecycle = ConfigurationLifecycle.RUNNING).use { f ->
            f.editSmscDraft("+123")
            f.advance(1000)
            f.tapText("保存并重启")
            f.advance(1000)
            assertTrue(f.hasText("保存并重启实例？"))
            assertEquals(0, f.submissions, "确认前不得提交")
            f.tapLast("保存并重启")
            assertEquals(1, f.submissions)
            assertTrue(f.draft.pending)
        }
    } }

    @Test fun toolbarBackWithUnsavedEditsAsksAndDiscardThenLeaves() = onMotionTestThread { runTest {
        Fixture(testScheduler).use { f ->
            f.editSmscDraft("+123")
            f.advance(1000)
            f.backController.request()
            f.advance(1000)
            assertEquals(0, f.leaves, "有未保存修改时不能直接离开")
            assertTrue(f.hasText("保存修改？"))
            assertTrue(f.hasText("1 项修改尚未保存。保存后下次启动生效。"))
            f.tapLast("放弃修改")
            f.advance(1000)
            assertEquals(1, f.leaves)
            assertFalse(f.draft.dirty, "放弃并离开恢复最近读取的配置")
            assertEquals(0, f.submissions)
        }
    } }

    @Test fun systemBackWithUnsavedEditsCanSaveFromTheDialog() = onMotionTestThread { runTest {
        Fixture(testScheduler).use { f ->
            f.editSmscDraft("+123")
            f.advance(1000)
            f.systemBack()
            f.advance(1000)
            assertEquals(0, f.fallbackBacks, "系统返回由页面守卫消费，不落到导航")
            assertTrue(f.hasText("保存修改？"))
            f.tapLast("保存")
            assertEquals(1, f.submissions)
            assertEquals(1, f.leaves, "保存被受理后离开，结果交给总览行")
            assertTrue(f.draft.pending)
        }
    } }

    @Test fun dismissingTheLeaveDialogKeepsEditing() = onMotionTestThread { runTest {
        Fixture(testScheduler).use { f ->
            f.editSmscDraft("+123")
            f.advance(1000)
            f.systemBack()
            f.advance(1000)
            assertTrue(f.hasText("保存修改？"))
            f.systemBack()
            f.advance(1000)
            assertFalse(f.hasText("保存修改？"), "再次返回关闭询问，继续编辑")
            assertEquals(0, f.leaves)
            assertEquals(0, f.fallbackBacks)
            assertTrue(f.draft.dirty)
        }
    } }

    @Test fun cleanDraftBackLeavesWithoutAsking() = onMotionTestThread { runTest {
        Fixture(testScheduler).use { f ->
            f.systemBack()
            f.advance(100)
            assertEquals(1, f.fallbackBacks, "无修改时系统返回照常交给导航")
            f.backController.request()
            assertEquals(1, f.leaves, "无修改时顶栏返回直接离开")
            assertFalse(f.hasText("保存修改？"))
        }
    } }

    @Test fun synchronousFailureFromTheLeaveDialogStaysOnThePage() = onMotionTestThread { runTest {
        Fixture(testScheduler, completeWith = ConfigurationApplyOutcome.Failed).use { f ->
            f.editSmscDraft("+123")
            f.advance(1000)
            f.backController.request()
            f.advance(1000)
            f.tapLast("保存")
            f.advance(1000)
            assertEquals(1, f.submissions)
            assertEquals(0, f.leaves, "同步失败必须留在页面，草稿和说明都在")
            assertEquals(ConfigurationEditStatus.Failed, f.draft.status)
            assertTrue(f.hasTextContaining("保存失败，修改已保留"))
        }
    } }

    internal class Fixture(
        private val scheduler: TestCoroutineScheduler,
        lifecycle: ConfigurationLifecycle = ConfigurationLifecycle.STOPPED,
        durationScale: Float = 1f,
        private val completeWith: ConfigurationApplyOutcome? = null,
        val width: Int = 440,
        val height: Int = 900,
    ) : AutoCloseable {
        val instance = ConfigurationInstance(
            ConfigurationInstanceId("sim-1"), TITLE, lifecycle,
            ConfigurationTransport.AUTO, "", "", "", "", "epdg.invalid", "ims.invalid", "001", "01",
            draftOwnerId = SimOwnerId.fromHmacSha256(ByteArray(32) { 1 }),
        )
        val draft = ConfigurationEditorDraft(instance)
        var submissions = 0
        var leaves = 0
        var fallbackBacks = 0
        var completion: ((ConfigurationApplyOutcome) -> Unit)? = null
        var remount by mutableStateOf(0)
        val haptics = mutableListOf<HapticFeedbackType>()
        val viewport = Rect(0f, 0f, width.toFloat(), height.toFloat())
        val backController = SubpageBackController { leaves++ }
        private val input = object : NavigationEventInput() { fun back() = dispatchOnBackCompleted() }
        private val back = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher(onBackCompletedFallback = { fallbackBacks++ }).apply { addInput(input) }
        }
        private val recorder = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
        }
        private val model = ConfigurationEditorModel(instance, ConfigurationUiState.Ready(listOf(instance)), true, null)
        val environment = MotionTestEnvironment(scheduler, width = width, height = height, durationScale = durationScale) {
            SoftVoWiFiTheme(false) {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalHapticFeedback provides recorder,
                    LocalNavigationEventDispatcherOwner provides back, LocalSubpageBackController provides backController) {
                    Scaffold(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().testTag(SUBJECT)) {
                            val submit: com.vowifi.sw.manager.model.ConfigurationSubmit = { _, _, complete ->
                                submissions++
                                completion = complete
                                completeWith?.let(complete)
                                ConfigurationApplyAcceptance.Accepted
                            }
                            key(remount) { ConfigurationSimEditor(model, draft, submit) }
                        }
                    }
                }
            }
        }
        init { advance(1000) }

        fun advance(milliseconds: Long) {
            repeat((milliseconds / 16).toInt()) { environment.advanceFrameBy(16) }
            val remainder = milliseconds % 16
            if (remainder > 0) environment.advanceFrameBy(remainder)
        }
        fun nodes(merged: Boolean = false): List<SemanticsNode> = environment.scene.semanticsOwners.flatMap { owner ->
            fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
            visit(if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode)
        }
        fun hasText(value: String): Boolean = nodes().any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
        }
        fun hasTextContaining(value: String): Boolean = nodes().any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains(value) } == true
        }
        fun text(value: String): SemanticsNode = nodes().first {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
        }
        fun subjectBounds(): Rect = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == SUBJECT }.boundsInRoot
        fun tapText(value: String) = tapPoint(text(value).boundsInRoot.center)
        fun tapLast(value: String) = tapPoint(nodes().last {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
        }.boundsInRoot.center)
        fun tapPoint(point: Offset) {
            environment.press(point)
            advance(16)
            environment.release(point)
            advance(1)
        }
        fun systemBack() = input.back()
        fun setDialogText(value: String, afterMillis: Long = 16) {
            val field = nodes().single { it.config.contains(SemanticsActions.SetText) }
            assertTrue(requireNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)), "必须命中对话框中的真实字段")
            advance(afterMillis)
        }
        fun editSmscDraft(value: String) { draft.smsc.edit { replace(0, length, value) } }
        fun hasErrorSemantics(): Boolean = nodes().any { it.config.contains(SemanticsProperties.Error) }
        fun assertNotClipped(probe: GlyphProbe) {
            assertTrue(probe.bounds.bottom + 8f < subjectBounds().bottom, "采样前提：短文字须在当前实际容器内，不能把高度裁剪冒充透明度")
        }
        fun probe(value: String, firstLineOnly: Boolean = false, naturalBounds: Boolean = false): GlyphProbe {
            val node = text(value)
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts),
                "采样前提：短文字必须提供真实布局结果")
            val layout = layouts.single()
            if (!firstLineOnly) assertEquals(1, layout.lineCount, "采样前提：只测完整单行短文字，不以整行 fillMaxWidth 区域取样")
            assertFalse(layout.isLineEllipsized(0), "采样前提：首行不得被省略")
            val origin = node.boundsInRoot
            val glyphBounds = Rect(origin.left + layout.getLineLeft(0), origin.top + layout.getLineTop(0),
                origin.left + layout.getLineRight(0), origin.top + layout.getLineBottom(0))
            if (naturalBounds) {
                // Button Text has natural width; its semantics rectangle has real height even when
                // GetTextLayoutResult reports a zero-height line. Summary probes keep their line bounds.
                val metrics = "semantics=$origin layoutSize=${layout.size} lineBounds=$glyphBounds " +
                    "lineTop=${layout.getLineTop(0)} lineBottom=${layout.getLineBottom(0)} " +
                    "lineCount=${layout.lineCount} ellipsized=${layout.isLineEllipsized(0)}"
                assertCompleteNaturalLabel(origin, layout.size, subjectBounds(), layout.isLineEllipsized(0))
                assertTrue(origin.width <= layout.getLineRight(0) - layout.getLineLeft(0) + 2f,
                    "C9采样前提：只能采自然宽度标签小矩形，不采整Row；$metrics")
                return pixels { bitmap -> GlyphProbe.capture(bitmap, origin, metrics) }
            }
            return pixels { bitmap -> GlyphProbe.capture(bitmap, glyphBounds) }
        }
        fun strength(probe: GlyphProbe): Double = pixels(probe::strength)
        internal fun <T> pixels(block: (Bitmap) -> T): T = environment.scene.render(scheduler.currentTime * 1_000_000L).use { image ->
            Bitmap.makeFromImage(image).use(block)
        }
        override fun close() { environment.close(); back.navigationEventDispatcher.dispose() }
        companion object {
            const val TITLE = "SIM 1 · 合成配置"
            private const val SUBJECT = "configuration-motion-subject"
        }
    }

    /** 只采最终文字核心像素；每行减去同局部实时底色，不积分整卡、模糊或阴影。 */
    internal class GlyphProbe private constructor(
        val bounds: Rect,
        private val backgroundX: Int,
        private val samples: List<Sample>,
        val metrics: String,
    ) {
        private data class Sample(val x: Int, val y: Int, val delta: DoubleArray)
        fun strength(bitmap: Bitmap): Double {
            var numerator = 0.0
            var denominator = 0.0
            for (sample in samples) {
                val pixel = bitmap.getColor(sample.x, sample.y)
                val background = bitmap.getColor(backgroundX, sample.y)
                for ((index, shift) in CHANNELS.withIndex()) {
                    val delta = sample.delta[index]
                    numerator += (channel(pixel, shift) - channel(background, shift)) * delta
                    denominator += delta * delta
                }
            }
            return numerator / denominator
        }
        companion object {
            private val CHANNELS = intArrayOf(16, 8, 0)
            private fun channel(argb: Int, shift: Int): Int = argb ushr shift and 255
            fun capture(bitmap: Bitmap, bounds: Rect, metrics: String = ""): GlyphProbe {
                val backgroundX = ceil(bounds.right).toInt() + 12
                assertTrue(bounds.left >= 0f && bounds.top >= 0f && backgroundX + 4 < bitmap.width && bounds.bottom < bitmap.height,
                    "采样前提：完整短文字及相邻局部底色必须在画布内；$metrics")
                val samples = mutableListOf<Sample>()
                for (y in floor(bounds.top).toInt() until ceil(bounds.bottom).toInt()) {
                    val background = bitmap.getColor(backgroundX, y)
                    for (otherX in listOf(backgroundX - 4, backgroundX + 4)) {
                        val other = bitmap.getColor(otherX, y)
                        assertTrue(CHANNELS.all { kotlin.math.abs(channel(other, it) - channel(background, it)) <= 2 },
                            "采样前提：相邻底色必须均匀，不能把描边/其他文字/阴影计为内容墨迹；$metrics")
                    }
                    for (x in floor(bounds.left).toInt() until ceil(bounds.right).toInt()) {
                        val pixel = bitmap.getColor(x, y)
                        val delta = DoubleArray(3) { channel(pixel, CHANNELS[it]).toDouble() - channel(background, CHANNELS[it]) }
                        if (delta.sumOf { it * it } > 3.0 * 48.0 * 48.0) samples += Sample(x, y, delta)
                    }
                }
                assertTrue(samples.size >= 24,
                    "采样前提：最终真实文字必须有足够高对比核心像素；bounds=$bounds backgroundX=$backgroundX samples=${samples.size} $metrics")
                return GlyphProbe(bounds, backgroundX, samples,
                    if (metrics.isEmpty()) "" else "$metrics sampled=$bounds backgroundX=$backgroundX corePixels=${samples.size}")
            }
        }
    }
}

/** Natural Text size, not its requested lineHeight, defines the complete label sampling rectangle. */
internal fun assertCompleteNaturalLabel(bounds: Rect, layoutSize: IntSize, subject: Rect, ellipsized: Boolean) {
    val metrics = "semantics=$bounds layoutSize=$layoutSize subject=$subject"
    assertTrue(layoutSize.width > 0 && layoutSize.height > 0, "采样前提：自然文字布局不能为空；$metrics")
    assertFalse(ellipsized, "采样前提：完整自然标签不得省略；$metrics")
    assertEquals(layoutSize.width.toFloat(), bounds.width, .5f, "采样前提：不得裁剪字形或采整Row；$metrics")
    assertEquals(layoutSize.height.toFloat(), bounds.height, .5f, "采样前提：不得裁剪自然文字高度；$metrics")
    assertTrue(bounds.left >= subject.left && bounds.top >= subject.top && bounds.right <= subject.right &&
        bounds.bottom + 8f < subject.bottom, "采样前提：完整自然标签及原8px净空必须在实际容器内；$metrics")
}
