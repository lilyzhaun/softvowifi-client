package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigurationRebuildPresentationTest {
    private fun instance(format: ConfigurationFormatState, lifecycle: ConfigurationLifecycle = ConfigurationLifecycle.STOPPED) =
        ConfigurationInstance(ConfigurationInstanceId("sim-1"), "实例 ••••", lifecycle, ConfigurationTransport.WIFI,
            "", "", "", "", "", "", "", "", formatState = format)

    private fun active(lifecycle: EngineLifecycle = EngineLifecycle.STOPPED) = SimControlUiState.Ready(listOf(
        SimControlInstance(SimHandle("sim-1"), "SIM 1", SimPresence.ACTIVE_MATCHED, 0, "Synthetic", "310", "260",
            lifecycle, canStart = true, canStop = false, canApply = true),
    ))

    @Test
    fun activeLegacyIsARebuildDecisionNotAnEditableCurrentConfig() {
        val state = ConfigurationUiState.Ready(listOf(instance(ConfigurationFormatState.LEGACY)))
        val presentation = configurationPresentation(state, active())
        val row = presentation.rows.single()
        assertEquals("REBUILD_REQUIRED", row.kind.name)
        assertEquals(SimHandle("sim-1"), row.handle)
        assertTrue(row.status.orEmpty().contains("重建"))
        assertFalse(row.canApply)
        assertFalse(presentation.showEditor)
        assertFalse(canApplyConfiguration(state, ConfigurationInstanceId("sim-1")))
    }

    @Test
    fun unsupportedAndUnconfirmedInventoryNeverBecomeRebuildPermissions() {
        val future = ConfigurationUiState.Ready(listOf(instance(ConfigurationFormatState.UNSUPPORTED)))
        val row = configurationPresentation(future, active()).rows.single()
        assertEquals("UNAVAILABLE", row.kind.name)
        assertFalse(row.canApply)
        assertFalse(canApplyConfiguration(future, ConfigurationInstanceId("sim-1")))
        val legacy = ConfigurationUiState.Ready(listOf(instance(ConfigurationFormatState.LEGACY)))
        assertFalse(configurationPresentation(legacy).rows.single().canApply)
        assertFalse(configurationPresentation(legacy, SimControlUiState.Failure("无法读取活动订阅")).rows.single().canApply)
    }

    @Test
    fun runningLegacyExplainsTheRequiredStopWithoutStoppingAnything() {
        val state = ConfigurationUiState.Ready(listOf(instance(ConfigurationFormatState.LEGACY, ConfigurationLifecycle.RUNNING)))
        val row = configurationPresentation(state, active(EngineLifecycle.RUNNING)).rows.single()
        assertEquals("REBUILD_REQUIRED", row.kind.name)
        assertTrue(row.status.orEmpty().contains("停止"))
        assertFalse(row.canApply)
    }
}
