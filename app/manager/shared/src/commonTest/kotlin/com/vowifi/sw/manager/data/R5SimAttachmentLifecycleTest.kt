package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.SimActiveJoinInput
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class R5SimAttachmentLifecycleTest {
    @Test fun movingTheSameCardRetiresOldActions() = runTest {
        val first = SimActiveJoinInput("0000000000000000001", 0)
        val inventory = FakeSubscriptionInventory(SubscriptionInventoryResult.Active(listOf(first)))
        val engine = FakeEngineControlSource(engineReady(
            engineRow(first.key, EngineLifecycle.STOPPED),
        ))
        val source = simControlSource(inventory, engine, parentContext = coroutineContext)
        try {
            runCurrent()
            val before = readyInstances(source.state.value)
            val old = before.first().handle
            inventory.result = SubscriptionInventoryResult.Active(listOf(first.copy(slotIndex = 1)))
            inventory.emitChange()
            source.start(old) // 尚未运行新一轮枚举时，也必须先撤旧动作。
            runCurrent()
            source.start(old) // 新快照就绪后，旧页面持有的闭包仍不能复活。
            assertTrue(engine.started.isEmpty(), "换槽前的动作仍启动了新附件")
            val after = readyInstances(source.state.value)
            assertNotEquals(old, after.first().handle)
            source.start(after.first().handle)
            assertEquals(listOf(first.key), engine.started)
        } finally { source.close() }
    }

    @Test fun removalAndReappearanceCannotReviveAnOldHandle() = runTest {
        val row = SimActiveJoinInput("0000000000000000001", 0)
        val other = SimActiveJoinInput("0000000000000000002", 1)
        val inventory = FakeSubscriptionInventory(SubscriptionInventoryResult.Active(listOf(row, other)))
        val engine = FakeEngineControlSource(engineReady(engineRow(row.key, EngineLifecycle.STOPPED), engineRow(other.key, EngineLifecycle.STOPPED)))
        val source = simControlSource(inventory, engine, parentContext = coroutineContext)
        try {
            runCurrent()
            val old = readyInstances(source.state.value).first().handle
            val retained = readyInstances(source.state.value).last().handle
            inventory.result = SubscriptionInventoryResult.Active(listOf(other))
            inventory.emitChange()
            runCurrent()
            assertEquals(retained, readyInstances(source.state.value).single().handle)
            inventory.result = SubscriptionInventoryResult.Active(listOf(row, other))
            inventory.emitChange()
            runCurrent()
            source.start(old)
            assertTrue(engine.started.isEmpty(), "移除前的动作在重新插卡后复活")
            assertNotEquals(old, readyInstances(source.state.value).first().handle)
            assertEquals(retained, readyInstances(source.state.value).last().handle)
        } finally { source.close() }
    }

    @Test fun subscriptionIdChangesRetireActionsWithoutChangingHistoryOwnership() {
        val handles = OpaqueIccidHandleStore()
        val row = SimActiveJoinInput("0000000000000000001", 0, subscriptionId = 1)
        handles.reconcileActive(listOf(row))
        val old = handles.handleFor(row.key)
        handles.reconcileActive(listOf(row.copy(subscriptionId = 2)))
        assertEquals(null, handles.keyFor(old))
        val current = handles.handleFor(row.key)
        assertNotEquals(old, current)
        handles.reconcileActive(listOf(row.copy(subscriptionId = 2)))
        assertEquals(current, handles.handleFor(row.key))
    }
}
