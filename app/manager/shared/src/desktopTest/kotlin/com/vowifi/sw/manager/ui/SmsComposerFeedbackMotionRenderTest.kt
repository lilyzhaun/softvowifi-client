@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.*

/** Real Composer, native editing and rendered text; no send request or substitute animation. */
class SmsComposerFeedbackMotionRenderTest {
    @Test fun acceptedNoticeHasWholeGlyphIntermediateInkInsteadOfClippedOrInstantText() = onMotionTestThread { runTest {
        val label = "已受理，不代表接收设备已收到。"
        var background = 0
        val reference = ComposerRig(testScheduler, 0f).use { f ->
            f.outcome.value = OutgoingMessageStatus.ACCEPTED; f.consume()
            background = f.canvasArgb
            f.noticeInk(label)
        }
        val terminal = reference.second
        val mask = terminal.pixels.indices.filter { s3RgbDistance(terminal.pixels[it], background) > 20 }
        assertTrue(mask.size >= 8, "前提：零动画真实字形至少8个高对比点")
        assertTrue(terminal.pixels.count { s3RgbDistance(it, background) <= 2 } >= terminal.pixels.size / 3,
            "前提：known字形周围必须确为Rig均匀canvas，不以另一表面猜背景")
        ComposerRig(testScheduler, 1f).use { f ->
            assertEquals(background, f.canvasArgb)
            f.outcome.value = OutgoingMessageStatus.ACCEPTED; f.consume(); f.frames(4)
            val current = f.noticeInk(label)
            assertEquals(reference.first, current.first, "前提：整段真实Text自然bounds必须原位、未裁切")
            assertEquals(terminal.width, current.second.width); assertEquals(terminal.height, current.second.height)
            val ink = current.second.pixels
            assertTrue(mask.count { s3RgbDistance(ink[it], background) > 2 } >= mask.size * .8,
                "前提：至少80%原字形仍有墨迹，不能把父裁剪露出的残片当淡入")
            fun rgbDiff(pixel: Int) = listOf(16, 8, 0).sumOf { shift -> abs((pixel ushr shift and 255) - (background ushr shift and 255)) }
            val ratio = mask.sumOf { rgbDiff(ink[it]).toDouble() } /
                mask.sumOf { rgbDiff(terminal.pixels[it]).toDouble() }
            assertTrue(ratio in .05.. .95, "G1：64ms字形须有真实alpha中间墨迹，旧直接插入约为1；ratio=$ratio")
            assertEquals(0, f.sendCalls)
        }
    } }

    @Test fun authoritativeOutcomeGivesSpaceGraduallyInsteadOfInsertingAWholeTextRow() = onMotionTestThread { runTest {
        for (busy in listOf(false, true)) {
            val acceptedHeight = ComposerRig(testScheduler, 0f, busy).use { reference ->
                reference.outcome.value = OutgoingMessageStatus.ACCEPTED
                reference.consume()
                reference.height()
            }
            ComposerRig(testScheduler, 1f, busy).use { f ->
                val original = f.height()
                assertTrue(acceptedHeight > original + 12f, "前提：真实结果文字必须使Composer实际增高")
                f.outcome.value = OutgoingMessageStatus.ACCEPTED
                f.consume(); f.frames(4)
                val middle = f.height()
                f.frames(80)
                assertEquals(acceptedHeight, f.height(), 1f, "正常/zero、忙碌/非忙碌的已受理终值须相同")
                assertTrue(middle > original + 1f && middle < acceptedHeight - 1f,
                    "G1：64ms必须实际让位，不能null→Text一帧插入全部高度；$original/$middle/$acceptedHeight")
                assertEquals(0, f.sendCalls)
                assertEquals(busy, f.field().config.getOrNull(SemanticsProperties.Disabled) != null)
            }
        }
    } }

    @Test fun longNoticeReplacementCanReverseWithoutJumpingToEitherCompleteLayout() = onMotionTestThread { runTest {
        val long = "合成结果说明：暂时无法确认结果，请保留输入并核对，不会自动重试。".repeat(2)
        val shortHeight = ComposerRig(testScheduler, 0f).use { f ->
            f.notice.value = "合成说明"; f.consume(); f.height()
        }
        val longHeight = ComposerRig(testScheduler, 0f).use { f ->
            f.notice.value = long; f.consume(); f.height()
        }
        assertTrue(longHeight > shortHeight + 24f, "前提：真实CJK排版确实换行，不用字符串长度冒充增高")
        ComposerRig(testScheduler, 1f).use { f ->
            f.notice.value = "合成说明"; f.consume(); f.frames(80)
            assertEquals(shortHeight, f.height(), 1f)
            f.notice.value = long; f.consume(); f.frames(4)
            val expanding = f.height()
            assertTrue(expanding > shortHeight + 1f && expanding < longHeight - 1f,
                "G1：长说明替换不能瞬间切到完整长布局")
            f.notice.value = "合成说明"; f.consume()
            assertEquals(expanding, f.height(), 1f, "反向目标提交的同刻不能跳回短说明布局")
            f.frames(2)
            assertTrue(f.height() > shortHeight + 1f && f.height() < longHeight + 1f)
            f.frames(80); assertEquals(shortHeight, f.height(), 1f)
            f.outcome.value = OutgoingMessageStatus.FAILED; f.consume(); f.frames(80)
            assertTrue(f.texts().any { it == "发送未完成，草稿已保留。" })
            assertEquals("甲", f.body.value); assertEquals(0, f.sendCalls)
        }
    } }

