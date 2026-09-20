package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.SimPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimControlEvaluatorCapabilityTest {
    @Test
    fun matchedStoppedCanStartAndApply() {
        val instance = simReady(
            active = listOf(simActive(SIM_KEY_A, slotIndex = 0)),
            engineRows = listOf(simEngine(SIM_KEY_A, EngineLifecycle.STOPPED, pid = null)),
        ).instances.single()

        assertEquals(SimPresence.ACTIVE_MATCHED, instance.presence)
        assertEquals(EngineLifecycle.STOPPED, instance.lifecycle)
        assertEquals(true, instance.canStart)
        assertEquals(false, instance.canStop)
        assertEquals(true, instance.canApply)
        assertEquals(false, instance.canProvision)
        assertNull(instance.message)
    }

    @Test
    fun matchedRunningCanStopAndApply() {
        val runtime = simRuntime()
        val instance = simReady(
            active = listOf(simActive(SIM_KEY_A, slotIndex = 1, lastFour = "1234")),
            engineRows = listOf(simEngine(SIM_KEY_A, EngineLifecycle.RUNNING, pid = 88, runtime = runtime, message = "隧道已连接")),
        ).instances.single()

        assertEquals(SimPresence.ACTIVE_MATCHED, instance.presence)
        assertEquals(EngineLifecycle.RUNNING, instance.lifecycle)
        assertEquals(false, instance.canStart)
        assertEquals(true, instance.canStop)
        assertEquals(true, instance.canApply)
        assertEquals(false, instance.canProvision)
        assertEquals(88, instance.pid)
        assertEquals(runtime, instance.runtime)
        assertEquals("隧道已连接", instance.message)
    }

    @Test
    fun matchedStartingAndStoppingDisableActionsAndApply() {
        listOf(EngineLifecycle.STARTING, EngineLifecycle.STOPPING).forEach { lifecycle ->
            val instance = simReady(
                active = listOf(simActive(SIM_KEY_A, slotIndex = 0)),
                engineRows = listOf(simEngine(SIM_KEY_A, lifecycle, pid = 5)),
            ).instances.single()

            assertEquals(SimPresence.ACTIVE_MATCHED, instance.presence)
            assertEquals(lifecycle, instance.lifecycle)
            assertEquals(false, instance.canStart, lifecycle.name)
            assertEquals(false, instance.canStop, lifecycle.name)
            assertEquals(false, instance.canApply, lifecycle.name)
            assertEquals(false, instance.canProvision, lifecycle.name)
        }
    }

    @Test
    fun unconfiguredDisablesStartStopApply() {
        val missingEngine = simReady(
            active = listOf(simActive(SIM_KEY_A, slotIndex = 0)),
            engineRows = emptyList(),
        ).instances.single()
        val configMissing = simReady(
            active = listOf(simActive(SIM_KEY_B, slotIndex = 1)),
            engineRows = listOf(simEngine(SIM_KEY_B, EngineLifecycle.CONFIG_MISSING)),
        ).instances.single()

        listOf(missingEngine, configMissing).forEach { instance ->
            assertEquals(SimPresence.ACTIVE_UNCONFIGURED, instance.presence)
            assertEquals(EngineLifecycle.CONFIG_MISSING, instance.lifecycle)
            assertEquals(false, instance.canStart)
            assertEquals(false, instance.canStop)
            assertEquals(false, instance.canApply)
            assertEquals(true, instance.canProvision)
            assertEquals(UNCONFIGURED_MESSAGE, instance.message)
            assertNull(instance.pid)
            assertNull(instance.runtime)
        }
    }

    @Test
    fun retainedRunningEngineRowIsNotPublic() {
        val ready = simReady(
            active = emptyList(),
            engineRows = listOf(simEngine(SIM_KEY_C, EngineLifecycle.RUNNING, pid = 11, runtime = simRuntime("CONNECTING"))),
        )

        assertTrue(ready.instances.isEmpty())
    }

    @Test
    fun retainedStoppedEngineRowIsNotPublic() {
        val ready = simReady(
            active = emptyList(),
            engineRows = listOf(simEngine(SIM_KEY_C, EngineLifecycle.STOPPED)),
        )

        assertTrue(ready.instances.isEmpty())
    }

    @Test
    fun retainedTransitionalEngineRowsAreNotPublic() {
        listOf(EngineLifecycle.STARTING, EngineLifecycle.STOPPING).forEach { lifecycle ->
            val ready = simReady(
                active = emptyList(),
                engineRows = listOf(simEngine(SIM_KEY_C, lifecycle, pid = 4)),
            )

            assertTrue(ready.instances.isEmpty(), lifecycle.name)
        }
    }

    @Test
    fun unconfiguredValidPlmnCanProvisionWithoutStartStopApply() {
        val cases = listOf(
            Triple("310", "260", "T-Mobile"),
            Triple("460", "09", "CMCC"),
            Triple("505", "03", "Vodafone"),
        )

        cases.forEach { (mcc, mnc, carrier) ->
            val instance = simReady(
                active = listOf(
                    simActive(SIM_KEY_A, slotIndex = 1, carrierName = carrier, mcc = mcc, mnc = mnc),
                ),
                engineRows = emptyList(),
            ).instances.single()

            assertEquals(SimPresence.ACTIVE_UNCONFIGURED, instance.presence, "$mcc/$mnc")
            assertEquals(mcc, instance.mcc, "$mcc/$mnc")
            assertEquals(mnc, instance.mnc, "$mcc/$mnc")
            assertEquals(true, instance.canProvision, "$mcc/$mnc")
            assertEquals(false, instance.canStart, "$mcc/$mnc")
            assertEquals(false, instance.canStop, "$mcc/$mnc")
            assertEquals(false, instance.canApply, "$mcc/$mnc")
        }
    }

    @Test
    fun unconfiguredMissingOrInvalidPlmnCannotProvision() {
        val cases = listOf(
            simActive(SIM_KEY_A, slotIndex = 0, mcc = null, mnc = "260"),
            simActive(SIM_KEY_A, slotIndex = 0, mcc = "310", mnc = null),
            simActive(SIM_KEY_A, slotIndex = 0, mcc = "31", mnc = "260"),
            simActive(SIM_KEY_A, slotIndex = 0, mcc = "310", mnc = "2"),
            simActive(SIM_KEY_A, slotIndex = 0, mcc = "3100", mnc = "260"),
        )

        cases.forEach { active ->
            val instance = simReady(
                active = listOf(active),
                engineRows = emptyList(),
            ).instances.single()

            assertEquals(SimPresence.ACTIVE_UNCONFIGURED, instance.presence)
            assertEquals(false, instance.canProvision)
            assertEquals(false, instance.canStart)
            assertEquals(false, instance.canStop)
            assertEquals(false, instance.canApply)
        }
    }

    @Test
    fun matchedValidPlmnCannotProvision() {
        val instance = simReady(
            active = listOf(
                simActive(SIM_KEY_A, slotIndex = 1, carrierName = "T-Mobile", mcc = "310", mnc = "260"),
            ),
            engineRows = listOf(simEngine(SIM_KEY_A, EngineLifecycle.STOPPED)),
        ).instances.single()

        assertEquals(SimPresence.ACTIVE_MATCHED, instance.presence)
        assertEquals(false, instance.canProvision)
        assertEquals(true, instance.canStart)
        assertEquals(true, instance.canApply)
    }
}
