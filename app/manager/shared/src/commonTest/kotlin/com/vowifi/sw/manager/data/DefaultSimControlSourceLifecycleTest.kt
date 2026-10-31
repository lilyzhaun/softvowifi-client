@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class DefaultSimControlSourceLifecycleTest {
    @Test
    fun registersListenerThenListsActiveOnceWithoutCallback() = runTest(UnconfinedTestDispatcher()) {
        val inventory = FakeSubscriptionInventory(activeA())
        val engine = FakeEngineControlSource(engineReady(engineRow(SIM_KEY_A, EngineLifecycle.STOPPED)))
        val source = simControlSource(inventory, engine, parentContext = coroutineContext)

        assertEquals(listOf("startWatching", "listActive"), inventory.events)
        assertEquals(1, inventory.startWatchingCalls)
        assertEquals(1, inventory.listActiveCalls)
        assertEquals(0, engine.refreshCalls)
        assertIs<SimControlUiState.Ready>(source.state.value)
        source.close()
    }

    @Test
    fun listenerCallbackRefreshesInventoryAgain() = runTest(UnconfinedTestDispatcher()) {
        val inventory = FakeSubscriptionInventory(activeA())
        val engine = FakeEngineControlSource(engineReady())
        val source = simControlSource(inventory, engine, parentContext = coroutineContext)

        inventory.result = SubscriptionInventoryResult.Active(
            listOf(simActive(SIM_KEY_A, slotIndex = 0), simActive(SIM_KEY_B, slotIndex = 1)),
        )
        inventory.emitChange()

        assertEquals(2, inventory.listActiveCalls)
        assertEquals(2, readyInstances(source.state.value).size)
        assertEquals(0, engine.refreshCalls)
        source.close()
    }

    @Test
    fun closeUnregistersAndIgnoresLaterCallbackAndEngineEmissions() = runTest(UnconfinedTestDispatcher()) {
        val inventory = FakeSubscriptionInventory(activeA())
        val engine = FakeEngineControlSource(engineReady(engineRow(SIM_KEY_A, EngineLifecycle.STOPPED)))
        val handles = OpaqueIccidHandleStore()
        val source = simControlSource(inventory, engine, handles, coroutineContext)
        val callback = inventory.onChanged
        val previous = source.state.value
        val handle = readyInstances(previous).single().handle

        source.close()
        inventory.result = SubscriptionInventoryResult.Active(listOf(simActive(SIM_KEY_B, slotIndex = 1)))
        callback?.invoke()
        engine.emit(engineReady(engineRow(SIM_KEY_A, EngineLifecycle.RUNNING, pid = 9)))

        assertEquals(1, inventory.stopWatchingCalls)
        assertEquals(1, inventory.listActiveCalls)
        assertEquals(previous, source.state.value)
        assertEquals(SIM_KEY_A, handles.keyFor(handle))
        assertEquals(0, engine.refreshCalls)
    }

    @Test
    fun overlappingRefreshIsDroppedByOperationGate() = runTest {
        val inventory = FakeSubscriptionInventory(activeA())
        val engine = FakeEngineControlSource(engineReady(engineRow(SIM_KEY_A, EngineLifecycle.STOPPED)))
        val source = simControlSource(inventory, engine, parentContext = coroutineContext)

        source.refresh()
        source.refresh()
        advanceUntilIdle()

        assertEquals(1, inventory.listActiveCalls)
        source.refresh()
        source.refresh()
        advanceUntilIdle()
        assertEquals(2, inventory.listActiveCalls)
        source.close()
    }

    @Test
    fun engineStateRejoinsCachedInventoryWithoutEngineRefresh() = runTest(UnconfinedTestDispatcher()) {
        val inventory = FakeSubscriptionInventory(activeA())
        val engine = FakeEngineControlSource(engineReady(engineRow(SIM_KEY_A, EngineLifecycle.STOPPED)))
        val source = simControlSource(inventory, engine, parentContext = coroutineContext)

        engine.emit(engineReady(engineRow(SIM_KEY_A, EngineLifecycle.RUNNING, pid = 42)))

        val instance = readyInstances(source.state.value).single()
        assertEquals(EngineLifecycle.RUNNING, instance.lifecycle)
        assertEquals(42, instance.pid)
        assertEquals(1, inventory.listActiveCalls)
        assertEquals(0, engine.refreshCalls)
        source.close()
    }

    @Test
    fun injectedHandleStoreIsSharedAndNotClearedOnClose() = runTest(UnconfinedTestDispatcher()) {
        val handles = OpaqueIccidHandleStore()
        val preexisting = handles.handleFor(SIM_KEY_A)
        val inventory = FakeSubscriptionInventory(activeA())
        val engine = FakeEngineControlSource(engineReady(engineRow(SIM_KEY_A, EngineLifecycle.STOPPED)))
        val source = simControlSource(inventory, engine, handles, coroutineContext)

        assertEquals(preexisting, readyInstances(source.state.value).single().handle)
        source.close()
        assertEquals(SIM_KEY_A, handles.keyFor(preexisting))
        assertEquals(preexisting, handles.handleFor(SIM_KEY_A))
    }

    @Test
    fun failedListenerRegistrationStillRunsInitialListActive() = runTest(UnconfinedTestDispatcher()) {
        val inventory = FakeSubscriptionInventory(
            result = SubscriptionInventoryResult.PermissionDenied("iccid=$SIM_ICCID_19"),
            registerListener = false,
        )
        val engine = FakeEngineControlSource(engineReady(engineRow(SIM_KEY_A, EngineLifecycle.STOPPED)))
        val source = simControlSource(inventory, engine, parentContext = coroutineContext)

        assertEquals(listOf("startWatching", "listActive"), inventory.events)
        assertEquals(null, inventory.onChanged)
        val denied = assertIs<SimControlUiState.PermissionDenied>(source.state.value)
        assertEquals("请在 KernelSU 中授予 SoftVoWiFi root 权限", denied.message)
        source.close()
        assertEquals(1, inventory.stopWatchingCalls)
    }

    @Test
    fun closeStopsOnlyOwnWatchAndLeavesSiblingConsumers() = runTest(UnconfinedTestDispatcher()) {
        val inventory = FakeSubscriptionInventory(activeA())
        var siblingTicks = 0
        val sibling = inventory.startWatching { siblingTicks += 1 }
        val engine = FakeEngineControlSource(engineReady(engineRow(SIM_KEY_A, EngineLifecycle.STOPPED)))
        val source = simControlSource(inventory, engine, parentContext = coroutineContext)

        source.close()
        inventory.emitChange()

        assertEquals(1, siblingTicks)
        assertEquals(1, inventory.listActiveCalls)
        assertEquals(1, inventory.stopWatchingCalls)
        sibling.stop()
        inventory.emitChange()
        assertEquals(1, siblingTicks)
        assertEquals(2, inventory.stopWatchingCalls)
    }

    @Test
    fun sameKeyRetiresOldActionsAfterDisappearAndReappear() = runTest(UnconfinedTestDispatcher()) {
        val handles = OpaqueIccidHandleStore()
        val inventory = FakeSubscriptionInventory(activeA())
        val engine = FakeEngineControlSource(engineReady(engineRow(SIM_KEY_A, EngineLifecycle.STOPPED)))
        val source = simControlSource(inventory, engine, handles, coroutineContext)
        val original = readyInstances(source.state.value).single().handle

        inventory.result = SubscriptionInventoryResult.Active(emptyList())
        inventory.emitChange()
        assertTrue(readyInstances(source.state.value).isEmpty())
        assertNull(handles.keyFor(original))

        inventory.result = activeA()
        inventory.emitChange()
        val restored = readyInstances(source.state.value).single()
        assertEquals(SimPresence.ACTIVE_MATCHED, restored.presence)
        assertNotEquals(original, restored.handle)
        assertEquals(SIM_KEY_A, handles.keyFor(restored.handle))
        source.start(original)
        assertTrue(engine.started.isEmpty())
        source.start(restored.handle)
        assertEquals(listOf(SIM_KEY_A), engine.started)
        source.close()
    }
}

private fun activeA() = SubscriptionInventoryResult.Active(listOf(simActive(SIM_KEY_A, slotIndex = 0)))
