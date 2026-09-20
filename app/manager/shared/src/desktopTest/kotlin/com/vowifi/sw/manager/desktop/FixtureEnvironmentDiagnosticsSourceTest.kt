package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FixtureEnvironmentDiagnosticsSourceTest {
    @Test
    fun exposesEveryDiagnosticsState() {
        assertIs<EnvironmentDiagnosticsUiState.Loading>(
            FixtureEnvironmentDiagnosticsSource("loading").state.value,
        )
        assertEquals(
            DiagnosticCheckStatus.PASS,
            assertIs<EnvironmentDiagnosticsUiState.Ready>(
                FixtureEnvironmentDiagnosticsSource("ready").state.value,
            ).severity,
        )
        assertEquals(
            DiagnosticCheckStatus.WARN,
            assertIs<EnvironmentDiagnosticsUiState.Ready>(
                FixtureEnvironmentDiagnosticsSource("warning").state.value,
            ).severity,
        )
        assertIs<EnvironmentDiagnosticsUiState.Failure>(
            FixtureEnvironmentDiagnosticsSource("failure").state.value,
        )
        assertIs<EnvironmentDiagnosticsUiState.PermissionDenied>(
            FixtureEnvironmentDiagnosticsSource("permission").state.value,
        )
        assertIs<EnvironmentDiagnosticsUiState.Unavailable>(
            FixtureEnvironmentDiagnosticsSource("unavailable").state.value,
        )
    }

    @Test
    fun refreshRecoversToReady() {
        listOf("loading", "warning", "failure", "permission", "unavailable").forEach { mode ->
            val source = FixtureEnvironmentDiagnosticsSource(mode)
            source.refresh()
            assertEquals(
                DiagnosticCheckStatus.PASS,
                assertIs<EnvironmentDiagnosticsUiState.Ready>(source.state.value).severity,
            )
        }
    }
}
