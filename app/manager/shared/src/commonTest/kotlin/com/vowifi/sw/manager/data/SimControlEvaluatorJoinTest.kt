package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimControlEvaluatorJoinTest {
    @Test
    fun ordersOnlyActiveRowsBySlotAndKey() {
        val ready = simReady(
            active = listOf(
                simActive(SIM_KEY_B, slotIndex = 1, carrierName = "T-Mobile", mcc = "310", mnc = "260", lastFour = SIM_TAIL_FOUR),
                simActive(SIM_KEY_A, slotIndex = 0, carrierName = "CMCC", mcc = "460", mnc = "09"),
            ),
            engineRows = listOf(
                simEngine(SIM_KEY_C, EngineLifecycle.STOPPED, displayLabel = SAFE_ENGINE_LABEL),
                simEngine(SIM_KEY_B, EngineLifecycle.STOPPED, pid = 4242),
            ),
        )

        assertEquals(2, ready.instances.size)
        val unconfigured = ready.instances[0]
        val matched = ready.instances[1]
        assertEquals(listOf("sim-1", "sim-2"), ready.instances.map { it.handle.value })
        assertEquals(SimPresence.ACTIVE_UNCONFIGURED, unconfigured.presence)
        assertEquals(0, unconfigured.slotIndex)
        assertEquals("CMCC", unconfigured.carrierLabel)
        assertEquals("460", unconfigured.mcc)
        assertEquals("09", unconfigured.mnc)
        assertEquals(EngineLifecycle.CONFIG_MISSING, unconfigured.lifecycle)
        assertEquals(UNCONFIGURED_MESSAGE, unconfigured.message)
        assertEquals(true, unconfigured.canProvision)
        assertEquals(SimPresence.ACTIVE_MATCHED, matched.presence)
        assertEquals(1, matched.slotIndex)
        assertEquals("T-Mobile", matched.carrierLabel)
        assertEquals("310", matched.mcc)
        assertEquals("260", matched.mnc)
        assertEquals(EngineLifecycle.STOPPED, matched.lifecycle)
        assertEquals(4242, matched.pid)
        assertEquals("SIM 2 · T-Mobile · 310 / 260 · •••• 0336", matched.displayLabel)
        assertEquals(false, matched.canProvision)
        assertNoRawKeys(ready, SIM_KEY_A, SIM_KEY_B, SIM_KEY_C)
    }

    @Test
    fun activeAWithEngineAAndRetainedCExposesOnlyA() {
        val ready = simReady(
            active = listOf(simActive(SIM_KEY_A, slotIndex = 0, carrierName = "alpha")),
            engineRows = listOf(
                simEngine(SIM_KEY_A, EngineLifecycle.STOPPED),
                simEngine(SIM_KEY_C, EngineLifecycle.RUNNING, pid = 9, runtime = simRuntime()),
            ),
        )

        assertEquals(1, ready.instances.size)
        assertEquals(SimPresence.ACTIVE_MATCHED, ready.instances.single().presence)
        assertEquals("alpha", ready.instances.single().carrierLabel)
        assertNoRawKeys(ready, SIM_KEY_A, SIM_KEY_C)
    }

    @Test
    fun emptyActiveAndEngineYieldsReadyEmpty() {
        val ready = simReady(active = emptyList(), engineRows = emptyList())

        assertTrue(ready.instances.isEmpty())
        assertIs<SimControlUiState.Ready>(ready)
        assertNull(ready.message)
    }

    @Test
    fun skipsEmptyActiveKeysAndKeepsConfiguredEngineAsRemoved() {
        val ready = simReady(
            active = listOf(
                simActive("", slotIndex = 0),
                simActive("  ", slotIndex = 1, carrierName = "beta"),
                simActive(SIM_KEY_A, slotIndex = 2, carrierName = "gamma"),
            ),
            engineRows = listOf(
                simEngine("", EngineLifecycle.STOPPED),
                simEngine(SIM_KEY_A, EngineLifecycle.RUNNING, pid = 7, runtime = simRuntime()),
            ),
        )

        assertEquals(1, ready.instances.size)
        assertEquals(SimPresence.ACTIVE_MATCHED, ready.instances.single().presence)
        assertEquals("gamma", ready.instances.single().carrierLabel)
        assertEquals(7, ready.instances.single().pid)
    }

    @Test
    fun duplicateAndTiedInputsAreDeterministic() {
        val ready = simReady(
            active = listOf(
                simActive("key-z", slotIndex = 1, carrierName = "late"),
                simActive("key-m", slotIndex = 1, carrierName = "mid"),
                simActive("key-m", slotIndex = 0, carrierName = "first"),
                simActive("key-z", slotIndex = 3, carrierName = "ignored-duplicate"),
            ),
            engineRows = listOf(
                simEngine("key-z", EngineLifecycle.STOPPED),
                simEngine("key-z", EngineLifecycle.RUNNING, pid = 99),
                simEngine("key-r", EngineLifecycle.STOPPED, displayLabel = "实例 •••• 1111"),
                simEngine("key-r", EngineLifecycle.RUNNING, displayLabel = "实例 •••• 2222"),
            ),
        )

        assertEquals(listOf("sim-1", "sim-2"), ready.instances.map { it.handle.value })
        assertEquals("first", ready.instances[0].carrierLabel)
        assertEquals(0, ready.instances[0].slotIndex)
        assertEquals("late", ready.instances[1].carrierLabel)
        assertEquals(EngineLifecycle.STOPPED, ready.instances[1].lifecycle)
        assertNull(ready.instances[1].pid)
        assertEquals(2, ready.instances.size)
    }

    @Test
    fun configMissingEngineOnActiveIsUnconfigured() {
        val ready = simReady(
            active = listOf(simActive(SIM_KEY_A, slotIndex = 0, carrierName = "alpha")),
            engineRows = listOf(simEngine(SIM_KEY_A, EngineLifecycle.CONFIG_MISSING, pid = 3, message = "/data/adb/x")),
        )

        val instance = ready.instances.single()
        assertEquals(SimPresence.ACTIVE_UNCONFIGURED, instance.presence)
        assertEquals(EngineLifecycle.CONFIG_MISSING, instance.lifecycle)
        assertNull(instance.pid)
        assertEquals(UNCONFIGURED_MESSAGE, instance.message)
        assertNoRawKeys(ready, SIM_KEY_A)
    }

    @Test
    fun permissionAndFailureAreBounded() {
        val denied = SimControlEvaluator.permissionDenied("/data/adb/iccid=$SIM_ICCID_19")
        val failed = SimControlEvaluator.failure("Unexpected $SIM_ICCID_19")

        assertEquals("请在 KernelSU 中授予 SoftVoWiFi root 权限", denied.message)
        assertEquals("无法读取 SIM 控制状态", failed.message)
        assertPrivate(denied.message)
        assertPrivate(failed.message)
    }
}
