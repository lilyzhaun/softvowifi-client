package com.vowifi.sw.manager.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults

/**
 * S1 §4.4–4.6 / §5.1: actual pointer -> actual clickable -> existing production motion
 * wrapper/indication -> rendered pixels. No manual Press/Cancel emission,
 * semantic click, animation-value assignment, screenshot file or wall-clock sleep.
 * Candidate REDs are not reproduction evidence until the coordinator runs them.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class, ExperimentalFoundationApi::class)
class ManagerPressMotionTest {
    @Test fun eightMillisecondManagerTapRemainsVisiblyPressedUntilNinetyMillis() =
        assertQuickTapMinimumVisible(Kind.MANAGER)

    @Test fun eightMillisecondPhoneTapRemainsVisiblyPressedUntilNinetyMillis() =
        assertQuickTapMinimumVisible(Kind.PHONE)

    @Test fun eightMillisecondContentTapRemainsVisiblyDimmedUntilNinetyMillis() =
        assertQuickTapMinimumVisible(Kind.CONTENT)

    @Test fun publicIndicationShowsFeedbackFromTheRealScrollableSourceAndPairsReleaseAndCancel() =
        withProbe(Kind.PUBLIC_INDICATION, scrolling = true) { probe, environment, scheduler ->
            val rest = pixels(probe, environment, scheduler)
            assertTrue(probe.presses.isEmpty(), "真实按下前没有合成交互")
            val center = probe.hitBounds.center
            environment.press(center)
            advanceByFrames(environment, 64)
            assertIs<PressInteraction.Press>(probe.presses.single())
            assertEquals(0, probe.clicks)
            val held = pixels(probe, environment, scheduler)
            environment.release(center)
            assertEquals(1, probe.clicks)
            assertEquals(1, probe.haptics.size)
            assertReleasedPair(probe, 0)
            // The old raw observer RED is frozen. Migrate to create(source), never retain raw pointer code.
            assertVisiblePress(probe.kind, rest, held)
            environment.press(center)
            advanceByFrames(environment, 64)
            environment.move(Offset(232f, 152f))
            environment.advanceFrameBy()
            environment.release(Offset(232f, 152f))
            environment.advanceFrameBy()
            assertEquals(4, probe.presses.size)
            val second = assertIs<PressInteraction.Press>(probe.presses[2])
            assertSame(second, assertIs<PressInteraction.Cancel>(probe.presses[3]).press)
            assertEquals(1, probe.clicks, "公开Indication不拥有额外动作，取消不补click")
            assertEquals(1, probe.haptics.size)
        }

    @Test fun sourcedScrollableManagerFollowsItsRealPressAndReleaseWithoutAndroidDelayAssumptions() =
        withProbe(Kind.MANAGER, scrolling = true) { probe, environment, scheduler ->
            val rest = pixels(probe, environment, scheduler)
            val center = probe.hitBounds.center
            assertTrue(probe.presses.isEmpty(), "source尚未按下")
            environment.press(center)
            advanceByFrames(environment, 64)
            assertIs<PressInteraction.Press>(probe.presses.single())
            assertVisiblePress(probe.kind, rest, pixels(probe, environment, scheduler))
            environment.release(center)
            assertEquals(1, probe.clicks, "业务不等待按压恢复动画")
            assertEquals(1, probe.haptics.size)
            assertReleasedPair(probe, 0)
        }

    @Test fun touchScrollingCancelsTheRealPressAndNeverInvokesItsBusinessAction() =
        forEachWrapper { kind -> withProbe(kind, scrolling = true) { probe, environment, scheduler ->
            val rest = pixels(probe, environment, scheduler)
            val center = probe.hitBounds.center
            environment.press(center)
            advanceByFrames(environment, 200)
            repeat(4) { environment.advanceFrameBy(16) }
            assertVisiblePress(kind, rest, pixels(probe, environment, scheduler))
            assertIs<PressInteraction.Press>(probe.presses.single())
            environment.move(center - Offset(0f, 24f))
            environment.advanceFrameBy()
            environment.move(center - Offset(0f, 52f))
            environment.advanceFrameBy()
            assertTrue(probe.scroll.value > 0, "必须证明纵向容器实际消费了触摸位移")
            assertCanceled(probe)
            // Sample cancellation recovery while still holding: a fling must not move the probe offscreen.
            settle(environment)
            assertRest(rest, pixels(probe, environment, scheduler), "滚动取消后绘制恢复")
            environment.release(center - Offset(0f, 52f))
            environment.advanceFrameBy()
            assertEquals(0, probe.clicks)
            assertTrue(probe.haptics.isEmpty())
        } }

    @Test fun movingOutsideCancelsWithoutClickHapticOrResidualDrawing() =
        forEachWrapper { kind -> withProbe(kind) { probe, environment, scheduler ->
            val rest = pixels(probe, environment, scheduler)
            environment.press(probe.hitBounds.center)
            advanceByFrames(environment, 200)
            environment.advanceFrameBy()
            assertVisiblePress(kind, rest, pixels(probe, environment, scheduler))
            val outside = Offset(232f, 152f)
            environment.move(outside)
            environment.advanceFrameBy()
            environment.release(outside)
            environment.advanceFrameBy()
            assertCanceled(probe)
            settle(environment)
            assertRest(rest, pixels(probe, environment, scheduler), "移出取消后无残留")
        } }

    @Test fun disabledBusyAndInactiveControlsDoNotPaintPressOrPerformAnAction() =
        forEachWrapper { kind -> Gate.entries.forEach { gate ->
            withProbe(kind) { probe, environment, scheduler ->
                val rest = pixels(probe, environment, scheduler)
                probe.setGate(gate, false)
                repeat(2) { environment.advanceFrameBy() }
                val center = probe.hitBounds.center
                environment.press(center)
                advanceByFrames(environment, 200)
                environment.advanceFrameBy()
                assertRest(rest, pixels(probe, environment, scheduler), "$kind/$gate 不产生按压绘制")
                environment.release(center)
                environment.advanceFrameBy()
                assertTrue(probe.presses.isEmpty(), "$kind/$gate 不制造 Press")
                assertEquals(0, probe.clicks)
                assertTrue(probe.haptics.isEmpty())
            }
        } }

    @Test fun gateWithdrawalCancelsAHeldGestureAndRestoringTheGateDoesNotResurrectIt() =
        forEachWrapper { kind -> Gate.entries.forEach { gate ->
            withProbe(kind) { probe, environment, scheduler ->
                val rest = pixels(probe, environment, scheduler)
                val center = probe.hitBounds.center
                environment.press(center)
                advanceByFrames(environment, 200) // Same total hold time, actual intervening Compose frames.
                environment.advanceFrameBy()
                assertVisiblePress(kind, rest, pixels(probe, environment, scheduler))
                probe.setGate(gate, false)
                repeat(2) { environment.advanceFrameBy() }
                assertCanceled(probe)
                settle(environment)
                assertRest(rest, pixels(probe, environment, scheduler), "$gate 撤销后的绘制终值")
                probe.setGate(gate, true)
                repeat(2) { environment.advanceFrameBy() }
                assertRest(rest, pixels(probe, environment, scheduler), "重新可用不复活旧 Press")
                environment.release(center)
                environment.advanceFrameBy()
                assertEquals(0, probe.clicks, "已取消的旧手指抬起不能执行业务")
                assertTrue(probe.haptics.isEmpty())
                environment.press(center)
                environment.advanceFrameBy(8)
                environment.release(center)
                assertEquals(1, probe.clicks, "新手势正常可用")
                assertEquals(1, probe.haptics.size)
            }
        } }

    @Test fun gateWithdrawalRemovesDrawingWithinThirtyTwoMillisAndDoesNotReviveTheHeldFinger() =
        forEachWrapper { kind -> Gate.entries.forEach { gate ->
            withProbe(kind) { probe, environment, scheduler ->
                val rest = pixels(probe, environment, scheduler)
                val center = probe.hitBounds.center
                val downAt = scheduler.currentTime
                environment.press(center)
                repeat(4) { environment.advanceFrameBy() }
                assertEquals(downAt + 64, scheduler.currentTime, "撤门禁时尚未达到 90ms")
                assertIs<PressInteraction.Press>(probe.presses.single())
                assertVisiblePress(kind, rest, pixels(probe, environment, scheduler))
                val withdrawnAt = scheduler.currentTime
                probe.setGate(gate, false)
                repeat(2) { environment.advanceFrameBy() }
                assertEquals(withdrawnAt + 32, scheduler.currentTime)
                assertCanceled(probe)
                assertRest(rest, pixels(probe, environment, scheduler), "$kind/$gate 32ms内撤销按压绘制")
                probe.setGate(gate, true)
                repeat(2) { environment.advanceFrameBy() }
                assertRest(rest, pixels(probe, environment, scheduler), "门禁恢复不复活尚未抬起的旧 Press")
                environment.release(center)
                environment.advanceFrameBy()
                assertEquals(0, probe.clicks)
                assertTrue(probe.haptics.isEmpty())
                environment.press(center)
                environment.advanceFrameBy(8)
                environment.release(center)
                assertEquals(1, probe.clicks, "下一次真实手势不被旧取消状态锁住")
                assertEquals(1, probe.haptics.size)
                assertEquals(4, probe.presses.size)
                assertReleasedPair(probe, 2)
            }
        } }

    @Test fun gateWithdrawalAfterSuccessfulReleaseStopsMinimumVisibilityWithinThirtyTwoMillis() =
        forEachWrapper { kind -> Gate.entries.forEach { gate ->
            withProbe(kind) { probe, environment, scheduler ->
                val rest = pixels(probe, environment, scheduler)
                val center = probe.hitBounds.center
                val downAt = scheduler.currentTime
                environment.press(center)
                environment.advanceFrameBy(8)
                environment.release(center)
                assertEquals(downAt + 8, scheduler.currentTime)
                assertEquals(1, probe.clicks, "成功 Release 的业务已经立即执行")
                assertEquals(1, probe.haptics.size)
                assertReleasedPair(probe, 0)
                probe.setGate(gate, false)
                repeat(2) { environment.advanceFrameBy() }
                assertEquals(downAt + 40, scheduler.currentTime, "不等90ms保持窗口结束")
                assertRest(rest, pixels(probe, environment, scheduler), "$kind/$gate 撤销待完成的最短可见周期")
                probe.setGate(gate, true)
                repeat(2) { environment.advanceFrameBy() }
                assertEquals(downAt + 72, scheduler.currentTime)
                assertRest(rest, pixels(probe, environment, scheduler), "旧可见周期不会因门禁恢复而重新出现")
                assertEquals(2, probe.presses.size)
                assertEquals(1, probe.clicks)
                assertEquals(1, probe.haptics.size)
            }
        } }

    @Test fun cancellationBeforeNinetyMillisStartsRecoveryWithoutWaitingForMinimumVisibility() =
        forEachWrapper { kind ->
            var heldSignals: List<Float> = emptyList()
            withProbe(kind) { probe, environment, scheduler ->
                val rest = pixels(probe, environment, scheduler)
                val downAt = scheduler.currentTime
                val center = probe.hitBounds.center
                environment.press(center)
                repeat(3) { environment.advanceFrameBy() }
                assertEquals(downAt + 48, scheduler.currentTime)
                assertIs<PressInteraction.Press>(probe.presses.single())
                assertTrue(signal(kind, rest, pixels(probe, environment, scheduler)) > 0f,
                    "对照前提：已有实际按压绘制")
                heldSignals = List(2) {
                    environment.advanceFrameBy()
                    signal(kind, rest, pixels(probe, environment, scheduler))
                }
                assertEquals(downAt + 80, scheduler.currentTime)
                assertEquals(0, probe.clicks, "对照手指仍按住，不能触发业务")
                environment.release(center)
                assertEquals(1, probe.clicks)
                assertReleasedPair(probe, 0)
            }
            withProbe(kind) { probe, environment, scheduler ->
                val rest = pixels(probe, environment, scheduler)
                val downAt = scheduler.currentTime
                environment.press(probe.hitBounds.center)
                repeat(3) { environment.advanceFrameBy() }
                assertEquals(downAt + 48, scheduler.currentTime)
                assertIs<PressInteraction.Press>(probe.presses.single())
                assertTrue(signal(kind, rest, pixels(probe, environment, scheduler)) > 0f,
                    "取消前提：同一时序下已有实际按压绘制")
                val outside = Offset(232f, 152f)
                environment.move(outside)
                val canceledSignals = List(2) {
                    environment.advanceFrameBy()
                    signal(kind, rest, pixels(probe, environment, scheduler))
                }
                assertEquals(downAt + 80, scheduler.currentTime, "轨迹对照仍在90ms前")
                assertCanceled(probe)
                // A spring can carry its old velocity briefly. Compare with a still-held real-pointer
                // control, not an invented formula, same-frame rest, or forced animation target.
                val separation = if (kind == Kind.CONTENT) .004f else .002f
                assertTrue(canceledSignals.last() < heldSignals.last() - separation,
                    "$kind Cancel必须立即改向恢复，不能被90ms保持成对照轨迹：held=$heldSignals canceled=$canceledSignals")
                environment.release(outside)
                environment.advanceFrameBy()
                assertEquals(0, probe.clicks)
                assertTrue(probe.haptics.isEmpty())
                settle(environment)
                assertRest(rest, pixels(probe, environment, scheduler), "取消最终回到原态")
            }
        }

    @Test fun zeroDurationScaleReachesPressedAndRestTargetsWithoutTheNinetyMillisHold() =
        forEachWrapper { kind -> withProbe(kind, durationScale = 0f) { probe, environment, scheduler ->
            val rest = pixels(probe, environment, scheduler)
            val center = probe.hitBounds.center
            environment.press(center)
            repeat(2) { environment.advanceFrameBy() }
            val pressed = pixels(probe, environment, scheduler)
            if (kind == Kind.CONTENT) {
                assertEquals(.82f, pressed.centerRed / rest.centerRed, .006f, "零动画到内容透明度终值")
                assertEquals(rest.width, pressed.width, .6f, "开放行不缩放几何")
            } else assertEquals(rest.width * .96f, pressed.width, .6f, "零动画到缩放终值")
            assertEquals(0, probe.clicks)
            environment.release(center)
            assertEquals(1, probe.clicks)
            repeat(2) { environment.advanceFrameBy() }
            assertRest(rest, pixels(probe, environment, scheduler), "零动画不等 90ms 再恢复")
            assertEquals(1, probe.haptics.size)
            assertReleasedPair(probe, 0)
        } }

    @Test fun publicIndicationAlsoUsesTheRealZeroDurationScaleAndDoesNotWaitForMinimumVisibility() =
        withProbe(Kind.PUBLIC_INDICATION, durationScale = 0f) { probe, environment, scheduler ->
            val rest = pixels(probe, environment, scheduler)
            val center = probe.hitBounds.center
            environment.press(center)
            repeat(2) { environment.advanceFrameBy() }
            assertEquals(rest.width * .96f, pixels(probe, environment, scheduler).width, .6f)
            assertEquals(0, probe.clicks)
            environment.release(center)
            assertEquals(1, probe.clicks)
            repeat(2) { environment.advanceFrameBy() }
            assertRest(rest, pixels(probe, environment, scheduler), "公开节点同样不等90ms才恢复")
            assertEquals(1, probe.haptics.size)
            assertEquals(2, probe.presses.size)
            assertReleasedPair(probe, 0)
        }

    @Test fun sixContinuousQuickPhoneKeysDeliverEveryInputBeforeMotionFinishes() =
        withProbe(Kind.PHONE) { probe, environment, _ ->
            val center = probe.hitBounds.center
            repeat(6) { index ->
                environment.press(center)
                assertEquals(index, probe.clicks, "按下不输入")
                environment.advanceFrameBy(8)
                environment.release(center)
                assertEquals(index + 1, probe.clicks, "每次抬起即时输入，不靠动画锁或固定防抖")
                environment.advanceFrameBy(8)
            }
            assertEquals(6, probe.haptics.size)
            assertEquals(12, probe.presses.size)
            repeat(6) { assertReleasedPair(probe, it * 2) }
        }

    @Test fun realLongPressFiresOnceAndItsReleaseDoesNotAlsoClick() =
        withProbe(Kind.PHONE, longClick = true) { probe, environment, _ ->
            val center = probe.hitBounds.center
            environment.press(center)
            advanceByFrames(environment, probe.longPressTimeoutMillis + 32)
            assertEquals(1, probe.longClicks, "使用当前 ViewConfiguration 长按时长")
            assertEquals(0, probe.clicks)
            assertEquals(1, probe.haptics.size)
            environment.release(center)
            repeat(2) { environment.advanceFrameBy() }
            assertEquals(1, probe.longClicks)
            assertEquals(0, probe.clicks, "长按抬起不能追加普通点击")
            assertEquals(1, probe.haptics.size)
        }

    @Test fun anOlderQuickReleaseCannotEndANewerPressWhenItsMinimumVisibleDeadlineArrives() =
        forEachWrapper { kind -> withProbe(kind) { probe, environment, scheduler ->
            val rest = pixels(probe, environment, scheduler)
            val center = probe.hitBounds.center
            environment.press(center)
            environment.advanceFrameBy(8)
            environment.release(center)
            assertEquals(1, probe.clicks)
            environment.advanceFrameBy(16)
            environment.press(center)
            repeat(8) { environment.advanceFrameBy(16) } // Cross the first Press's 90ms deadline.
            assertEquals(3, probe.presses.size)
            val newer = assertIs<PressInteraction.Press>(probe.presses[2])
            assertVisiblePress(kind, rest, pixels(probe, environment, scheduler))
            assertEquals(1, probe.clicks)
            environment.release(center)
            assertEquals(2, probe.clicks)
            assertSame(newer, assertIs<PressInteraction.Release>(probe.presses[3]).press)
            assertEquals(2, probe.haptics.size)
        } }

    @Test fun anOlderCancellationCannotClearTheNextRealPointerPress() =
        forEachWrapper { kind -> withProbe(kind) { probe, environment, scheduler ->
            val rest = pixels(probe, environment, scheduler)
            val center = probe.hitBounds.center
            environment.press(center)
            advanceByFrames(environment, 160)
            environment.move(Offset(232f, 152f))
            environment.advanceFrameBy()
            environment.release(Offset(232f, 152f))
            environment.advanceFrameBy()
            assertCanceled(probe)
            environment.press(center)
            repeat(8) { environment.advanceFrameBy(16) }
            assertEquals(3, probe.presses.size)
            assertVisiblePress(kind, rest, pixels(probe, environment, scheduler))
            environment.release(center)
            assertEquals(1, probe.clicks)
            assertEquals(1, probe.haptics.size)
            assertReleasedPair(probe, 2)
        } }

    @Test fun repressDuringRecoveryIsContinuousAndReversesFromTheCurrentDrawing() =
        forEachWrapper { kind -> withProbe(kind) { probe, environment, scheduler ->
            val rest = pixels(probe, environment, scheduler)
            val center = probe.hitBounds.center
            environment.press(center)
            advanceByFrames(environment, 220)
            environment.advanceFrameBy()
            assertVisiblePress(kind, rest, pixels(probe, environment, scheduler))
            environment.release(center)
            // Establish recovery's first frame at the release timestamp, without adding hold/wait time.
            environment.advanceFrameBy(0)
            repeat(2) { environment.advanceFrameBy() }
            val recovering = pixels(probe, environment, scheduler)
            environment.press(center)
            val sameMoment = pixels(probe, environment, scheduler)
            assertEquals(recovering.width, sameMoment.width, .4f, "新 Press 不瞬跳回起点")
            assertEquals(recovering.centerRed, sameMoment.centerRed, .006f)
            repeat(4) { environment.advanceFrameBy() }
            val reversed = pixels(probe, environment, scheduler)
            assertTrue(signal(kind, rest, reversed) >= signal(kind, rest, recovering) - .002f,
                "重新按下须改向按压，不排队等旧恢复结束")
            assertEquals(1, probe.clicks)
            environment.release(center)
            assertEquals(2, probe.clicks)
            assertEquals(2, probe.haptics.size)
        } }

    @Test fun pressedDrawingNeverShrinksTheClickableHitRectangle() =
        forEachWrapper { kind -> withProbe(kind) { probe, environment, scheduler ->
            val hit = probe.hitBounds
            val rest = pixels(probe, environment, scheduler)
            environment.press(hit.center)
            advanceByFrames(environment, 220)
            environment.advanceFrameBy()
            assertVisiblePress(kind, rest, pixels(probe, environment, scheduler))
            assertEquals(hit, probe.hitBounds, "只改绘制，不缩小布局或命中区域")
            val edge = Offset(hit.right - 2f, hit.center.y)
            assertTrue(edge.x > hit.left && edge.x < hit.right && edge.y > hit.top && edge.y < hit.bottom)
            assertEquals(0f, redAt(environment, scheduler, edge), .006f,
                "前提：边缘在布局命中范围内，但实际绘制内容之外")
            environment.move(edge)
            environment.advanceFrameBy(8)
            assertEquals(0, probe.clicks, "移动不执行业务")
            assertTrue(probe.presses.none { it is PressInteraction.Cancel }, "布局内边缘移动不能按绘制边界取消")
            environment.release(edge)
            assertEquals(1, probe.clicks)
            assertEquals(1, probe.haptics.size)
            assertEquals(2, probe.presses.size, "边缘抬起仍恰好一次Press/Release，无Cancel")
            assertReleasedPair(probe, 0)
        } }

    @Test fun actualPhoneSurfaceTintDoesNotVanishWhileTheQuickPressIsStillHeldVisible() =
        withProbe(Kind.PHONE_SURFACE) { probe, environment, scheduler ->
            val rest = pixels(probe, environment, scheduler)
            val center = probe.hitBounds.center
            val downAt = scheduler.currentTime
            environment.press(center)
            environment.advanceFrameBy(8)
            environment.release(center)
            assertEquals(downAt + 8, scheduler.currentTime, "Release业务不等DurMicro淡入")
            assertEquals(1, probe.clicks)
            // §3.1/§5.1: DurMicro tint fades in over real frames, not the first 8ms frame.
            repeat(4) { environment.advanceFrameBy(8) }
            val fadingIn = pixels(probe, environment, scheduler)
            assertEquals(downAt + 40, scheduler.currentTime, "Release后32ms有界真实帧")
            assertTrue(fadingIn.centerRed < rest.centerRed - .015f,
                "前提：真实色层在DurMicro淡入的后续帧已经着色")
            repeat(6) { environment.advanceFrameBy(8) }
            val retained = pixels(probe, environment, scheduler)
            assertEquals(downAt + 88, scheduler.currentTime, "原88ms保持窗口不变")
            assertTrue(retained.centerRed < rest.centerRed - .015f,
                "同一可见态须保留实际 PhoneButton 色层，不允许 raw pressed 已 false 就瞬灭")
            assertTrue(retained.width < rest.width - 1f, "色层与缩放须属于同一最短可见周期")
            assertEquals(1, probe.clicks)
            assertEquals(1, probe.haptics.size)
            settle(environment)
            assertRest(rest, pixels(probe, environment, scheduler), "实体键结束后两层一起恢复")
        }

    @Test fun frameClockHoldExpiresAndRoundReleaseReboundsWithoutAdvancingTheCoroutineTimer() =
        withProbe(Kind.PHONE) { probe, environment, scheduler ->
            val rest = pixels(probe, environment, scheduler)
            val center = probe.hitBounds.center
            val unchangedCoroutineTime = scheduler.currentTime
            var frameTime = unchangedCoroutineTime
            fun renderedFrame(): Paint {
                frameTime += 16
                environment.scene.render(frameTime * 1_000_000L).close()
                scheduler.runCurrent()
                return pixelsAt(probe, environment, frameTime)
            }
            environment.press(center)
            repeat(12) { renderedFrame() }
            assertVisiblePress(Kind.PHONE, rest, pixelsAt(probe, environment, frameTime))
            assertEquals(unchangedCoroutineTime, scheduler.currentTime,
                "只推进实际渲染时钟，不能让协程delay替代可见帧时间")
            environment.release(center)
            assertEquals(1, probe.clicks)
            assertEquals(1, probe.haptics.size)
            assertReleasedPair(probe, 0)
            val releasedWidths = List(45) { renderedFrame().width }
            assertTrue(releasedWidths.max() > rest.width + .2f, "原Round弹簧应在真实帧内出现微过冲")
            assertTrue(releasedWidths.max() < rest.width * 1.015f, "保留克制的过冲上限")
            assertRest(rest, pixelsAt(probe, environment, frameTime), "帧时钟窗口结束后正常收敛")
            assertEquals(unchangedCoroutineTime, scheduler.currentTime)
        }

    @Test fun externalSolidIndicationTintsTheNativeSurfaceWithoutPaintingItsTransparentCorners() =
        onMotionTestThread { runTest {
            val source = MutableInteractionSource()
            val interactions = mutableListOf<PressInteraction>()
            var clicks = 0
            MotionTestEnvironment(testScheduler, height = 200) {
                LaunchedEffect(source) { source.interactions.collect {
                    if (it is PressInteraction) interactions += it
                } }
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    SoftVoWiFiTheme(false) {
                        Box(Modifier.fillMaxSize().background(Color.Black).padding(20.dp)) {
                            Button(
                                onClick = { clicks++ }, interactionSource = source, indication = null,
                                colors = ButtonDefaults.buttonColors(color = Color.White, contentColor = Color.Black),
                                insideMargin = PaddingValues(0.dp),
                                modifier = Modifier.size(160.dp, 80.dp).managerPressInputGate(true)
                                    .indication(source, rememberManagerPressIndication(ManagerPressStyle.Solid, true)),
                            ) { }
                        }
                    }
                }
            }.use { environment ->
                repeat(2) { environment.advanceFrameBy() }
                val center = Offset(100f, 60f)
                val corner = Offset(21f, 21f)
                assertEquals(1f, redAt(environment, testScheduler, center), .006f)
                assertEquals(0f, redAt(environment, testScheduler, corner), .006f,
                    "前提：真实native squircle在该角像素透明，不复制库形状")
                environment.press(center)
                advanceByFrames(environment, 200)
                assertIs<PressInteraction.Press>(interactions.single())
                assertTrue(redAt(environment, testScheduler, center) < .98f,
                    "外置Solid仍必须在完整native表面内部着色")
                assertEquals(0f, redAt(environment, testScheduler, corner), .006f,
                    "SrcAtop仅沿真实表面alpha着色，不能把透明角铺成矩形")
                environment.release(center)
                assertEquals(1, clicks)
                assertEquals(2, interactions.size)
                assertSame(interactions[0], assertIs<PressInteraction.Release>(interactions[1]).press)
                settle(environment)
                assertEquals(1f, redAt(environment, testScheduler, center), .006f)
                assertEquals(0f, redAt(environment, testScheduler, corner), .006f)
            }
        } }

    private fun assertQuickTapMinimumVisible(kind: Kind) = withProbe(kind) { probe, environment, scheduler ->
        val rest = pixels(probe, environment, scheduler)
        val hit = probe.hitBounds
        val downAt = scheduler.currentTime
        environment.press(hit.center)
        assertEquals(0, probe.clicks)
        environment.advanceFrameBy(8)
        environment.release(hit.center)
        assertEquals(downAt + 8, scheduler.currentTime, "快速点按前提不能被辅助暗中延长")
        assertEquals(1, probe.clicks, "抬起立即执行业务，不能等待 90ms")
        assertEquals(1, probe.haptics.size)
        assertReleasedPair(probe, 0)
        val samples = List(10) {
            environment.advanceFrameBy(8)
            pixels(probe, environment, scheduler)
        }
        assertEquals(downAt + 88, scheduler.currentTime, "末样本仍在 90ms 前")
        assertVisiblePress(kind, rest, samples.last())
        val tail = samples.takeLast(4).map { signal(kind, rest, it) }
        assertTrue(tail.zipWithNext().all { (earlier, later) -> later >= earlier - .0015f },
            "90ms 前仍在沉下而非提前回弹；实际绘制信号 $tail")
        assertEquals(hit, probe.hitBounds)
        settle(environment)
        assertRest(rest, pixels(probe, environment, scheduler), "最终恢复同一静止外观")
        assertEquals(1, probe.clicks)
    }

    private fun assertReleasedPair(probe: Probe, index: Int) {
        val press = assertIs<PressInteraction.Press>(probe.presses[index])
        assertSame(press, assertIs<PressInteraction.Release>(probe.presses[index + 1]).press)
    }

    private fun assertCanceled(probe: Probe) {
        assertEquals(2, probe.presses.size, "严格核对真实 Press/Cancel 子序列，不混入 Focus")
        val press = assertIs<PressInteraction.Press>(probe.presses[0])
        assertSame(press, assertIs<PressInteraction.Cancel>(probe.presses[1]).press)
        assertEquals(0, probe.clicks)
        assertEquals(0, probe.longClicks)
        assertTrue(probe.haptics.isEmpty(), "取消不能发出动作触感")
    }

    private fun assertVisiblePress(kind: Kind, rest: Paint, current: Paint) {
        val minimum = if (kind == Kind.CONTENT) .04f else .008f
        assertTrue(signal(kind, rest, current) > minimum,
            "$kind 必须有实际按压像素变化：rest=$rest current=$current")
    }

    private fun assertRest(rest: Paint, current: Paint, message: String) {
        assertEquals(rest.width, current.width, .6f, "$message：几何")
        assertEquals(rest.centerRed, current.centerRed, .006f, "$message：透明度/色层")
    }

    private fun signal(kind: Kind, rest: Paint, current: Paint): Float =
        if (kind == Kind.CONTENT) 1f - current.centerRed / rest.centerRed else 1f - current.width / rest.width

    private fun settle(environment: MotionTestEnvironment) {
        advanceByFrames(environment, 1000)
        environment.advanceFrameBy()
    }

    /** Preserve each existing total duration; render real 16ms frames instead of creating a late first frame. */
    private fun advanceByFrames(environment: MotionTestEnvironment, milliseconds: Long) {
        repeat((milliseconds / 16).toInt()) { environment.advanceFrameBy(16) }
        val remainder = milliseconds % 16
        if (remainder > 0) environment.advanceFrameBy(remainder)
    }

    private fun forEachWrapper(block: (Kind) -> Unit) = listOf(Kind.MANAGER, Kind.PHONE, Kind.CONTENT).forEach(block)

    private fun withProbe(
        kind: Kind, scrolling: Boolean = false, durationScale: Float = 1f, longClick: Boolean = false,
        block: (Probe, MotionTestEnvironment, TestCoroutineScheduler) -> Unit,
    ) = onMotionTestThread { runTest {
        val probe = Probe(kind, scrolling, longClick)
        MotionTestEnvironment(testScheduler, height = 200, durationScale = durationScale) { probe.Content() }.use { environment ->
            repeat(2) { environment.advanceFrameBy() }
            block(probe, environment, testScheduler)
        }
    } }

    private fun pixels(probe: Probe, environment: MotionTestEnvironment, scheduler: TestCoroutineScheduler): Paint {
        return pixelsAt(probe, environment, scheduler.currentTime)
    }

    private fun pixelsAt(probe: Probe, environment: MotionTestEnvironment, frameTimeMillis: Long): Paint {
        val center = probe.hitBounds.center
        assertTrue(center.x in 0f..239f && center.y in 0f..199f, "采样前提：命中区域中心仍可见")
        return environment.scene.render(frameTimeMillis * 1_000_000L).use { image ->
            Bitmap.makeFromImage(image).use { bitmap ->
                fun red(x: Int): Float = ((bitmap.getColor(x, center.y.toInt()) ushr 16) and 255) / 255f
                val middle = red(center.x.toInt())
                assertTrue(middle > .5f, "合成白色探针可见，不能将离屏/空画面当按压")
                // Integrate antialiased coverage; normalize alpha so an open row's dim is not mistaken for scale.
                Paint((0 until 240).sumOf { red(it).toDouble() }.toFloat() / middle, middle)
            }
        }
    }

    private fun redAt(environment: MotionTestEnvironment, scheduler: TestCoroutineScheduler, position: Offset): Float =
        environment.scene.render(scheduler.currentTime * 1_000_000L).use { image ->
            Bitmap.makeFromImage(image).use { bitmap ->
                ((bitmap.getColor(position.x.toInt(), position.y.toInt()) ushr 16) and 255) / 255f
            }
        }

    private data class Paint(val width: Float, val centerRed: Float)
    private enum class Kind { MANAGER, PHONE, CONTENT, PUBLIC_INDICATION, PHONE_SURFACE }
    private enum class Gate { ENABLED, BUSY, ROUTE }

    private class Probe(val kind: Kind, private val scrolling: Boolean, private val longClick: Boolean) {
        val source = MutableInteractionSource()
        val interactions = mutableListOf<Interaction>()
        val presses get() = interactions.filterIsInstance<PressInteraction>()
        val haptics = mutableListOf<HapticFeedbackType>()
        val scroll = ScrollState(0)
        var enabled by mutableStateOf(true)
        var busy by mutableStateOf(false)
        var active by mutableStateOf(true)
        var clicks = 0
        var longClicks = 0
        var hitBounds = Rect.Zero
        var longPressTimeoutMillis = 0L
        private val haptic = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
        }

        fun setGate(gate: Gate, allowed: Boolean) {
            when (gate) {
                Gate.ENABLED -> enabled = allowed
                Gate.BUSY -> busy = !allowed
                Gate.ROUTE -> active = allowed
            }
        }

        @Composable fun Content() {
            val configuration = LocalViewConfiguration.current
            SideEffect { longPressTimeoutMillis = configuration.longPressTimeoutMillis }
            LaunchedEffect(source) { source.interactions.collect { interactions += it } }
            CompositionLocalProvider(
                LocalDensity provides Density(1f), LocalAppRouteActive provides active,
                LocalHapticFeedback provides haptic,
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (kind == Kind.PHONE_SURFACE) Box(Modifier.padding(20.dp)) {
                        PhoneActionFeedback(AppearanceSettings.Default) { SoftVoWiFiTheme(false) {
                            PhoneButton("合成按压探针", { clicks++ }, modifier = measuredHit(), targetSize = 160.dp,
                                enabled = enabled, busy = busy, showLabel = false, content = {})
                        } }
                    } else if (scrolling) Column(
                        Modifier.fillMaxSize().verticalScroll(scroll, overscrollEffect = null).padding(horizontal = 20.dp),
                    ) {
                        Spacer(Modifier.height(20.dp))
                        Target()
                        Spacer(Modifier.height(600.dp))
                    } else Box(Modifier.padding(20.dp)) { Target() }
                }
            }
        }

        private fun measuredHit(): Modifier = Modifier.onGloballyPositioned { coordinates ->
            val origin = coordinates.positionInRoot()
            hitBounds = Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
        }

        @Composable private fun Target() {
            val actionable = enabled && !busy && active
            val feedback = managerActionFeedback(ManagerAction.ContextAction, actionable)
            val click = managerActionHandler(feedback, haptic, onAccepted = { clicks++ })
            val hit = if (longClick) Modifier.combinedClickable(
                enabled = actionable, interactionSource = source, indication = null, hapticFeedbackEnabled = false,
                onLongClick = { feedback.performHapticThenInvoke(haptic, { longClicks++ }) }, onClick = click,
            ) else Modifier.clickable(enabled = actionable, interactionSource = source, indication = null, onClick = click)
            Box(Modifier.size(200.dp, 120.dp).then(measuredHit()).managerPressInputGate(actionable).then(hit),
                contentAlignment = Alignment.Center) {
                val drawing = when (kind) {
                    Kind.MANAGER -> Modifier.managerPressScale(feedback, source)
                    Kind.PHONE -> Modifier.phonePressMotion(actionable, source)
                    Kind.CONTENT -> Modifier.communicationContentPress(feedback, source)
                    Kind.PUBLIC_INDICATION -> Modifier.indication(
                        source, rememberManagerPressIndication(ManagerPressStyle.CardRow, actionable),
                    )
                    Kind.PHONE_SURFACE -> error("PhoneButton owns its real interaction source")
                }
                Box(Modifier.size(160.dp, 80.dp).then(drawing).background(Color.White))
            }
        }
    }
}