    @Test fun nativeMultilineEditingUsesTheApprovedSpringAndPreservesVelocityOnReverse() = onMotionTestThread { runTest {
        val multiline = "甲\n乙\n丙\n丁\n戊"
        var originalFieldHeight = 0f
        var expandedFieldHeight = 0f
        var shortHeight = 0f
        var longHeight = 0f
        ComposerRig(testScheduler, 0f).use { f ->
            originalFieldHeight = f.field().boundsInRoot.height; shortHeight = f.height()
            f.edit(multiline); f.consume()
            assertEquals(multiline, f.body.value, "前提：通过实际TextField SetText进入生产onBodyChange")
            expandedFieldHeight = f.field().boundsInRoot.height; longHeight = f.height()
            assertTrue(expandedFieldHeight > originalFieldHeight + 60f,
                "前提：真实可编辑控件必须已多行增高；库若仍单行，此项不是有效动画RED")
            assertTrue(longHeight > shortHeight + 60f)
            f.edit("甲"); f.consume(); assertEquals(shortHeight, f.height(), 1f)
        }
        fun trajectory(from: Float, to: Float, velocity: Float = 0f) = TargetBasedAnimation(
            animationSpec = spring<Float>(dampingRatio = MotionTokens.SpringSpatialFast.dampingRatio,
                stiffness = MotionTokens.SpringSpatialFast.stiffness, visibilityThreshold = 1f),
            typeConverter = Float.VectorConverter, initialValue = from, targetValue = to, initialVelocity = velocity)
        ComposerRig(testScheduler, 1f).use { f ->
            assertEquals(originalFieldHeight, f.field().boundsInRoot.height, 1f)
            f.edit(multiline); f.consume(); f.frames(9)
            val forward = trajectory(shortHeight, longHeight)
            // Only the first 0–16ms frame phase is variable, never a fit to the old tween.
            val phase = (0L..16L).minBy { shift ->
                abs(forward.getValueFromNanos((144L - shift) * 1_000_000L) - f.height())
            }
            val at = (144L - phase) * 1_000_000L
            val predicted = forward.getValueFromNanos(at)
            assertTrue(longHeight - predicted > 2f, "前提：独立spring此时确未到终值，才能拒绝旧120ms tween")
            assertEquals(predicted, f.height(), 2f,
                "G3：144ms须匹配独立SpringSpatialFast而非已经结束的120ms补间")
            val beforeReverse = f.height()
            f.edit("甲"); f.consume()
            assertEquals(beforeReverse, f.height(), 1f, "反转提交同刻不能重置位置")
            val reverse = trajectory(beforeReverse, shortHeight, forward.getVelocityVectorFromNanos(at).value)
            f.frames(2)
            val allowed = (16L..32L).map { reverse.getValueFromNanos(it * 1_000_000L) }
            assertTrue(f.height() >= allowed.min() - 2f && f.height() <= allowed.max() + 2f,
                "反转必须承接独立spring速度；不能只断言存在某种尺寸动画")
            f.frames(80)
            assertEquals(shortHeight, f.height(), 1f)
            assertEquals(originalFieldHeight, f.field().boundsInRoot.height, 1f)
            assertEquals("甲", f.body.value); assertEquals(0, f.sendCalls)
        }
    } }

    private class ComposerRig(scheduler: TestCoroutineScheduler, scale: Float, busy: Boolean = false) : AutoCloseable {
        val body = mutableStateOf("甲")
        val outcome = mutableStateOf<OutgoingMessageStatus?>(null)
        val notice = mutableStateOf<String?>(null)
        var sendCalls = 0
        var canvasArgb = 0
        private val clock = scheduler
        private val e = MotionTestEnvironment(scheduler, 280, 1000, durationScale = scale) {
            SoftVoWiFiTheme(false) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    val canvas = LocalSoftVoWiFiColors.current.canvas; SideEffect { canvasArgb = canvas.toArgb() }
                    Column(Modifier.fillMaxWidth().background(LocalSoftVoWiFiColors.current.canvas)) {
                        MessageComposer(body.value, MessageComposeSpec(MessageComposeMode.ENABLED, notice.value),
                            MessageImeOwner.COMPOSE_COMPOSER_PADDING, {}, busy, true,
                            { body.value = it }, { sendCalls++ }, outcome = outcome.value)
                        Box(Modifier.size(4.dp).semantics { testTag = "composer-successor" })
                    }
                }
            }
        }
        init { frames(80) }
        fun frames(n: Int) { repeat(n) { e.advanceFrameBy(16) } }
        fun consume() { repeat(4) { e.advanceFrameBy(0) } }
        fun field(): SemanticsNode = s4Nodes(e).single {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("短信内容") == true &&
                it.config.contains(SemanticsProperties.EditableText)
        }
        fun edit(value: String) {
            assertTrue(requireNotNull(field().config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
        }
        fun height(): Float = s4Nodes(e).single {
            it.config.getOrNull(SemanticsProperties.TestTag) == "composer-successor"
        }.boundsInRoot.top
        fun texts(): List<String> = s4Nodes(e).flatMap {
            it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text }
        }
        fun noticeInk(label: String): Pair<Rect, S3SmallPatch> {
            val n = s4Nodes(e).single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == label && it.config.contains(SemanticsActions.GetTextLayoutResult) }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(requireNotNull(n.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts))
            val layout = layouts.single(); assertEquals(label, layout.layoutInput.text.text)
            assertEquals(1, layout.lineCount); assertFalse(layout.isLineEllipsized(0))
            val natural = n.boundsInRoot
            assertEquals(layout.size.width.toFloat(), natural.width, .5f); assertEquals(layout.size.height.toFloat(), natural.height, .5f)
            val boxes = (0..3).map(layout::getBoundingBox)
            val region = Rect(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom }).translate(natural.topLeft)
            return natural to s3SmallPatch(e, clock, region)
        }
        override fun close() { e.close() }
    }
}
