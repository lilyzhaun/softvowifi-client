@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class DefaultSimControlSourceActionTest {
    @Test
    fun matchedStartWaitsForEngineStateBeforePublishingStarting() = runTest(UnconfinedTestDispatcher()) {
        val inventory = FakeSubscriptionInventory(activeKey(SIM_ICCID_19))
        val engine = FakeEngineControlSource(engineReady(engineRow(SIM_ICCID_19, EngineLifecycle.STOPPED)))
        val source = simControlSource(inventory, engine, parentContext = coroutineContext)
        val handle = readyInstances(source.state.value).single().handle

        source.start(handle)

        assertEquals(listOf(SIM_ICCID_19), engine.started)
        val unchanged = readyInstances(source.state.value).single()
        assertEquals(handle, unchanged.handle)
        assertEquals(EngineLifecycle.STOPPED, unchanged.lifecycle)
        assertTrue(unchanged.canStart)

        engine.emit(engineReady(engineRow(SIM_ICCID_19, EngineLifecycle.STARTING)))
        val starting = readyInstances(source.state.value).single()
        assertEquals(handle, starting.handle)
        assertEquals(EngineLifecycle.STARTING, starting.lifecycle)
        assertFalse(starting.canStart)
        assertFalse(starting.canStop)
        assertFalse(starting.canApply)
        assertNoRawKeys(source.state.value, SIM_ICCID_19)
        source.close()
    }

    @Test
    fun unconfiguredAndHiddenRetainedStoppedRowsDoNotDelegate() = runTest(UnconfinedTestDispatcher()) {
        val inventory = FakeSubscriptionInventory(
            SubscriptionInventoryResult.Active(listOf(simActive(SIM_KEY_A, slotIndex = 0))),
        )
        val engine = FakeEngineControlSource(
            engineReady(engineRow(SIM_KEY_C, EngineLifecycle.STOPPED)),
        )
        val source = simControlSource(inventory, engine, parentContext = coroutineContext)
        val ready = readyInstances(source.state.value)
        val unconfigured = ready.single()

        source.start(unconfigured.handle)
        source.start(SimHandle("sim-99"))

        assertTrue(engine.started.isEmpty())
        assertTrue(engine.stopped.isEmpty())
        source.close()
    }

    @Test
    fun hiddenRetainedRunningRowCannotBeStoppedThroughPublicHandle() = runTest(UnconfinedTestDispatcher()) {
        val inventory = FakeSubscriptionInventory(SubscriptionInventoryResult.Active(emptyList()))
        val engine = FakeEngineControlSource(
            engineReady(engineRow(SIM_ICCID_19, EngineLifecycle.RUNNING, pid = 11)),
        )
        val source = simControlSource(inventory, engine, parentContext = coroutineContext)

        assertTrue(readyInstances(source.state.value).isEmpty())
        source.stop(SimHandle("sim-1"))

        assertTrue(engine.stopped.isEmpty())
        assertNoRawKeys(source.state.value, SIM_ICCID_19)
        source.close()
    }

    @Test
    fun joinedCapabilitiesMatchEvaluator() = runTest(UnconfinedTestDispatcher()) {
        val inventory = FakeSubscriptionInventory(
            SubscriptionInventoryResult.Active(
                listOf(simActive(SIM_KEY_A, slotIndex = 0), simActive(SIM_KEY_B, slotIndex = 1)),
            ),
        )
        val engine = FakeEngineControlSource(
            engineReady(
                engineRow(SIM_KEY_B, EngineLifecycle.STOPPED),
                engineRow(SIM_KEY_C, EngineLifecycle.RUNNING, pid = 8),
            ),
        )
        val source = simControlSource(inventory, engine, parentContext = coroutineContext)
        val instances = readyInstances(source.state.value)

        assertEquals(listOf(false, true), instances.map { it.canStart })
        assertEquals(listOf(false, false), instances.map { it.canStop })
        assertEquals(
            listOf(
                SimPresence.ACTIVE_UNCONFIGURED,
                SimPresence.ACTIVE_MATCHED,
            ),
            instances.map { it.presence },
        )
        source.close()
    }

    @Test
    fun engineUnavailableStatesDoNotKeepStartableInstances() = runTest(UnconfinedTestDispatcher()) {
        val inventory = FakeSubscriptionInventory(activeKey(SIM_KEY_A))
        val engine = FakeEngineControlSource(engineReady(engineRow(SIM_KEY_A, EngineLifecycle.STOPPED)))
        val source = simControlSource(inventory, engine, parentContext = coroutineContext)
        val handle = readyInstances(source.state.value).single().handle
        assertTrue(readyInstances(source.state.value).single().canStart)

        engine.emit(EngineControlUiState.Loading)
        source.start(handle)
        assertIs<SimControlUiState.Loading>(source.state.value)
        assertTrue(engine.started.isEmpty())

        engine.emit(EngineControlUiState.PermissionDenied("KernelSU 尚未授权"))
        source.start(handle)
        val denied = assertIs<SimControlUiState.PermissionDenied>(source.state.value)
        assertEquals("请在 KernelSU 中授予 SoftVoWiFi root 权限", denied.message)
        assertTrue(engine.started.isEmpty())

        engine.emit(EngineControlUiState.Failure("/data/adb/$SIM_ICCID_19"))
        source.stop(handle)
        val failure = assertIs<SimControlUiState.Failure>(source.state.value)
        assertEquals("无法读取 SIM 控制状态", failure.message)
        assertTrue(engine.stopped.isEmpty())
        assertNoRawKeys(failure, SIM_ICCID_19)
        source.close()
    }

    @Test
    fun inventoryErrorsMapToBoundedUiStates() = runTest(UnconfinedTestDispatcher()) {
        val inventory = FakeSubscriptionInventory(
            SubscriptionInventoryResult.PermissionDenied("iccid=$SIM_ICCID_19"),
        )
        val engine = FakeEngineControlSource(engineReady(engineRow(SIM_ICCID_19, EngineLifecycle.STOPPED)))
        val source = simControlSource(inventory, engine, parentContext = coroutineContext)

        val denied = assertIs<SimControlUiState.PermissionDenied>(source.state.value)
        assertEquals("请在 KernelSU 中授予 SoftVoWiFi root 权限", denied.message)
        assertNoRawKeys(denied, SIM_ICCID_19)
        source.start(SimHandle("sim-1"))
        assertTrue(engine.started.isEmpty())

        inventory.result = SubscriptionInventoryResult.Failure("imsi=$SIM_DIGITS_15 /data/adb/x")
        inventory.emitChange()
        val failure = assertIs<SimControlUiState.Failure>(source.state.value)
        assertEquals("无法读取 SIM 控制状态", failure.message)
        assertNoRawKeys(failure, SIM_DIGITS_15)
        source.close()
    }

    @Test
    fun publicStateOmitsNineteenDigitKey() = runTest(UnconfinedTestDispatcher()) {
        val inventory = FakeSubscriptionInventory(activeKey(SIM_ICCID_19, lastFour = SIM_TAIL_FOUR))
        val engine = FakeEngineControlSource(engineReady(engineRow(SIM_ICCID_19, EngineLifecycle.STOPPED)))
        val source = simControlSource(inventory, engine, parentContext = coroutineContext)
        val ready = source.state.value

        assertEquals("sim-1", readyInstances(ready).single().handle.value)
        assertTrue(readyInstances(ready).single().displayLabel.endsWith("•••• 0336"))
        assertNoRawKeys(ready, SIM_ICCID_19)
        publicStrings(ready).forEach { text ->
            assertFalse(text.contains(SIM_ICCID_19))
            assertFalse(text.contains("iccid", ignoreCase = true))
        }
        source.close()
    }
}

private fun activeKey(key: String, lastFour: String? = null) = SubscriptionInventoryResult.Active(
    listOf(simActive(key, slotIndex = 1, carrierName = "T-Mobile", mcc = "310", mnc = "260", lastFour = lastFour)),
)
