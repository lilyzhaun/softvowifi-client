package com.vowifi.sw.manager

import com.vowifi.sw.manager.data.CallSource
import com.vowifi.sw.manager.data.ConfigurationSource
import com.vowifi.sw.manager.data.EngineControlSource
import com.vowifi.sw.manager.data.EnvironmentDiagnosticsSource
import com.vowifi.sw.manager.data.MessageSource
import com.vowifi.sw.manager.data.ObserverLogSource
import com.vowifi.sw.manager.data.OperationalLogSource
import com.vowifi.sw.manager.data.RefreshResult
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SoftVoWiFiSourcesWiringTest {
    @Test
    fun homeUserRefreshWaitsForAllFiveAndProjectsSimAfterEngineCompletion() = runTest {
        val fixture = WiringFixture()
        val names = listOf("observer", "engine", "sim", "configuration", "diagnostics")
        names.forEach { fixture.waits[it] = CompletableDeferred() }
        val refresh = async { fixture.sources.refreshHomeAndAwait() }
        try {
            runCurrent()
            assertEquals(setOf("observer", "engine", "configuration", "diagnostics"), fixture.awaited.toSet(),
                "R5/C15：必须启动真实完成接口；SIM投影须等待engine")
            assertFalse(refresh.isCompleted)
            fixture.waits.getValue("engine").complete(RefreshResult.Completed)
            runCurrent()
            assertEquals(names.toSet(), fixture.awaited.toSet())
            assertEquals(5, fixture.awaited.size)
            listOf("observer", "sim", "diagnostics").forEach {
                fixture.waits.getValue(it).complete(RefreshResult.Completed)
            }
            runCurrent()
            assertFalse(refresh.isCompleted, "配置尚未读完，不能以其他四源结束冒充首页完成")
            fixture.waits.getValue("configuration").complete(RefreshResult.Completed)
            runCurrent()
            assertEquals(RefreshResult.Completed, refresh.await())
            assertEquals(0, fixture.identityRefreshes, "首页普通刷新不能重采SIM身份")
            assertTrue(fixture.refreshes.isEmpty(), "不能调用Unit入口后假定完成")
        } finally {
            refresh.cancel()
            fixture.waits.values.forEach { it.complete(RefreshResult.Cancelled) }
        }
    }

    @Test
    fun homeFailureStillWaitsForTheOtherAcceptedReadsWithoutReplayingActions() = runTest {
        val fixture = WiringFixture()
        val failure = RefreshResult.Failed("状态读取失败")
        fixture.waits["engine"] = CompletableDeferred(failure)
        fixture.waits["configuration"] = CompletableDeferred()
        val refresh = async { fixture.sources.refreshHomeAndAwait() }
        try {
            runCurrent()
            assertEquals(setOf("observer", "engine", "sim", "configuration", "diagnostics"), fixture.awaited.toSet())
            assertFalse(refresh.isCompleted, "已发出的配置读仍须等待真实结束")
            fixture.waits.getValue("configuration").complete(RefreshResult.Completed)
            runCurrent()
            assertEquals(failure, refresh.await())
            assertEquals(0, fixture.identityRefreshes)
            assertTrue(fixture.engineStarts.isEmpty() && fixture.engineStops.isEmpty())
            assertTrue(fixture.simStarts.isEmpty() && fixture.simStops.isEmpty() && fixture.provisions.isEmpty())
        } finally {
            refresh.cancel()
            fixture.waits.values.forEach { it.complete(RefreshResult.Cancelled) }
        }
    }

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
    val awaited = mutableListOf<String>()
    val waits = mutableMapOf<String, CompletableDeferred<RefreshResult>>()
    var identityRefreshes = 0
    val simStarts = mutableListOf<SimHandle>()
    val simStops = mutableListOf<SimHandle>()
    val engineStarts = mutableListOf<String>()
    val engineStops = mutableListOf<String>()
    val provisions = mutableListOf<SimHandle>()
    val dials = mutableListOf<String>()

    private fun refreshed(name: String) {
        refreshes[name] = refreshes.getOrElse(name) { 0 } + 1
    }

    private suspend fun awaitRead(name: String): RefreshResult {
        awaited += name
        return waits[name]?.await() ?: RefreshResult.Completed
    }

    val sources = SoftVoWiFiSources(
        observer = object : ObserverLogSource {
            override val state = MutableStateFlow<RegistrationUiState>(RegistrationUiState.Loading)
            override fun refresh() = refreshed("observer")
            override suspend fun refreshAndAwait() = awaitRead("observer")
        },
        engine = object : EngineControlSource {
            override val state = MutableStateFlow<EngineControlUiState>(EngineControlUiState.Loading)
            override fun refresh() = refreshed("engine")
            override suspend fun refreshAndAwait() = awaitRead("engine")
            override fun start(iccid: String) { engineStarts += iccid }
            override fun stop(iccid: String) { engineStops += iccid }
        },
        simControl = object : SimControlSource {
            override val state = MutableStateFlow<SimControlUiState>(SimControlUiState.Loading)
            override fun refresh() = refreshed("sim")
            override suspend fun refreshAndAwait() = awaitRead("sim")
            override fun start(handle: SimHandle) { simStarts += handle }
            override fun stop(handle: SimHandle) { simStops += handle }
        },
        configuration = object : ConfigurationSource {
            override val state = MutableStateFlow<ConfigurationUiState>(ConfigurationUiState.Loading)
            override fun refresh() = refreshed("configuration")
            override suspend fun refreshAndAwait() = awaitRead("configuration")
            override suspend fun refreshIdentitiesAndAwait(): RefreshResult {
                identityRefreshes++
                return RefreshResult.Completed
            }
            override fun submit(instanceId: ConfigurationInstanceId, overrides: ConfigurationOverrides,
                onComplete: (com.vowifi.sw.manager.model.ConfigurationApplyOutcome) -> Unit) =
                com.vowifi.sw.manager.model.ConfigurationApplyAcceptance.Unavailable
            override fun provision(handle: SimHandle) { provisions += handle }
        },
        diagnostics = object : EnvironmentDiagnosticsSource {
            override val state = MutableStateFlow<EnvironmentDiagnosticsUiState>(EnvironmentDiagnosticsUiState.Loading)
            override fun refresh() = refreshed("diagnostics")
            override suspend fun refreshAndAwait() = awaitRead("diagnostics")
        },
        logs = object : OperationalLogSource {
            override val state = MutableStateFlow<OperationalLogUiState>(OperationalLogUiState.Loading)
            override fun refresh() = refreshed("logs")
            override suspend fun refreshAndAwait() = awaitRead("logs")
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
