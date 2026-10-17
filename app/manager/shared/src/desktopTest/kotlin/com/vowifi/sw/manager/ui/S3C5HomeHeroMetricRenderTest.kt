@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class S3C5HomeHeroMetricRenderTest {
    @Test fun actualHomeHeroMetricHasADistinctIntermediateNumericFrame() = onMotionTestThread { runTest {
        S3HomeMetricFixture(testScheduler, durationScale = 1f, initial = "1", targets = listOf("2")).use { fixture ->
            val region = fixture.numberBounds("1")
            val start = s3SmallPatch(fixture.environment, testScheduler, region)
            fixture.tapNext()
            assertEquals(1, fixture.accepted)
            assertEquals("2", fixture.value.value)
            fixture.environment.advanceFrameBy(0)
            fixture.frames(2)
            val middle = s3SmallPatch(fixture.environment, testScheduler, region)
            fixture.frames(16)
            val finalBounds = fixture.numberBounds("2")
            assertEquals(region.left, finalBounds.left, .5f, "采样前提：tnum 数字起点稳定")
            assertEquals(region.top, finalBounds.top, .5f)
            assertEquals(region.width, finalBounds.width, .5f, "采样前提：两个数字须等宽")
            val end = s3SmallPatch(fixture.environment, testScheduler, region)
            fun difference(a: S3SmallPatch, b: S3SmallPatch) = a.pixels.indices.count { s3RgbDistance(a.pixels[it], b.pixels[it]) > 8 }
            assertTrue(difference(start, end) >= 4, "采样前提：生产字形必须确实发生变化")
            assertTrue(difference(middle, start) >= 4 && difference(middle, end) >= 4,
                "C5：实际 HomeHeroCard 内数值在32ms须有中间绘制，不能一步替换")
            assertEquals(1, fixture.accepted, "渲染帧不触发下一业务快照")
        }
    } }

    @Test fun unknownRemainsUnknownAndDoesNotBecomeZeroInEitherDirection() = onMotionTestThread { runTest {
        S3HomeMetricFixture(testScheduler, durationScale = 1f, initial = "1", targets = listOf("未知", "1")).use { fixture ->
            fixture.tapNext()
            assertEquals("未知", fixture.value.value)
            fixture.environment.advanceFrameBy(0)
            repeat(16) {
                fixture.frames()
                assertTrue("0" !in fixture.texts(), "C5：未知值不得被生产显示语义当成0")
            }
            fixture.numberBounds("未知")
            fixture.tapNext()
            assertEquals("1", fixture.value.value)
            repeat(16) {
                fixture.frames()
                assertTrue("0" !in fixture.texts(), "未知→数字也不能制造0作为显示值")
            }
            fixture.numberBounds("1")
            assertEquals(2, fixture.accepted)
        }
    } }

    @Test fun zeroScaleImmediatelyUsesTheActualTargetNumber() = onMotionTestThread { runTest {
        S3HomeMetricFixture(testScheduler, durationScale = 0f, initial = "1", targets = listOf("2")).use { fixture ->
            fixture.tapNext()
            fixture.frames(2)
            assertEquals("2", fixture.value.value)
            fixture.numberBounds("2")
            assertTrue("1" !in fixture.texts())
            assertEquals(1, fixture.accepted)
        }
    } }

    @Test fun decreasingMetricAlsoHasAnIntermediateFrameWithOnlyTheCurrentNumberAccessible() = onMotionTestThread { runTest {
        S3HomeMetricFixture(testScheduler, durationScale = 1f, initial = "2", targets = listOf("1")).use { fixture ->
            val region = fixture.numberBounds("2")
            val start = s3SmallPatch(fixture.environment, testScheduler, region)
            fixture.tapNext()
            fixture.environment.advanceFrameBy(0)
            fixture.frames(2)
            val middle = s3SmallPatch(fixture.environment, testScheduler, region)
            assertEquals("1", fixture.value.value)
            assertTrue("1" in fixture.texts() && "2" !in fixture.texts(), "C5：出场数字不得继续暴露为当前值")
            fixture.frames(16)
            val end = s3SmallPatch(fixture.environment, testScheduler, region)
            assertTrue(s3MetricDifference(start, end) >= 4, "采样前提：递减的真实字形须不同")
            assertTrue(s3MetricDifference(middle, start) >= 4 && s3MetricDifference(middle, end) >= 4,
                "C5：递减也须有 NumericRoll 中间绘制，不得一步替换")
            assertEquals(1, fixture.accepted)
        }
    } }

    @Test fun anAcceptedSameValueSnapshotDoesNotReplayTheMetricTransition() = onMotionTestThread { runTest {
        S3HomeMetricFixture(testScheduler, durationScale = 1f, initial = "1", targets = listOf("1")).use { fixture ->
            val region = fixture.numberBounds("1")
            val start = s3SmallPatch(fixture.environment, testScheduler, region)
            fixture.tapNext()
            fixture.environment.advanceFrameBy(0)
            repeat(16) {
                fixture.frames()
                assertEquals(0, s3MetricDifference(start, s3SmallPatch(fixture.environment, testScheduler, region)),
                    "C5：真实受理的同值快照不重演淡变或位移")
            }
            assertEquals(1, fixture.accepted)
        }
    } }

    @Test fun incomingNumberMovesFromTheOppositeSideWithinEightDpInBothDirections() = onMotionTestThread { runTest {
        listOf("1" to "2", "2" to "1").forEach { (initial, target) ->
            S3HomeMetricFixture(testScheduler, durationScale = 1f, initial = initial, targets = listOf(target)).use { fixture ->
                val start = fixture.numberBounds(initial)
                fixture.tapNext()
                fixture.environment.advanceFrameBy(0)
                fixture.frames(2)
                val middle = fixture.numberBounds(target)
                fixture.frames(16)
                val end = fixture.numberBounds(target)
                assertEquals(start.top, end.top, .5f, "采样前提：数字终态必须位于同一基线")
                val incomingOffset = middle.top - end.top
                if (target > initial) assertTrue(incomingOffset > .25f && incomingOffset <= 8.5f,
                    "C5：增大时新数字从下方8dp范围移入，实际=$incomingOffset")
                else assertTrue(incomingOffset < -.25f && incomingOffset >= -8.5f,
                    "C5：减小时新数字从上方8dp范围移入，实际=$incomingOffset")
                assertEquals(1, fixture.accepted)
            }
        }
    } }

    @Test fun noSimIsANonNumericCurrentValueAndNeverAnInventedZero() = onMotionTestThread { runTest {
        S3HomeMetricFixture(testScheduler, durationScale = 1f, initial = "1", targets = listOf("无 SIM", "1")).use { fixture ->
            fixture.tapNext()
            fixture.environment.advanceFrameBy(0)
            repeat(16) {
                fixture.frames()
                assertTrue("无 SIM" in fixture.texts() && "0" !in fixture.texts() && "1" !in fixture.texts())
            }
            fixture.tapNext()
            repeat(16) {
                fixture.frames()
                assertTrue("1" in fixture.texts() && "0" !in fixture.texts() && "无 SIM" !in fixture.texts())
            }
            assertEquals(2, fixture.accepted)
        }
    } }

    @Test fun actualHeroTitleAndSummaryCrossfadeInPlaceWithoutExposingOutgoingText() = onMotionTestThread { runTest {
        S3HomeMetricFixture(testScheduler, durationScale = 1f, initial = "1", targets = listOf("2"),
            heroText = { value -> if (value == "1") HomeHeroTextBlock("11", listOf("22")) else HomeHeroTextBlock("88", listOf("99")) },
        ).use { fixture ->
            val titleRegion = fixture.numberBounds("11")
            val summaryRegion = fixture.numberBounds("22")
            val titleStart = s3SmallPatch(fixture.environment, testScheduler, titleRegion)
            val summaryStart = s3SmallPatch(fixture.environment, testScheduler, summaryRegion)
            fixture.tapNext()
            fixture.environment.advanceFrameBy(0)
            fixture.frames(2)
            val titleMiddle = s3SmallPatch(fixture.environment, testScheduler, titleRegion)
            val summaryMiddle = s3SmallPatch(fixture.environment, testScheduler, summaryRegion)
            assertTrue("88" in fixture.texts() && "99" in fixture.texts() && "11" !in fixture.texts() && "22" !in fixture.texts())
            fixture.frames(16)
            assertEquals(titleRegion, fixture.numberBounds("88"), "采样前提：等宽标题的原位几何须保持")
            assertEquals(summaryRegion, fixture.numberBounds("99"), "采样前提：等宽说明的原位几何须保持")
            val background = fixture.palette.surfaceRaised.toArgb()
            fun assertFading(start: S3SmallPatch, middle: S3SmallPatch, end: S3SmallPatch, foreground: Int) {
                fun coverage(patch: S3SmallPatch, i: Int) = s3Coverage(patch.pixels[i], background, foreground)
                val oldOnly = start.pixels.indices.filter { coverage(start, it) > .7f && coverage(end, it) < .03f }
                val newOnly = start.pixels.indices.filter { coverage(end, it) > .7f && coverage(start, it) < .03f }
                assertTrue(oldOnly.size >= 2 && newOnly.size >= 2, "采样前提：真实主卡字体须有可区分字形点")
                val outgoing = oldOnly.map { coverage(middle, it) / coverage(start, it) }.average()
                val incoming = newOnly.map { coverage(middle, it) / coverage(end, it) }.average()
                assertTrue(outgoing > .03 && outgoing < .97 && incoming > .03 && incoming < .97,
                    "C5：主卡标题/说明须同时保留旧字形淡出与新字形淡入；实际旧=$outgoing，新=$incoming")
            }
            assertFading(titleStart, titleMiddle, s3SmallPatch(fixture.environment, testScheduler, titleRegion), fixture.palette.textPrimary.toArgb())
            assertFading(summaryStart, summaryMiddle, s3SmallPatch(fixture.environment, testScheduler, summaryRegion), fixture.palette.textSecondary.toArgb())
            assertEquals(1, fixture.accepted)
        }
    } }

    @Test fun changingThemeAndFontScaleDuringTheRollImmediatelyUsesTheNewEnvironment() = onMotionTestThread { runTest {
        S3HomeMetricFixture(testScheduler, durationScale = 1f, initial = "1", targets = listOf("2")).use { fixture ->
            fixture.tapNext()
            fixture.environment.advanceFrameBy(0)
            fixture.frames(2)
            fixture.dark.value = true
            fixture.fontScale.value = 1.2f
            fixture.frames(2)
            val region = fixture.numberBounds("2")
            val direct = s3SmallPatch(fixture.environment, testScheduler, region)
            assertTrue("1" !in fixture.texts())
            fixture.frames(16)
            assertEquals(region, fixture.numberBounds("2"))
            assertEquals(0, s3MetricDifference(direct, s3SmallPatch(fixture.environment, testScheduler, region)),
                "C5：主题/字号变化应直接重排到当前值，不把旧环境的业务动效拖入新环境")
            assertEquals(1, fixture.accepted)
        }
    } }
}

