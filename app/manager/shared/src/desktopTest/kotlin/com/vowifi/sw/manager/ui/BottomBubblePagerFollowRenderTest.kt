@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.vowifi.sw.manager.ui.navigation.AppNavigationState
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Read the rendered bubble, not the selected tab: a moving page must have no second animation. */
class BottomBubblePagerFollowRenderTest {
    @Test fun smallDragReversalAndFlingFollowTheViewportOnEveryFrame() = verify(LayoutDirection.Ltr)
    @Test fun rightToLeftUsesTheSameContinuousLogicalPagePosition() = verify(LayoutDirection.Rtl)
    @Test fun zeroMotionStillTracksTheFingerRatherThanJumpingToTheTargetTab() = verify(LayoutDirection.Ltr, 0f)
    @Test fun tabTapInterruptsFlingAndANewContentDragTakesBackThePosition() = verify(LayoutDirection.Ltr, takeTab = true)
    @Test fun bubbleDragTakesOverFromTheRenderedPositionWithoutJumpingBack() = verify(LayoutDirection.Ltr, takeBubble = true)
    @Test fun inFlightTabSpringThenContentThenBubbleKeepsTheRenderedPosition() = verify(LayoutDirection.Ltr, fromTab = true)

    private fun verify(direction: LayoutDirection, motionScale: Float = 1f, takeTab: Boolean = false,
        takeBubble: Boolean = false, fromTab: Boolean = false) = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher, timeout = 30.seconds) {
            var navigation by mutableStateOf(AppNavigationState.initial(AppRoute.Calls))
            var bubbleColor = 0
            MotionTestEnvironment(testScheduler, 420, 500, motionScale, dispatcher) {
                SoftVoWiFiTheme(false) {
                    val colors = LocalSoftVoWiFiColors.current
                    val primary = MiuixTheme.colorScheme.primary
                    SideEffect { bubbleColor = primary.copy(alpha = .15f).compositeOver(colors.surface).toArgb() }
                    CompositionLocalProvider(LocalDensity provides Density(1f), LocalLayoutDirection provides direction) {
                        AppShell(navigation, { navigation = it }, appearanceSettings = AppearanceSettings.Default.copy(
                            blurRequested = false, liquidGlassRequested = false), blurSupported = false) {
                            Box(Modifier.fillMaxSize().background(colors.canvas))
                        }
                    }
                }
            }.use { e ->
                fun frame(count: Int = 1) { repeat(count) { e.advanceFrameBy(16) } }
                fun tabs() = s4Nodes(e).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
                fun page(): Float = s4Nodes(e).single { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
                    .config[SemanticsProperties.HorizontalScrollAxisRange].value() / 420f
                fun bubbleCenter(): Float {
                    val bounds = tabs().map { it.boundsInRoot }
                    assertEquals(4, bounds.size)
                    // The pinned icons can reach the old +6 row. Sample the cap
                    // above their ink; rounded ends retain a wide, symmetric run.
                    val y = bounds.first().top.toInt() + 1
                    return e.scene.render(testScheduler.currentTime * 1_000_000L).use { image ->
                        Bitmap.makeFromImage(image).use { bitmap ->
                            val ink = (bounds.minOf { it.left }.toInt() until bounds.maxOf { it.right }.toInt())
                                .filter { s3RgbDistance(bitmap.getColor(it, y), bubbleColor) <= 2 }
                            assertTrue(ink.size >= 20, "precondition: the real opaque indicator must be visible")
                            (ink.first() + ink.last()) / 2f
                        }
                    }
                }
                val faults = mutableListOf<String>()
                fun sample(at: String) {
                    // Rendering may perform the pending zero-duration pager layout.
                    // Read its semantics AFTER that same image, not before rendering.
                    val actual = bubbleCenter()
                    val roots = tabs()
                    val calls = roots.single { s4Matches(it, AppDestination.CALLS.label) }.boundsInRoot.center.x
                    val messages = roots.single { s4Matches(it, AppDestination.MESSAGES.label) }.boundsInRoot.center.x
                    val position = page()
                    val expected = calls + (position - 1f) * (messages - calls)
                    if (abs(actual - expected) > 1.5f) faults += "$at page=$position actual=$actual expected=$expected"
                }
                frame(40)
                assertEquals(1f, page(), .001f)
                sample("rest")
                if (fromTab) {
                    val settings = tabs().single { s4Matches(it, AppDestination.SETTINGS.label) }.boundsInRoot.center
                    e.press(settings); frame(); e.release(settings); frame(3)
                    assertTrue(page() in 1.01f..2.99f, "precondition: original tab spring is still in flight")
                    e.press(Offset(350f, 220f)); frame()
                    for (to in listOf(322f, 290f, 250f)) {
                        e.move(Offset(to, 220f)); frame(); sample("tab to content $to")
                    }
                    e.release(Offset(250f, 220f)); frame()
                    val shown = bubbleCenter(); val y = tabs().first().boundsInRoot.center.y
                    e.press(Offset(shown, y)); frame()
                    assertEquals(shown, bubbleCenter(), 1.5f, "old in-flight spring cannot displace the new bubble DOWN")
                    e.move(Offset(shown - 34f, y)); frame(5)
                    e.release(Offset(shown - 34f, y)); frame(60)
                    sample("tab/content/bubble settled")
                    assertTrue(faults.isEmpty(), faults.joinToString("\n"))
                    return@use
                }
                val sign = if (direction == LayoutDirection.Ltr) -1f else 1f
                val start = if (sign < 0) 350f else 70f
                var x = start
                e.press(Offset(x, 220f)); frame()
                // Pager's target threshold is smaller than half a page; stay below
                // it for this precondition instead of assuming an 84px drag is "small".
                for (distance in listOf(24f, 44f, 54f)) {
                    x = start + sign * distance; e.move(Offset(x, 220f)); frame(); sample("small $distance")
                }
                assertTrue(page() in 1.01f..1.3f, "precondition: viewport moved before changing the target tab")
                assertEquals(AppRoute.Calls, navigation.currentRoot)
                for (distance in listOf(58f, 32f, 74f, 118f, 162f, 206f)) {
                    x = start + sign * distance; e.move(Offset(x, 220f)); frame(); sample("reverse/continue $distance")
                }
                e.release(Offset(x, 220f))
                if (takeTab || takeBubble) {
                    repeat(3) { frame(); sample("fling before takeover $it") }
                    assertTrue(page() in 1.1f..1.9f, "precondition: take ownership before the viewport settles")
                    val before = bubbleCenter()
                    if (takeBubble) {
                        val y = tabs().first().boundsInRoot.center.y
                        e.press(Offset(before, y)); frame()
                        assertEquals(before, bubbleCenter(), 1.5f, "a real bubble DOWN must not jump to its stale spring value")
                        e.move(Offset(before + 42f, y)); frame(5)
                        e.release(Offset(before + 42f, y)); frame(60)
                    } else {
                        val settings = tabs().single { s4Matches(it, AppDestination.SETTINGS.label) }.boundsInRoot.center
                        e.press(settings); frame(); e.release(settings); frame()
                        assertEquals(AppRoute.Settings, navigation.currentRoot, "real tab tap keeps immediate business selection")
                        assertTrue(bubbleCenter() >= before - 1.5f, "tap spring must start at the drawn position, not the old selected tab")
                        frame(3)
                        e.press(Offset(70f, 220f)); frame()
                        for (to in listOf(98f, 140f, 188f)) {
                            e.move(Offset(to, 220f)); frame(); sample("content interrupts tab spring $to")
                        }
                        e.release(Offset(188f, 220f)); frame(60)
                    }
                    sample("settled after takeover")
                    assertTrue(faults.isEmpty(), faults.joinToString("\n"))
                    return@use
                }
                repeat(12) { frame(); sample("release $it") }
                frame(50); sample("settled")
                assertTrue(faults.isEmpty(), "bubble must share the viewport position without a threshold or spring lag:\n${faults.joinToString("\n")}")
            }
        }
    }
}
