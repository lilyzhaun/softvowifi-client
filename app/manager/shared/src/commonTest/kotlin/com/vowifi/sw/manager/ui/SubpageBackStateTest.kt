package com.vowifi.sw.manager.ui

import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.NavigationEventState
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SubpageBackStateTest {
    @Test fun disabledSettingDoesNotExposeGestureStateToRenderer() = withStates { gesture, resting, _, _ ->
        assertSame(resting, subpageBackRenderState(false, gesture, resting))
    }

    @Test fun liveSettingToggleRestoresGestureOnlyWhenEnabled() = withStates { gesture, resting, _, _ ->
        val source = InMemoryAppearanceSettingsSource()
        source.setPredictiveBackRequested(true)
        assertSame(gesture, subpageBackRenderState(source.state.value.predictiveBackRequested, gesture, resting))
        source.setPredictiveBackRequested(false)
        assertSame(resting, subpageBackRenderState(source.state.value.predictiveBackRequested, gesture, resting))
    }

    @Test fun realGestureProgressIsHiddenWhenOffCancelDoesNotPopAndCommitPopsOnce() = withStates { gesture, resting, input, completed ->
        input.start()
        input.progress()
        assertIs<NavigationEventTransitionState.InProgress>(subpageBackRenderState(true, gesture, resting).transitionState)
        assertIs<NavigationEventTransitionState.Idle>(subpageBackRenderState(false, gesture, resting).transitionState)
        input.cancel()
        assertEquals(0, completed())
        input.start()
        input.progress()
        input.complete()
        assertEquals(1, completed())
        assertIs<NavigationEventTransitionState.Idle>(resting.transitionState)
    }

    private data object TestInfo : NavigationEventInfo()

    private fun withStates(check: (NavigationEventState<TestInfo>, NavigationEventState<TestInfo>, Input, () -> Int) -> Unit) {
        val dispatcher = NavigationEventDispatcher()
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = dispatcher
        }
        val input = Input()
        dispatcher.addInput(input)
        var completed = 0
        val recomposer = Recomposer(Dispatchers.Unconfined)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        lateinit var gesture: NavigationEventState<TestInfo>
        lateinit var resting: NavigationEventState<TestInfo>
        try {
            composition.setContent {
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                    gesture = rememberNavigationEventState(TestInfo)
                    resting = rememberNavigationEventState(TestInfo)
                    NavigationBackHandler(gesture, isBackEnabled = true, onBackCompleted = { completed += 1 })
                }
            }
            check(gesture, resting, input) { completed }
        } finally { composition.dispose(); recomposer.cancel(); dispatcher.dispose() }
    }

    private class Input : NavigationEventInput() {
        fun start() = dispatchOnBackStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = 0f))
        fun progress() = dispatchOnBackProgressed(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = 0.6f))
        fun cancel() = dispatchOnBackCancelled()
        fun complete() = dispatchOnBackCompleted()
    }
}
