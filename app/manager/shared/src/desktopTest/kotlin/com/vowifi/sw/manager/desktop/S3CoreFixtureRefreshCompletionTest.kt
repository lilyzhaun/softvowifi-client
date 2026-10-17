package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class S3CoreFixtureRefreshCompletionTest {
    @Test fun synchronousEngineAndSimReadTheirCurrentBackendWithoutResettingControls() = runBlocking {
        val engine = FixtureEngineControlSource("data")
        val sim = FixtureSimControlSource("matched-running")
        val owner = (engine.state.value as EngineControlUiState.Ready).instances.single().iccid
        val handle = (sim.state.value as SimControlUiState.Ready).instances.single().handle
        engine.stop(owner)
        sim.stop(handle)
        val engineStopped = engine.state.value
        val simStopped = sim.state.value
        assertEquals(RefreshResult.Completed, engine.refreshAndAwait())
        assertEquals(RefreshResult.Completed, sim.refreshAndAwait())
        assertEquals(engineStopped, engine.state.value)
        assertEquals(simStopped, sim.state.value)
        engine.close(); sim.close()
        assertEquals(RefreshResult.Unsupported, engine.refreshAndAwait())
        assertEquals(RefreshResult.Unsupported, sim.refreshAndAwait())
    }

    @Test fun synchronousObserverParsesAndConfigurationPublishesBeforeReturning() = runBlocking {
        val observer = FixtureObserverLogSource("loading")
        val configuration = FixtureConfigurationSource("loading")
        assertEquals(RefreshResult.Completed, observer.refreshAndAwait())
        assertIs<RegistrationUiState.Data>(observer.state.value)
        assertEquals(RefreshResult.Completed, configuration.refreshAndAwait())
        assertIs<ConfigurationUiState.Ready>(configuration.state.value)
        assertEquals(RefreshResult.Completed, configuration.refreshIdentitiesAndAwait())
        observer.close(); configuration.close()
        assertEquals(RefreshResult.Unsupported, observer.refreshAndAwait())
        assertEquals(RefreshResult.Unsupported, configuration.refreshIdentitiesAndAwait())
    }
}
