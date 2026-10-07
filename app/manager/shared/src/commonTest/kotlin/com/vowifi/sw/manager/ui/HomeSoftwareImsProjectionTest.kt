package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineRuntimeStatus
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.ParseIssue
import com.vowifi.sw.manager.model.RegistrationEvent
import com.vowifi.sw.manager.model.RegistrationSnapshot
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeSoftwareImsProjectionTest {
    @Test
    fun stoppedSecondSimDoesNotMakeRegisteredServiceYellow() {
        val result = dashboard(SimControlUiState.Ready(listOf(sim(), sim(1).copy(lifecycle = EngineLifecycle.STOPPED, runtime = null))))
        assertEquals(SemanticTone.SUCCESS, result.hero.tone)
        assertEquals("IMS 已注册", result.hero.title)
        assertEquals(SemanticTone.SUCCESS, result.metrics.first().tone)
        assertEquals("1 / 2 张活动 SIM 已注册", result.facts.first().value)
    }

    @Test
    fun twoActiveSimsIgnoreThreeHistoricalObserverSubscriptions() {
        val result = dashboard(ready(), stock("REGISTERED", "REGISTERED", "REGISTERED"))
        assertEquals("2", result.metrics.first().value)
        assertEquals("已注册 IMS", result.metrics.first().label)
        assertEquals("2 / 2 张活动 SIM 已注册", result.facts.first().value)
        assertEquals("IMS 注册概况", result.facts.first().label)
    }

    @Test
    fun zeroStockRegistrationsCannotOverrideTwoSoftwareRegistrations() {
        val result = dashboard(ready(), stock("UNREGISTERED", "UNREGISTERED"))
        assertEquals("2", result.metrics.first().value)
        assertEquals(SemanticTone.SUCCESS, result.hero.tone)
    }

    @Test
    fun observerLoadingEmptyReadAndParseErrorsDoNotChangeSoftwareHealth() {
        val baseline = dashboard(ready(), stock("REGISTERED"))
        listOf(RegistrationUiState.Loading, RegistrationUiState.Empty,
            RegistrationUiState.ReadError("observer unavailable"),
            RegistrationUiState.ParseError(listOf(ParseIssue(1, "invalid"))),
            stock("REGISTERED").copy(snapshot = stock("REGISTERED").snapshot.copy(issues = listOf(ParseIssue(1, "invalid")))),
        ).forEach { observer ->
            val result = dashboard(ready(), observer)
            assertEquals(baseline.metrics, result.metrics)
            assertEquals(baseline.facts, result.facts)
            assertEquals(baseline.hero, result.hero)
            assertTrue(result.notices.isEmpty())
        }
    }

    @Test
    fun nonRunningLifecycleNeverCountsRetainedRegisteredRuntime() {
        EngineLifecycle.entries.filter { it != EngineLifecycle.RUNNING }.forEach { lifecycle ->
            val result = dashboard(SimControlUiState.Ready(listOf(sim().copy(lifecycle = lifecycle))))
            assertEquals("0", result.metrics.first().value, lifecycle.name)
            assertFalse(result.hero.tone == SemanticTone.SUCCESS, lifecycle.name)
        }
    }

    @Test
    fun unknownOtherLineKeepsKnownRegistrationGreenAndReportsUnknownCount() {
        listOf(null, runtime().copy(ims = "unexpected"), runtime().copy(ims = "registered")).forEach { runtime ->
            val result = dashboard(SimControlUiState.Ready(listOf(sim(), sim(1).copy(runtime = runtime))))
            assertEquals("1", result.metrics.first().value)
            assertEquals("1 / 2 张活动 SIM 已注册 · 1 张未知", result.facts.first().value)
            assertEquals("IMS 已注册", result.hero.title)
            assertEquals(SemanticTone.SUCCESS, result.hero.tone)
        }
    }

    @Test
    fun retainedConfigurationIsExcludedButUnconfiguredActiveSimCounts() {
        val retained = sim(2).copy(presence = SimPresence.REMOVED_CONFIG_RETAINED)
        val result = dashboard(SimControlUiState.Ready(listOf(sim(), sim(1), retained)))
        assertEquals("2", result.metrics.first().value)
        assertEquals("2 / 2 张活动 SIM 已注册", result.facts.first().value)
        val unconfigured = sim(1).copy(presence = SimPresence.ACTIVE_UNCONFIGURED, lifecycle = EngineLifecycle.CONFIG_MISSING)
        val partial = dashboard(SimControlUiState.Ready(listOf(sim(), unconfigured, retained)))
        assertEquals("1 / 2 张活动 SIM 已注册", partial.facts.first().value)
        assertEquals("IMS 已注册", partial.hero.title)
        assertEquals(SemanticTone.SUCCESS, partial.hero.tone)
        assertTrue(partial.notices.any { it.title == "SIM 尚未配置" })
    }

    @Test
    fun oneRegisteredOneUnregisteredIsRegisteredWithoutClaimingBothReady() {
        val result = dashboard(SimControlUiState.Ready(listOf(sim(), sim(1).copy(runtime = runtime().copy(ims = "UNREGISTERED")))))
        assertEquals("1", result.metrics.first().value)
        assertEquals(SemanticTone.SUCCESS, result.metrics.first().tone)
        assertEquals("IMS 已注册", result.hero.title)
        assertEquals(SemanticTone.SUCCESS, result.hero.tone)
        assertEquals("1 / 2 张活动 SIM 已注册", result.facts.first().value)
    }

    @Test
    fun unknownEngineStatesNeverBecomeMeasuredZero() {
        listOf(SimControlUiState.Loading, SimControlUiState.Failure("failed"), SimControlUiState.PermissionDenied("denied")).forEach { state ->
            val result = dashboard(state)
            assertEquals("未知", result.metrics.first().value)
            assertFalse(result.hero.tone == SemanticTone.SUCCESS)
            assertFalse(result.facts.first().value.contains("0 / 0"))
        }
        assertEquals("SIM 控制权限不足", dashboard(SimControlUiState.PermissionDenied("denied")).notices.single().title)
    }

    @Test
    fun registrationGreenDoesNotHideSeparateEnvironmentAndControlWarnings() {
        listOf(EnvironmentDiagnosticsUiState.Failure("failed"), EnvironmentDiagnosticsUiState.PermissionDenied("denied"),
            EnvironmentDiagnosticsUiState.Unavailable("unsupported"), EnvironmentDiagnosticsUiState.Loading,
            EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.FAIL, emptyList()),
            EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.WARN, emptyList()),
        ).forEach { diagnostics ->
            val result = dashboard(ready(), diagnostics = diagnostics)
            assertEquals("2", result.metrics.first().value)
            assertEquals(SemanticTone.SUCCESS, result.hero.tone)
            if (diagnostics != EnvironmentDiagnosticsUiState.Loading) assertTrue(result.notices.isNotEmpty())
        }
        assertEquals(SemanticTone.SUCCESS, dashboard(ready().copy(message = "control warning")).hero.tone)
        assertTrue(dashboard(ready().copy(message = "control warning")).notices.isNotEmpty())
        assertEquals(SemanticTone.SUCCESS, dashboard(SimControlUiState.Ready(listOf(sim().copy(message = "instance warning")))).hero.tone)
    }

    @Test
    fun registrationHeroDoesNotClaimTunnelReadiness() {
        val result = dashboard(SimControlUiState.Ready(listOf(sim().copy(runtime = runtime().copy(tunnel = "DISCONNECTED")))))
        assertEquals("1", result.metrics.first().value)
        assertEquals("已注册", result.hero.statusText)
        assertEquals("IMS 已注册", result.hero.title)
        assertEquals(SemanticTone.SUCCESS, result.hero.tone)
        assertEquals(0, homeSoftwareImsProjection(SimControlUiState.Ready(listOf(sim().copy(runtime = runtime().copy(tunnel = "DISCONNECTED"))))).connected)
    }

    @Test
    fun noActiveSimIsNeutralAndHasNoFabricatedEventTime() {
        val result = dashboard(SimControlUiState.Ready(listOf(sim().copy(presence = SimPresence.REMOVED_CONFIG_RETAINED))))
        assertEquals("无 SIM", result.metrics.first().value)
        assertEquals("暂无活动 SIM", result.hero.title)
        assertEquals(SemanticTone.NEUTRAL, result.hero.tone)
        assertEquals("暂无活动 SIM", result.facts.first().value)
        assertFalse(result.facts.any { it.label in listOf("最近注册事件", "事件质量", "Observer 注册概况") })
    }

    private fun dashboard(
        engine: SimControlUiState,
        observer: RegistrationUiState = stock("REGISTERED"),
        diagnostics: EnvironmentDiagnosticsUiState = EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.PASS, emptyList()),
    ) = homeDashboardPresentation(observer, engine, diagnostics, 1_800_000_000_000L)

    private fun ready() = SimControlUiState.Ready(listOf(sim(), sim(1)))
    private fun sim(slot: Int = 0) = SimControlInstance(
        SimHandle("test-$slot"), "SIM ${slot + 1}", SimPresence.ACTIVE_MATCHED, slot,
        "Carrier", "460", "00", EngineLifecycle.RUNNING, runtime = runtime(),
        canStart = false, canStop = true, canApply = false,
    )
    private fun runtime() = EngineRuntimeStatus("CONNECTED", reconnects = 0, ims = "REGISTERED", call = "IDLE", transport = "UDP", pcscf = emptyList())
    private fun stock(vararg states: String): RegistrationUiState.Data {
        val events = states.mapIndexed { index, state -> RegistrationEvent(1_700_000_000_000L, index, state) }
        return RegistrationUiState.Data(RegistrationSnapshot(events, events, emptyList()))
    }
}