private fun s3MetricDifference(a: S3SmallPatch, b: S3SmallPatch) =
    a.pixels.indices.count { s3RgbDistance(a.pixels[it], b.pixels[it]) > 8 }

// HomeMetricCard 是私有生产叶；必须经实际 HomeTopGrid 进入，不能造测试数字 Text。
private class S3HomeMetricFixture(
    private val scheduler: TestCoroutineScheduler,
    durationScale: Float,
    initial: String,
    private val targets: List<String>,
    private val heroText: (String) -> HomeHeroTextBlock = { HomeHeroTextBlock("状态汇总", listOf("受控源快照")) },
) : AutoCloseable {
    val value = mutableStateOf(initial)
    val dark = mutableStateOf(false)
    val fontScale = mutableStateOf(1f)
    lateinit var palette: SoftVoWiFiColors
    var accepted = 0
        private set
    private val haptics = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
    }
    val environment = MotionTestEnvironment(scheduler, width = 640, height = 360, durationScale = durationScale) {
        SoftVoWiFiTheme(dark.value) {
            val colors = LocalSoftVoWiFiColors.current
            SideEffect { palette = colors }
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale.value), LocalHapticFeedback provides haptics) {
                Column(Modifier.fillMaxSize().background(LocalSoftVoWiFiColors.current.canvas).padding(24.dp)) {
                    val text = heroText(value.value)
                    HomeTopGrid(HomeDashboardPresentation(
                        notices = emptyList(), hero = HomeHeroPresentation(SemanticTone.NEUTRAL, "状态", text, text),
                        metrics = listOf(HomeMetricPresentation(value.value, "运行中引擎", SemanticTone.NEUTRAL)),
                        actions = emptyList(), facts = emptyList(),
                    ))
                    ManagerTextAction("下一快照", ManagerAction.Refresh, {
                        value.value = targets[accepted]
                        accepted++
                    })
                }
            }
        }
    }
    init { frames(3) }
    fun frames(count: Int = 1) { repeat(count) { environment.advanceFrameBy(16) } }
    private fun nodes(unmerged: Boolean = true): List<SemanticsNode> = environment.scene.semanticsOwners.flatMap { owner ->
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        tree(if (unmerged) owner.unmergedRootSemanticsNode else owner.rootSemanticsNode)
    }
    fun texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
    fun numberBounds(text: String): Rect = s3TextBounds(nodes(), text)
    fun tapNext() {
        val point = nodes(unmerged = false).single { it.config.contains(SemanticsActions.OnClick) }.boundsInRoot.center
        environment.press(point)
        environment.advanceFrameBy(8)
        environment.release(point)
    }
    override fun close() = environment.close()
}
