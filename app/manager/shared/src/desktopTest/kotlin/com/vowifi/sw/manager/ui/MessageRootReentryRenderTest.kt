@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.refresh
import com.vowifi.sw.manager.data.FixtureMessageSource
import com.vowifi.sw.manager.data.MessageFixtures
import com.vowifi.sw.manager.data.MessageSource
import com.vowifi.sw.manager.desktop.*
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Real root navigation must reuse the process-owned source, not invoke a full reload. */
class MessageRootReentryRenderTest {
    @Test fun normalTabsDoNotReloadMessagesOnEveryReentry() = verify(false)
    @Test fun floatingTabsDoNotReloadMessagesOnEveryReentry() = verify(true)

    private fun verify(floating: Boolean) = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher, timeout = 45.seconds) {
            val fixture = FixtureMessageSource("loading")
            assertIs<MessageUiState.Loading>(fixture.state.value)
            val messages = CountingMessageSource(fixture)
            assertEquals(1, messages.refreshCalls, "source initialization must still load once")
            val lifecycle = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
                    currentState = Lifecycle.State.RESUMED
                }
            }
            val sources = SoftVoWiFiSources(
                FixtureObserverLogSource("empty"), FixtureEngineControlSource("engine-error"),
                FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"),
                FixtureEnvironmentDiagnosticsSource("empty"), FixtureOperationalLogSource("empty"),
                messages, FixtureCallSource("idle"),
            )
            try {
                MotionTestEnvironment(testScheduler, 420, 900, dispatcher = dispatcher) {
                    CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                        SoftVoWiFiApp(sources, {}, false, "test", initialDestination = AppDestination.MESSAGES,
                            appearanceSource = InMemoryAppearanceSettingsSource(AppearanceSettings.Default.copy(
                                floatingNavigation = floating, blurRequested = false, liquidGlassRequested = false)))
                    }
                }.use { e ->
                    fun frames(count: Int = 60) { repeat(count) { e.advanceFrameBy(16) } }
                    fun nodes(): List<SemanticsNode> = e.scene.semanticsOwners.flatMap { owner ->
                        fun descend(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descend)
                        descend(owner.rootSemanticsNode)
                    }
                    fun tabs() = nodes().filter {
                        it.config.contains(SemanticsProperties.Selected) && it.boundsInRoot.width > 0f &&
                            it.boundsInRoot.height > 0f && it.boundsInRoot.center.x in 0f..420f &&
                            it.boundsInRoot.top > 740f
                    }.sortedBy { it.boundsInRoot.left }
                    fun assertPage(index: Int) {
                        val bar = tabs(); assertEquals(4, bar.size)
                        assertEquals(listOf(index), bar.mapIndexedNotNull { i, n ->
                            i.takeIf { n.config[SemanticsProperties.Selected] }
                        })
                        val range = nodes().first { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
                            .config[SemanticsProperties.HorizontalScrollAxisRange]
                        assertEquals(index.toFloat(), range.value() / 420f, .01f)
                    }
                    fun visible(text: String) = nodes().any { n ->
                        n.boundsInRoot.width > 0f && n.boundsInRoot.height > 0f &&
                            n.boundsInRoot.center.x in 0f..420f && n.boundsInRoot.center.y in 0f..740f &&
                            (n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text in it.text } ||
                                n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { text in it })
                    }
                    fun tap(index: Int) {
                        val bar = tabs(); assertEquals(4, bar.size)
                        val hit = bar[index].boundsInRoot.center
                        e.press(hit); e.advanceFrameBy(16); e.release(hit); frames()
                        assertPage(index)
                    }
                    frames(); assertPage(2)
                    assertIs<MessageUiState.Ready>(messages.state.value)
                    val preview = MessageFixtures.ready().records.maxBy { it.timestampMs }.preview
                    assertTrue(visible(preview), "first screen must display initialized history")
                    val firstVisitReads = messages.refreshCalls
                    val reentryReads = mutableListOf<Int>()
                    repeat(3) {
                        for (index in listOf(1, 2, 3, 2)) {
                            tap(index)
                            if (index == 2) {
                                reentryReads += messages.refreshCalls
                                assertTrue(visible(preview), "reentry must retain history")
                            }
                        }
                    }
                    val beforeExplicit = messages.refreshCalls
                    sources.refresh(setOf(RefreshTarget.MESSAGES)); frames()
                    assertEquals(beforeExplicit + 1, messages.refreshCalls)
                    assertTrue(visible(preview), "explicit refresh must still call the real source")
                    val fresh = MessageRecord.Incoming("reentry-event", "+155502", "新事件测试预览",
                        1_780_000_001_000L, isRead = true)
                    messages.publish(MessageFixtures.ready().copy(records = listOf(fresh))); frames()
                    assertEquals(beforeExplicit + 1, messages.refreshCalls, "state delivery must not reload the whole source")
                    assertTrue(visible(fresh.preview), "avoid reads without disconnecting StateFlow updates")
                    assertEquals(List(6) { firstVisitReads }, reentryReads,
                        "Calls→Messages→Settings→Messages must not trigger repeated full refreshes")
                    assertEquals(1, firstVisitReads, "opening the root must reuse the initialized source")
                }
            } finally {
                lifecycle.lifecycle.currentState = Lifecycle.State.DESTROYED
                messages.close()
            }
        }
    }

    private class CountingMessageSource(private val fixture: FixtureMessageSource) : MessageSource by fixture {
        private val published = MutableStateFlow<MessageUiState>(MessageUiState.Loading)
        override val state: StateFlow<MessageUiState> = published
        var refreshCalls = 0
            private set
        init { refresh() }
        override fun refresh() {
            refreshCalls++
            fixture.refresh()
            published.value = fixture.state.value
        }
        fun publish(next: MessageUiState) { published.value = next }
    }
}
