package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FixtureEngineControlSourceTest {
    @Test
    fun startAndStopReplaceStateInPlace() {
        val source = FixtureEngineControlSource("stopped")
        val initial = assertIs<EngineControlUiState.Ready>(source.state.value)
        val iccid = initial.instances.single().iccid

        source.start(iccid)
        assertEquals(
            EngineLifecycle.RUNNING,
            assertIs<EngineControlUiState.Ready>(source.state.value).instances.single().lifecycle,
        )

        source.stop(iccid)
        assertEquals(
            EngineLifecycle.STOPPED,
            assertIs<EngineControlUiState.Ready>(source.state.value).instances.single().lifecycle,
        )
    }

    @Test
    fun exposesPermissionAndEmptyStates() {
        assertIs<EngineControlUiState.PermissionDenied>(FixtureEngineControlSource("permission").state.value)
        assertEquals(
            emptyList(),
            assertIs<EngineControlUiState.Ready>(FixtureEngineControlSource("no-config").state.value).instances,
        )
    }

    @Test
    fun exposesBusyState() {
        assertEquals(
            EngineLifecycle.STARTING,
            assertIs<EngineControlUiState.Ready>(FixtureEngineControlSource("starting").state.value)
                .instances.single().lifecycle,
        )
    }
}
