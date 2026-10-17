@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.FixtureMessageSource
import com.vowifi.sw.manager.desktop.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.math.roundToInt
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Repeated real drags must keep working without a navigation-bar tap between gestures. */
class RootHorizontalSwipeRenderTest {
    @Test fun repeatedCallsMessagesSettingsDragsRemainUsableWithNormalNavigation() = verify(false)
    @Test fun repeatedCallsMessagesSettingsDragsRemainUsableWithFloatingNavigation() = verify(true)
    @Test fun rapidRealTabTapsDoNotLoseTheLatestSelectionWithNormalNavigation() = verify(false, rapid = true)
    @Test fun rapidRealTabTapsDoNotLoseTheLatestSelectionWithFloatingNavigation() = verify(true, rapid = true)
    @Test fun consecutiveContentDragsCanReverseBeforeSettlingWithNormalNavigation() = verify(false, rapidDrags = true)
    @Test fun consecutiveContentDragsCanReverseBeforeSettlingWithFloatingNavigation() = verify(true, rapidDrags = true)

    @Test fun interruptedSelectionReleasesTheOldTargetForTheNextRapidTap() {
        val coordinator = AppRootPagerCoordinator(1)
        coordinator.select(2, RootSelectionMode.ANIMATE)
        coordinator.observePager(2, 1, true)
        coordinator.select(3, RootSelectionMode.ANIMATE)
        coordinator.observePager(2, 1, false)
        assertEquals(1, coordinator.selectedPage, "an interrupted animation must adopt the actual viewport")
        assertFalse(coordinator.hasProgrammaticTarget)
        coordinator.select(0, RootSelectionMode.ANIMATE)
        assertEquals(0, coordinator.selectedPage)
        assertTrue(coordinator.hasProgrammaticTarget)
    }

    private fun verify(floating: Boolean, rapid: Boolean = false, rapidDrags: Boolean = false) = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher, timeout = 45.seconds) {
            val lifecycle = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
            val sources = SoftVoWiFiSources(
                FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
                FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"),
                FixtureEnvironmentDiagnosticsSource("empty"), FixtureOperationalLogSource("empty"),
                FixtureMessageSource("ready"), FixtureCallSource("idle"),
            )
            MotionTestEnvironment(testScheduler, 420, 900, dispatcher = dispatcher) {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                    SoftVoWiFiApp(sources, {}, false, "test",
                        initialDestination = AppDestination.CALLS,
                        appearanceSource = InMemoryAppearanceSettingsSource(AppearanceSettings.Default.copy(
                            floatingNavigation = floating, blurRequested = false, liquidGlassRequested = false)))
                }
            }.use { e ->
                fun frame(count: Int = 1) { repeat(count) { e.advanceFrameBy(16) } }
                fun nodes(): List<SemanticsNode> = e.scene.semanticsOwners.flatMap { owner ->
                    fun descend(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descend)
                    descend(owner.rootSemanticsNode)
                }
                fun tabs() = nodes().filter {
                    it.config.contains(SemanticsProperties.Selected) && it.boundsInRoot.width > 0f &&
                        it.boundsInRoot.height > 0f && it.boundsInRoot.center.x in 0f..420f && it.boundsInRoot.top > 740f
                }
                fun page(): Float {
                    val range = nodes().first { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
                        .config[SemanticsProperties.HorizontalScrollAxisRange]
                    return range.value() / 420f
                }
                fun assertPage(expected: Int, at: String) {
                    assertEquals(expected.toFloat(), page(), .01f, "$at: real pager must settle on the dragged page")
                    val selected = tabs().mapIndexedNotNull { i, n -> i.takeIf { n.config[SemanticsProperties.Selected] } }
                    assertEquals(listOf(expected), selected, "$at: tab selection must match the actual viewport")
                }
                fun swipe(left: Boolean, y: Float) {
                    val from = if (left) 350f else 70f
                    val to = if (left) 70f else 350f
                    e.press(Offset(from, y)); frame()
                    repeat(14) { i ->
                        e.move(Offset(from + (to - from) * (i + 1) / 14f, y)); frame()
                    }
                    e.release(Offset(to, y)); frame(60)
                }
                frame(40); assertPage(1, "initial Calls")
                if (rapid) {
                    // 32ms between real touch clicks: never wait for a page spring to finish.
                    val sequence = listOf(2, 3, 1, 0, 2, 3, 0, 1, 3, 2, 0, 1)
                    sequence.forEachIndexed { index, target ->
                        val hit = tabs()[target].boundsInRoot.center
                        e.press(hit); frame(); e.release(hit); frame()
                        val accepted = tabs().mapIndexedNotNull { i, n ->
                            i.takeIf { n.config[SemanticsProperties.Selected] }
                        }
                        assertEquals(listOf(target), accepted, "real rapid tap ${index + 1}: latest input must win while the old page is moving")
                    }
                    frame(60)
                    assertPage(sequence.last(), "after uninterrupted rapid real taps")
                    return@use
                }
                if (rapidDrags) {
                    listOf(true, true, false, false, true, false).forEachIndexed { index, left ->
                        val from = if (left) 350f else 70f
                        val to = if (left) 70f else 350f
                        val before = page()
                        e.press(Offset(from, 390f)); frame()
                        repeat(10) { step ->
                            e.move(Offset(from + (to - from) * (step + 1) / 10f, 390f)); frame()
                        }
                        e.release(Offset(to, 390f)); frame()
                        val after = page()
                        assertTrue(if (left) after > before + .1f else after < before - .1f,
                            "drag ${index + 1} must interrupt and move immediately: before=$before, after=$after")
                    }
                    frame(60)
                    // Fling destinations depend on the interrupted fractional viewport
                    // and velocity. Every drag moved immediately; now require a fully
                    // settled integer viewport and its matching single selected tab.
                    val settled = page().roundToInt()
                    assertTrue(settled in 0..3)
                    assertPage(settled, "after six uninterrupted reversals")
                    return@use
                }
                val attempts = mutableListOf<String>()
                val faults = mutableListOf<String>()
                // One pass across roots, reversal, then repeat: no taps to reset a stuck drag owner.
                val expected = listOf(2, 3, 2, 1, 2, 3, 2, 1)
                expected.forEachIndexed { index, target ->
                    val before = page()
                    swipe(target > before, if (index < 4) 390f else 610f)
                    val after = page()
                    attempts += "$before->$target:$after"
                    try { assertPage(target, "drag ${index + 1}") }
                    catch (failure: AssertionError) {
                        if (faults.isEmpty()) println("ROOT_SWIPE_VISIBLE_ACTIONS|" + nodes().filter {
                            it.boundsInRoot.width > 0f && it.boundsInRoot.center.x in 0f..420f &&
                                it.boundsInRoot.center.y in 0f..740f && it.config.contains(SemanticsActions.OnClick)
                        }.map { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } to it.boundsInRoot })
                        faults += requireNotNull(failure.message)
                    }
                }
                // This is a separate fallback assertion, never a substitute for the drags above.
                val calls = tabs().getOrNull(1)
                if (calls != null && calls.config.contains(SemanticsActions.OnClick)) {
                    assertTrue(requireNotNull(calls.config[SemanticsActions.OnClick].action).invoke())
                    frame(60); assertPage(1, "navigation bar still works")
                }
                assertTrue(faults.isEmpty(), "real gesture sequence=$attempts\n" + faults.joinToString("\n"))
            }
        }
    }
}
