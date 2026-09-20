@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class EngineAutoRefreshTest {
    @Test
    fun onlyTransitionalOrRunningReadyStatesNeedAutoRefresh() {
        val inactive = listOf(
            EngineControlUiState.Loading,
            EngineControlUiState.Failure("synthetic failure"),
            EngineControlUiState.PermissionDenied("synthetic denial"),
            ready(),
            ready(EngineLifecycle.STOPPED),
            ready(EngineLifecycle.CONFIG_MISSING),
            ready(EngineLifecycle.STOPPED, EngineLifecycle.CONFIG_MISSING),
        )
        val active = listOf(
            ready(EngineLifecycle.STARTING),
            ready(EngineLifecycle.RUNNING),
            ready(EngineLifecycle.STOPPING),
            ready(EngineLifecycle.STOPPED, EngineLifecycle.RUNNING),
        )

        inactive.forEach { state -> assertFalse(engineStateNeedsAutoRefresh(state), state.toString()) }
        active.forEach { state -> assertTrue(engineStateNeedsAutoRefresh(state), state.toString()) }
    }

    @Test
    fun intervalConstantIsThreeSeconds() {
        assertEquals(3_000L, ENGINE_AUTO_REFRESH_INTERVAL_MILLIS)
    }

    @Test
    fun runningRefreshesDelayFirstOncePerTick() = runTest {
        val state = MutableStateFlow(ready(EngineLifecycle.RUNNING))
        var refreshCalls = 0
        val job = launchEngineAutoRefresh(state, TEST_INTERVAL_MILLIS) { refreshCalls += 1 }

        runCurrent()
        assertEquals(0, refreshCalls)
        advanceTimeBy(TEST_INTERVAL_MILLIS - 1)
        runCurrent()
        assertEquals(0, refreshCalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, refreshCalls)
        advanceTimeBy(TEST_INTERVAL_MILLIS)
        runCurrent()
        assertEquals(2, refreshCalls)
        job.cancel()
    }

    @Test
    fun stoppedDoesNotRefreshOverFiveSeconds() = runTest {
        val state = MutableStateFlow(ready(EngineLifecycle.STOPPED))
        var refreshCalls = 0
        val job = launchEngineAutoRefresh(state, TEST_INTERVAL_MILLIS) { refreshCalls += 1 }

        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(0, refreshCalls)
        job.cancel()
    }

    @Test
    fun stoppingRefreshStateAfterFirstTickPreventsLaterCalls() = runTest {
        val state = MutableStateFlow(ready(EngineLifecycle.RUNNING))
        var refreshCalls = 0
        val job = launchEngineAutoRefresh(state, TEST_INTERVAL_MILLIS) { refreshCalls += 1 }

        runCurrent()
        advanceTimeBy(TEST_INTERVAL_MILLIS)
        runCurrent()
        assertEquals(1, refreshCalls)

        state.value = ready(EngineLifecycle.STOPPED)
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(1, refreshCalls)
        job.cancel()
    }

    @Test
    fun cancellingReturnedJobPreventsLaterCalls() = runTest {
        val state = MutableStateFlow(ready(EngineLifecycle.RUNNING))
        var refreshCalls = 0
        val job = launchEngineAutoRefresh(state, TEST_INTERVAL_MILLIS) { refreshCalls += 1 }

        runCurrent()
        advanceTimeBy(TEST_INTERVAL_MILLIS)
        runCurrent()
        assertEquals(1, refreshCalls)

        job.cancel()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(1, refreshCalls)
    }

    @Test
    fun cancellingParentJobPreventsLaterCalls() = runTest {
        val state = MutableStateFlow(ready(EngineLifecycle.RUNNING))
        val parentJob = Job()
        val parentScope = CoroutineScope(coroutineContext + parentJob)
        var refreshCalls = 0
        parentScope.launchEngineAutoRefresh(state, TEST_INTERVAL_MILLIS) { refreshCalls += 1 }

        runCurrent()
        advanceTimeBy(TEST_INTERVAL_MILLIS)
        runCurrent()
        assertEquals(1, refreshCalls)

        parentJob.cancel()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(1, refreshCalls)
    }
}

private fun ready(vararg lifecycles: EngineLifecycle): EngineControlUiState.Ready =
    EngineControlUiState.Ready(
        lifecycles.map { lifecycle -> EngineInstanceState(iccid = "1", lifecycle = lifecycle) },
    )

private const val TEST_INTERVAL_MILLIS = 1_000L
