package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.RegistrationEvent
import com.vowifi.sw.manager.model.RegistrationSnapshot
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeProductizationTest {
    @Test
    fun oldObserverDatesDoNotAppearAsSoftwareRegistrationEvidence() {
        val firstTimestamp = 1_704_110_400_000L
        val secondTimestamp = firstTimestamp + 86_400_000L
        fun facts(timestamp: Long): List<HomeFactPresentation> {
            val event = RegistrationEvent(timestamp, 1, "REGISTERED")
            return homeDashboardPresentation(
                RegistrationUiState.Data(RegistrationSnapshot(listOf(event), listOf(event), emptyList())),
                SimControlUiState.Ready(emptyList()), EnvironmentDiagnosticsUiState.Loading,
                secondTimestamp + 7 * 86_400_000L,
            ).facts
        }
        assertEquals(facts(firstTimestamp), facts(secondTimestamp))
        assertEquals("暂无活动 SIM", facts(firstTimestamp).first().value)
        assertFalse(facts(firstTimestamp).any { it.label == "最近注册事件" || it.label == "事件质量" })
    }

    @Test
    fun operatorIdentityDoesNotIncludeTechnicalDisplayLabel() {
        val row = dashboard(instance()).actions.single()
        assertEquals("SIM 2 · 🇺🇸 Carrier", row.title)
        assertFalse(row.title.contains("310260"))
        assertFalse(row.title.contains("1234"))
    }

    @Test
    fun unavailableCountsAreNotReportedAsMeasuredZero() {
        val dashboard = homeDashboardPresentation(
            RegistrationUiState.ReadError("unavailable"), SimControlUiState.Loading,
            EnvironmentDiagnosticsUiState.Loading, 0L, { "date" },
        )
        assertEquals(listOf("未知", "未知"), dashboard.metrics.map { it.value })
    }

    @Test
    fun unconfiguredSimKeepsActionableExplanationWithProductionMessage() {
        val sim = instance().copy(
            presence = SimPresence.ACTIVE_UNCONFIGURED,
            canStart = false,
            canProvision = true,
            message = "未配置/未支持",
        )
        assertTrue(dashboard(sim).actions.single().summary.contains("可以创建"))
        assertTrue(dashboard(sim.copy(canProvision = false)).actions.single().summary.contains("无法自动创建"))
    }

    @Test
    fun projectedActionsKeepTypedDispatchAndBusyProtection() {
        val invoked = mutableListOf<Pair<HomeActionId, SimHandle>>()
        val states = listOf(
            instance(),
            instance().copy(lifecycle = EngineLifecycle.RUNNING, canStart = false, canStop = true),
            instance().copy(presence = SimPresence.ACTIVE_UNCONFIGURED, canStart = false, canProvision = true),
        )
        states.forEach { state ->
            val action = dashboard(state).actions.single().action
            assertTrue(action.enabled)
            dispatchHomeAction(action,
                { invoked.add(HomeActionId.START to it) },
                { invoked.add(HomeActionId.STOP to it) },
                { invoked.add(HomeActionId.PROVISION to it) },
            )
        }
        assertEquals(listOf(HomeActionId.START, HomeActionId.STOP, HomeActionId.PROVISION), invoked.map { it.first })
        assertTrue(invoked.all { it.second == instance().handle })
        val busy = dashboard(instance().copy(lifecycle = EngineLifecycle.STARTING)).actions.single().action
        assertTrue(busy.busy)
        assertFalse(busy.enabled)
        assertEquals("启动中", busy.label)
    }

    private fun dashboard(instance: SimControlInstance) = homeDashboardPresentation(
        RegistrationUiState.Empty, SimControlUiState.Ready(listOf(instance)),
        EnvironmentDiagnosticsUiState.Loading, 0L, { "date" },
    )

    private fun instance() = SimControlInstance(
        SimHandle("test-sim"), "SIM 2 · Carrier 310260 · ••••1234",
        SimPresence.ACTIVE_MATCHED, 1, "Carrier", "310", "260", EngineLifecycle.STOPPED,
        canStart = true, canStop = false, canApply = true,
        homeCarrierLabel = "Carrier",
    )
}
