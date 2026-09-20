package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.model.RegistrationUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FixtureObserverLogSourceTest {
    @Test
    fun exposesEveryQaState() {
        assertIs<RegistrationUiState.Loading>(FixtureObserverLogSource("loading").state.value)
        assertIs<RegistrationUiState.Empty>(FixtureObserverLogSource("empty").state.value)
        assertIs<RegistrationUiState.ParseError>(FixtureObserverLogSource("error").state.value)
        assertIs<RegistrationUiState.ReadError>(FixtureObserverLogSource("read-error").state.value)
        val warning = assertIs<RegistrationUiState.Data>(FixtureObserverLogSource("warning").state.value)
        assertEquals(1, warning.snapshot.issues.size)
        assertIs<RegistrationUiState.Data>(FixtureObserverLogSource("registering").state.value)
        assertIs<RegistrationUiState.Data>(FixtureObserverLogSource("unknown").state.value)
        assertIs<RegistrationUiState.Data>(FixtureObserverLogSource("data").state.value)
    }

    @Test
    fun refreshRecoversFromError() {
        val source = FixtureObserverLogSource("error")
        source.refresh()
        assertIs<RegistrationUiState.Data>(source.state.value)
    }
}
