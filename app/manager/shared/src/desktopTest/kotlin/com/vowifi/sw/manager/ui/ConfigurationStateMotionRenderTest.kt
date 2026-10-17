package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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

/** S3 C7/C8/C9 草案：旧生产 API、真实 draft、真实指针与既有统一帧时钟。尚未执行。 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConfigurationStateMotionRenderTest {
    @Test fun collapsingContentFadesBeforeRemovalAndCannotSubmitFromItsOldPosition() = onMotionTestThread { runTest {
        Fixture(testScheduler, initiallyExpanded = true).use { f ->
            f.editSmscDraft("+123")
            f.advance(1200)
            val probe = f.probe("运营商 001 / 01")
            val oldSavePoint = f.text("保存配置").boundsInRoot.center
            assertTrue(f.draft.dirty)
            f.tapText(Fixture.TITLE)
            assertFalse(f.expanded, "前提：真实头部点击已经提交折叠")
            f.advance(19) // 加上 tapText 的首帧为抬起后 20ms。
            f.assertNotClipped(probe)
            assertTrue(f.strength(probe) > .05, "C7：退出 20ms 仍须绘制旧内容，不能 if 分支立即移除")
            assertFalse(f.nodes(merged = true).any {
                it.config.contains(SemanticsActions.SetText) ||
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "保存配置" } == true
            }, "C7：退场旧内容保留绘制，但立即撤销编辑／保存的公开语义")
            f.tapPoint(oldSavePoint)
            f.advance(1000)
            assertEquals(0, f.submissions, "退出内容只留绘制，不得由旧按钮位置提交配置")
            assertTrue(f.draft.dirty)
            assertTrue(f.strength(probe) < .03, "退出完成后旧内容应消失")
            assertTrue(f.text(Fixture.TITLE).boundsInRoot.top >= 0f, "折叠头仍在可见范围")
        }
    } }

    @Test fun expandedContentWaits50msThenFadesFor150ms() = onMotionTestThread { runTest {
        Fixture(testScheduler).use { f ->
            f.tapText(Fixture.TITLE)
            f.advance(1200)
            assertTrue(f.expanded)
            val probe = f.probe("运营商 001 / 01")
            f.tapText(Fixture.TITLE)
            f.advance(1200)
            assertFalse(f.expanded)
            assertTrue(f.strength(probe) < .03, "前提：稳定折叠后没有该文字墨迹")

            f.tapText(Fixture.TITLE)
            assertTrue(f.expanded, "前提：再次展开由同一真实头部提交")
            f.advance(48) // 抬起后 49ms：50ms 延迟尚未结束。
            f.assertNotClipped(probe)
            assertTrue(f.strength(probe) < .05, "C7：内容不能在 50ms 延迟结束前直接显现")
            f.advance(76) // 抬起后 125ms：淡入区间的中间帧。
            val middle = f.strength(probe)
            assertTrue(middle > .15 && middle < .95, "C7：150ms 淡入必须有真实中间墨迹，实际=$middle")
            f.advance(100) // 抬起后 225ms：50ms + 150ms 已结束。
            assertTrue(f.strength(probe) in .95..1.05, "进入终态须恢复同一内容原色")
            assertEquals(0, f.submissions)
        }
    } }

    @Test fun latestDraftSurvivesRealPointerReversal() = onMotionTestThread { runTest {
        Fixture(testScheduler, initiallyExpanded = true).use { f ->
            f.openAdvanced()
            f.setSmsc("+123")
            f.advance(1000)
            val probe = f.probe("运营商 001 / 01")
            f.tapText(Fixture.TITLE)
            f.advance(19)
            assertFalse(f.expanded)
            assertTrue(f.strength(probe) > .05, "前提：反向点按发生在真实旧内容退场期间")
            f.tapText(Fixture.TITLE)
            assertTrue(f.expanded, "C7：真实头部可直接反向，不等退场或卡片高度完成")
            f.advance(1000)
            assertEquals("+123", f.draft.smsc.text.toString())
            assertTrue(f.draft.dirty)
            assertTrue(f.nodes().any { it.config.contains(SemanticsActions.SetText) }, "途中反向不能丢失仍保留的高级设置")
            f.setSmsc("+456")
            f.tapText(Fixture.TITLE)
            f.advance(19)
            f.tapText(Fixture.TITLE)
            assertTrue(f.expanded)
            f.advance(1000)
            assertEquals("+456", f.draft.smsc.text.toString(), "反向呈现只消费同一个实际 draft，不重建或清草稿")
            assertTrue(f.draft.dirty)
            assertEquals(0, f.submissions)
        }
    } }

    @Test fun dirtyActionGroupEntersByHeightAndFadeRatherThanImmediateInsertion() = onMotionTestThread { runTest {
        // 隔离实际叶组件，避免 ConfigurationEditor 的外层卡片高度裁剪掩盖 Row 的即时插入。
        Fixture(testScheduler, actionsOnly = true).use { f ->
            assertFalse(f.draft.dirty)
            assertFalse(f.hasText("保存配置"))
            f.editSmscDraft("+123")
            f.advance(1000)
            // Both actions share the same C9 group; use its dark secondary label for an unambiguous ink probe.
            // The probe checks the complete natural layout and clearance before capturing any ink.
            val probe = f.probe("放弃修改", naturalBounds = true)
            val fullHeight = f.subjectBounds().height
            val save = f.text("保存配置").boundsInRoot
            val discard = f.text("放弃修改").boundsInRoot
            assertTrue(save.left > discard.left, "前提：实际双列动作已出现")
            f.draft.discard()
            f.advance(1000)
            assertFalse(f.draft.dirty)

            f.editSmscDraft("+123")
            f.advance(1)
            assertTrue(f.draft.dirty, "草稿变脏不等待视觉动画")
            assertTrue(f.subjectBounds().height < fullHeight - 4f, "C9：第一帧不能插入完整按钮组高度；${probe.metrics}")
            assertTrue(f.strength(probe) < .95, "C9：第一帧不能出现完整按钮文字墨迹；${probe.metrics}")
            f.advance(1000)
            assertEquals(fullHeight, f.subjectBounds().height, 1f, probe.metrics)
            assertTrue(f.strength(probe) in .95..1.05, probe.metrics)
            assertEquals(0, f.submissions, "呈现不得自动提交配置")
        }
    } }

    @Test fun validationExplanationFadesInAfterARealInvalidSave() = onMotionTestThread { runTest {
        Fixture(testScheduler, initiallyExpanded = true).use { f ->
            f.openAdvanced()
            f.setSmsc("invalid")
            f.advance(1000) // Let the existing card/dirty-row layout settle before the real save tap.
            f.tapText("保存配置")
            f.advance(1000)
            assertEquals(ConfigurationEditStatus.Invalid, f.draft.status)
            assertTrue(f.hasErrorSemantics())
            val message = requireNotNull(f.draft.errors[ConfigurationFieldKey.Smsc])
            val probe = f.probe(message)
            f.setSmsc("+123")
            f.advance(1000)
            assertFalse(f.hasErrorSemantics(), "前提：合法输入确已清错误")
            assertTrue(f.strength(probe) < .03, "前提：错误稳定退场后局部墨迹为空")

            // 真实 draft 的 validationRequested 已成立；后继非法编辑直接产生当前校验结果。
            f.setSmsc("invalid", afterMillis = 1)
            assertTrue(f.hasErrorSemantics(), "错误目标/语义即时反映真实校验，不等待淡入")
            f.assertNotClipped(probe)
            assertTrue(f.strength(probe) < .95, "C8：错误说明不能第一帧直接完整插入")
            f.advance(225)
            assertTrue(f.strength(probe) in .95..1.05, "150ms 淡入完成后说明回到真实原色")
            assertEquals(0, f.submissions, "非法保存与呈现都不能调用后端")
        }
    } }

    @Test fun legitimateInputClearsValidationWithoutAnotherSave() = onMotionTestThread { runTest {
        Fixture(testScheduler, initiallyExpanded = true).use { f ->
            f.openAdvanced()
            f.setSmsc("invalid")
            f.advance(1000) // Validation assertions require a stable, actually hittable save action.
            f.tapText("保存配置")
            f.advance(225)
            assertEquals(ConfigurationEditStatus.Invalid, f.draft.status)
            assertTrue(f.hasErrorSemantics())
            assertEquals(0, f.submissions)
            f.setSmsc("+123", afterMillis = 1)
            assertTrue(f.draft.errors.isEmpty(), "旧业务守护：合法编辑立即清当前错误")
            assertFalse(f.hasErrorSemantics(), "语义不能保留已过期错误到下一次保存")
            assertFalse(configurationEditSummary(f.draft).contains("请检查"))
            assertEquals("+123", f.draft.smsc.text.toString())
            assertTrue(f.draft.dirty)
            assertEquals(0, f.submissions)
        }
    } }

    @Test fun pendingSaveFreezesInputAndDiscardAndFailureKeepsTheDraft() = onMotionTestThread { runTest {
        Fixture(testScheduler, initiallyExpanded = true).use { f ->
            f.openAdvanced()
            f.setSmsc("+123")
            f.advance(1000)
            f.tapText("保存配置")
            assertEquals(1, f.submissions, "真实单次点按应受理一次保存")
            assertTrue(f.draft.pending)
            assertTrue(f.nodes(merged = true).any {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "+123" &&
                    it.config.contains(SemanticsProperties.Disabled)
            }, "旧业务守护：真实字段在 pending 立即禁用")
            f.tapText("放弃修改")
            f.tapText("正在保存")
            f.advance(600)
            assertTrue(f.draft.pending)
            assertTrue(f.draft.dirty)
            assertEquals("+123", f.draft.smsc.text.toString())
            assertEquals(1, f.submissions, "pending 重复点击不能再提交")
            assertFalse(f.hasText("放弃修改？"), "pending 不能打开放弃确认")
            f.draft.discard()
            assertEquals("+123", f.draft.smsc.text.toString(), "draft 内部放弃保护也应保持")
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

    internal class Fixture(
        private val scheduler: TestCoroutineScheduler,
        initiallyExpanded: Boolean = false,
        actionsOnly: Boolean = false,
        durationScale: Float = 1f,
    ) : AutoCloseable {
        val instance = ConfigurationInstance(
            ConfigurationInstanceId("sim-1"), TITLE, ConfigurationLifecycle.STOPPED,
            ConfigurationTransport.AUTO, "", "", "", "", "epdg.invalid", "ims.invalid", "001", "01",
            draftOwnerId = SimOwnerId.fromHmacSha256(ByteArray(32) { 1 }),
        )
        val draft = ConfigurationEditorDraft(instance)
        var expanded by mutableStateOf(initiallyExpanded)
        var submissions = 0
        var completion: ((ConfigurationApplyOutcome) -> Unit)? = null
        var remount by mutableStateOf(0)
        val haptics = mutableListOf<HapticFeedbackType>()
        private val recorder = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
        }
        private val model = ConfigurationEditorModel(instance, ConfigurationUiState.Ready(listOf(instance)), true, null)
        val environment = MotionTestEnvironment(scheduler, width = if (actionsOnly) 640 else 440,
            height = if (actionsOnly) 3200 else 2800, durationScale = durationScale) {
            SoftVoWiFiTheme(false) {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalHapticFeedback provides recorder) {
                    Scaffold(if (actionsOnly) Modifier.fillMaxSize() else Modifier) {
                        Box(Modifier.fillMaxWidth().padding(16.dp).testTag(SUBJECT)) {
                            val submit: com.vowifi.sw.manager.model.ConfigurationSubmit = { _, _, complete ->
                                submissions++
                                completion = complete
                                ConfigurationApplyAcceptance.Accepted
                            }
                            key(remount) { if (actionsOnly) Column {
                                ConfigurationEditActions(model, draft, submit)
                            } else ConfigurationEditor(model, draft, expanded,
                                onToggleExpand = { expanded = !expanded }, onSubmit = submit)
                            }
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
        fun tapPoint(point: Offset) {
            environment.press(point)
            advance(16)
            environment.release(point)
            advance(1)
        }
        fun openAdvanced() {
            tapText("高级设置")
            advance(1000)
            assertTrue(nodes().any { it.config.contains(SemanticsActions.SetText) }, "前提：真实高级设置确已打开")
        }
        fun setSmsc(value: String, afterMillis: Long = 16) {
            val field = nodes().first { it.config.contains(SemanticsActions.SetText) }
            assertTrue(requireNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)), "旧 SetText API 必须命中真实 SMSC 字段")
            advance(afterMillis)
            assertEquals(value, draft.smsc.text.toString(), "前提：必须修改真实 draft，不能只改影子输入")
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
                // C9's actual button Text has natural width. Its semantics rectangle has
                // real height even when GetTextLayoutResult reports a zero-height line.
                // C7/C8/save-summary probes deliberately keep their original line bounds.
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
        override fun close() { environment.close() }
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
