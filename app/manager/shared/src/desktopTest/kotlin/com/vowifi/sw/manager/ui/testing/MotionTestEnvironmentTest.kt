package com.vowifi.sw.manager.ui.testing

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** S0 测试基座合同；不替代 S1 的最短可见测试或实机手感验收。 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class MotionTestEnvironmentTest {
    @Test fun quickTapDeliversRealPressAndReleaseAndClicksOnlyOnRelease() = onMotionTestThread { runTest {
        val probe = ClickProbe()
        MotionTestEnvironment(testScheduler) { probe.Content() }.use { environment ->
            environment.advanceFrameBy()
            environment.advanceFrameBy()
            val downAt = testScheduler.currentTime

            environment.press(Offset(48f, 48f))
            assertEquals(0, probe.clicks, "规范 4.1：按下不能执行点击")
            environment.advanceFrameBy(8)
            val press = assertIs<PressInteraction.Press>(probe.pressInteractions.single())
            environment.release(Offset(48f, 48f))
            assertEquals(downAt + 8, testScheduler.currentTime, "注入不能暗中延长快速点按")
            environment.advanceFrameBy()

            assertEquals(1, probe.clicks)
            assertEquals(2, probe.pressInteractions.size)
            assertSame(press, assertIs<PressInteraction.Release>(probe.pressInteractions[1]).press)
        }
    } }

    @Test fun movingOutsideCancelsTheRealClickableWithoutCallingItsAction() = onMotionTestThread { runTest {
        val probe = ClickProbe()
        MotionTestEnvironment(testScheduler) { probe.Content() }.use { environment ->
            environment.advanceFrameBy()
            environment.advanceFrameBy()
            environment.press(Offset(48f, 48f))
            environment.advanceFrameBy()
            assertIs<PressInteraction.Press>(probe.pressInteractions.single())

            val outside = Offset(200f, 200f)
            environment.move(outside)
            environment.advanceFrameBy()
            environment.release(outside)
            environment.advanceFrameBy()

            assertCanceled(probe)
        }
    } }

    @Test fun touchScrollingTakesOverThePressAndCancelsClick() = onMotionTestThread { runTest {
        val probe = ClickProbe()
        val scroll = ScrollState(0)
        MotionTestEnvironment(testScheduler, height = 160) { probe.ScrollContent(scroll) }.use { environment ->
            environment.advanceFrameBy()
            environment.advanceFrameBy()
            environment.press(Offset(48f, 80f))
            environment.advanceFrameBy(200)
            assertIs<PressInteraction.Press>(probe.pressInteractions.single())

            // 手指始终留在窗口及行内；由真实 verticalScroll 消费位移，不手动发 Cancel。
            environment.move(Offset(48f, 56f))
            environment.advanceFrameBy()
            environment.move(Offset(48f, 28f))
            environment.advanceFrameBy()
            environment.release(Offset(48f, 28f))
            environment.advanceFrameBy()

            assertTrue(scroll.value > 0, "必须证明滚动容器实际接管，不能只证明没有点击")
            assertCanceled(probe)
        }
    } }

    @Test fun wheelEventsScrollTheRealContainerWithoutSynthesizingAPressOrClick() = onMotionTestThread { runTest {
        val probe = ClickProbe()
        val scroll = ScrollState(0)
        MotionTestEnvironment(testScheduler, height = 160) { probe.ScrollContent(scroll) }.use { environment ->
            environment.advanceFrameBy()
            environment.advanceFrameBy()
            environment.scroll(Offset(48f, 48f), Offset(0f, 3f))
            environment.advanceFrameBy()
            environment.advanceFrameBy()

            assertTrue(scroll.value > 0)
            assertEquals(0, probe.clicks)
            assertTrue(probe.interactions.filterIsInstance<PressInteraction>().isEmpty(), "滚轮不能合成按压；允许真实鼠标悬停")
        }
    } }

    @Test fun normalTweenAndSpringActuallyAdvanceOnComposeFrames() = onMotionTestThread { runTest {
        val probe = AnimationProbe()
        MotionTestEnvironment(testScheduler) { probe.Content() }.use { environment ->
            environment.advanceFrameBy()
            environment.advanceFrameBy()
            assertEquals(1f, probe.effectiveDurationScale, "普通场景必须使用自身的缩放值，不继承零动画场景")
            assertEquals(0f, probe.tweenValue.value)
            assertEquals(0f, probe.springValue.value)

            probe.target = 1f
            repeat(3) { environment.advanceFrameBy() }
            assertTrue(probe.tweenValue.value > 0f && probe.tweenValue.value < 1f,
                "普通补间必须经过中间值，实际为 ${probe.tweenValue.value}")
            assertTrue(probe.springValue.value > 0f && probe.springValue.value < 1f,
                "普通弹簧必须经过中间值，实际为 ${probe.springValue.value}")
            environment.advanceFrameBy(2000)
            environment.advanceFrameBy()

            assertEquals(1f, probe.tweenValue.value)
            assertEquals(1f, probe.springValue.value, .001f)
        }
    } }

    @Test fun zeroDurationScaleReachesTheSameTargetsWithoutWaitingForAnimationDuration() = onMotionTestThread { runTest {
        val probe = AnimationProbe()
        MotionTestEnvironment(testScheduler, durationScale = 0f) { probe.Content() }.use { environment ->
            environment.advanceFrameBy()
            environment.advanceFrameBy()
            assertEquals(0f, probe.effectiveDurationScale, "必须接入 Compose 的 MotionDurationScale，不只保存参数")

            probe.target = 1f
            environment.advanceFrameBy()
            environment.advanceFrameBy()
            assertEquals(1f, probe.tweenValue.value, "规范 3.6：不等待 1000ms 补间")
            assertEquals(1f, probe.springValue.value, "规范 3.6：弹簧也直接到相同终值")

            probe.target = 0f
            environment.advanceFrameBy()
            environment.advanceFrameBy()
            assertEquals(0f, probe.tweenValue.value)
            assertEquals(0f, probe.springValue.value, "反向后不能残留旧动画状态")
        }
    } }

    private fun assertCanceled(probe: ClickProbe) {
        assertEquals(0, probe.clicks, "规范 4.6、4.8：取消不能产生业务动作")
        assertEquals(2, probe.pressInteractions.size)
        val press = assertIs<PressInteraction.Press>(probe.pressInteractions[0])
        assertSame(press, assertIs<PressInteraction.Cancel>(probe.pressInteractions[1]).press)
    }

    private class ClickProbe {
        private val source = MutableInteractionSource()
        val interactions = mutableListOf<Interaction>()
        // 真实 clickable 同时发出 Focus；只对按压子序列核对恰好一次及同一 Press 配对。
        val pressInteractions get() = interactions.filterIsInstance<PressInteraction>()
        var clicks = 0

        @Composable fun Content() {
            LaunchedEffect(source) { source.interactions.collect { interactions += it } }
            Box(Modifier.size(96.dp).clickable(interactionSource = source, indication = null) { clicks++ })
        }

        @Composable fun ScrollContent(scroll: ScrollState) {
            Column(Modifier.size(240.dp, 160.dp).verticalScroll(scroll)) {
                Content()
                Spacer(Modifier.height(600.dp))
            }
        }
    }

    private class AnimationProbe {
        var target by mutableStateOf(0f)
        lateinit var tweenValue: State<Float>
        lateinit var springValue: State<Float>
        var effectiveDurationScale: Float? = null

        @Composable fun Content() {
            val tweenState = animateFloatAsState(target, tween(durationMillis = 1000, easing = LinearEasing), label = "test-tween")
            val springState = animateFloatAsState(target, spring(dampingRatio = 1f, stiffness = 700f), label = "test-spring")
            val scope = rememberCoroutineScope()
            SideEffect {
                tweenValue = tweenState
                springValue = springState
                effectiveDurationScale = scope.coroutineContext[MotionDurationScale]?.scaleFactor
            }
            Box(Modifier.size(96.dp).graphicsLayer {
                alpha = tweenState.value
                scaleX = springState.value
                scaleY = springState.value
            })
        }
    }
}
