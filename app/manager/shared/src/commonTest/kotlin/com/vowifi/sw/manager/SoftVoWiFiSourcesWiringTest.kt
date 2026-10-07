package com.vowifi.sw.manager

import com.vowifi.sw.manager.data.CallSource
import com.vowifi.sw.manager.data.ConfigurationSource
import com.vowifi.sw.manager.data.EngineControlSource
import com.vowifi.sw.manager.data.EnvironmentDiagnosticsSource
import com.vowifi.sw.manager.data.MessageSource
import com.vowifi.sw.manager.data.ObserverLogSource
import com.vowifi.sw.manager.data.OperationalLogSource
import com.vowifi.sw.manager.data.SimControlSource
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogUiState
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.ui.AppDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

class SoftVoWiFiSourcesWiringTest {
    @Test
    fun homeAndSettingsRefreshDispatchExactlyOnce() {
        val fixture = WiringFixture()

        fixture.sources.refresh(AppDestination.HOME.refreshTargets)
        assertEquals(
            mapOf("observer" to 1, "engine" to 1, "sim" to 1, "configuration" to 1, "diagnostics" to 1),
            fixture.refreshes,
        )
        assertTrue(fixture.engineStarts.isEmpty())
        assertTrue(fixture.engineStops.isEmpty())
        assertTrue(fixture.simStarts.isEmpty())
        assertTrue(fixture.simStops.isEmpty())

        fixture.refreshes.clear()
        fixture.sources.refresh(AppDestination.SETTINGS.refreshTargets)
        assertEquals(mapOf("configuration" to 1, "sim" to 1, "diagnostics" to 1, "logs" to 1), fixture.refreshes)
    }

    @Test
    fun statusActionsUseTypedSimHandleWithoutCallingRawEngineActions() {
        val fixture = WiringFixture()
        val handle = SimHandle("sim-7")

        fixture.sources.startSim(handle)
        fixture.sources.stopSim(handle)

        assertEquals(listOf(handle), fixture.simStarts)
        assertEquals(listOf(handle), fixture.simStops)
        assertTrue(fixture.engineStarts.isEmpty())
        assertTrue(fixture.engineStops.isEmpty())
    }

    @Test
    fun configurationProvisionDoesNotStartEngine() {
        val fixture = WiringFixture()
        val handle = SimHandle("sim-2")

        fixture.sources.provisionSim(handle)

        assertEquals(listOf(handle), fixture.provisions)
        assertTrue(fixture.engineStarts.isEmpty())
        assertTrue(fixture.simStarts.isEmpty())
        assertTrue(fixture.engineStops.isEmpty())
    }

    @Test
    fun callsDialWiringDoesNotLeakNumberIntoUiState() {
        val fixture = WiringFixture()

        fixture.sources.calls.dial("+12345")

        assertEquals(listOf("+12345"), fixture.dials)
        assertEquals(CallUiState.Loading, fixture.sources.calls.state.value)
    }
}

private class WiringFixture {
    val refreshes = linkedMapOf<String, Int>()
    val simStarts = mutableListOf<SimHandle>()
    val simStops = mutableListOf<SimHandle>()
    val engineStarts = mutableListOf<String>()
    val engineStops = mutableListOf<String>()
    val provisions = mutableListOf<SimHandle>()
    val dials = mutableListOf<String>()

    private fun refreshed(name: String) {
        refreshes[name] = refreshes.getOrElse(name) { 0 } + 1
    }

    val sources = SoftVoWiFiSources(
        observer = object : ObserverLogSource {
            override val state = MutableStateFlow<RegistrationUiState>(RegistrationUiState.Loading)
            override fun refresh() = refreshed("observer")
        },
        engine = object : EngineControlSource {
            override val state = MutableStateFlow<EngineControlUiState>(EngineControlUiState.Loading)
            override fun refresh() = refreshed("engine")
            override fun start(iccid: String) { engineStarts += iccid }
            override fun stop(iccid: String) { engineStops += iccid }
        },
        simControl = object : SimControlSource {
            override val state = MutableStateFlow<SimControlUiState>(SimControlUiState.Loading)
            override fun refresh() = refreshed("sim")
            override fun start(handle: SimHandle) { simStarts += handle }
            override fun stop(handle: SimHandle) { simStops += handle }
        },
        configuration = object : ConfigurationSource {
            override val state = MutableStateFlow<ConfigurationUiState>(ConfigurationUiState.Loading)
            override fun refresh() = refreshed("configuration")
            override fun submit(instanceId: ConfigurationInstanceId, overrides: ConfigurationOverrides,
                onComplete: (com.vowifi.sw.manager.model.ConfigurationApplyOutcome) -> Unit) =
                com.vowifi.sw.manager.model.ConfigurationApplyAcceptance.Unavailable
            override fun provision(handle: SimHandle) { provisions += handle }
        },
        diagnostics = object : EnvironmentDiagnosticsSource {
            override val state = MutableStateFlow<EnvironmentDiagnosticsUiState>(EnvironmentDiagnosticsUiState.Loading)
            override fun refresh() = refreshed("diagnostics")
        },
        logs = object : OperationalLogSource {
            override val state = MutableStateFlow<OperationalLogUiState>(OperationalLogUiState.Loading)
            override fun refresh() = refreshed("logs")
            override fun selectFile(fileId: String) = Unit
            override fun setKindFilter(kind: OperationalLogKind?) = Unit
            override fun setLevelFilter(level: OperationalLogLevel?) = Unit
            override fun setInstanceFilter(displayLabel: String?) = Unit
        },
        messages = object : MessageSource {
            override val state = MutableStateFlow<MessageUiState>(MessageUiState.Loading)
            override fun refresh() = refreshed("messages")
        },
        calls = object : CallSource {
            override val state = MutableStateFlow<CallUiState>(CallUiState.Loading)
            override val history = MutableStateFlow<CallHistoryState>(CallHistoryState.Empty)
            override fun refresh() = refreshed("calls")
            override fun answer() = Unit
            override fun reject() = Unit
            override fun hangup() = Unit
            override fun cancel() = Unit
            override fun act(expected: com.vowifi.sw.manager.model.CallBinding, action: com.vowifi.sw.manager.model.CallAction) = Unit
            override fun sendDtmf(expected: com.vowifi.sw.manager.model.CallBinding, digit: Char) = Unit
            override fun dial(number: String) { dials += number }
            override fun dial(expected: com.vowifi.sw.manager.model.CallBinding, number: String) { dials += number }
            override fun clearHistory(expected: CallHistoryState.Ready) = Unit
            override fun deleteHistory(expected: CallHistoryState.Ready, ids: Set<com.vowifi.sw.manager.model.CallHistoryRecordId>) = error("wiring refresh test must not delete")
        },
    )
}
