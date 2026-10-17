@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineControlAction
import com.vowifi.sw.manager.model.EngineControlCompletion
import com.vowifi.sw.manager.model.EngineControlOutcome
import com.vowifi.sw.manager.model.SimPresence
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

// 复用已迁入的唯一 flow 投递屏障；只调用真实 DefaultSimControlSource，不新造调度器。
class S3SimRefreshAwaitTest {
    @Test fun configMissingStillRefusesStartButPreservesTheTypedControlFailure() = runTest {
        val completed = EngineControlCompletion(1, EngineControlAction.Start, EngineControlOutcome.Failed)
        val inventory = FakeSubscriptionInventory(SubscriptionInventoryResult.Active(listOf(simActive(SIM_KEY_A, 0))))
        val engine = FakeEngineControlSource(engineReady(engineRow(SIM_KEY_A, EngineLifecycle.CONFIG_MISSING)
            .copy(controlCompletion = completed)))
        val source = DefaultSimControlSource(inventory, engine, OpaqueIccidHandleStore(), coroutineContext)
        try {
            runCurrent()
            val row = readyInstances(source.state.value).single()
            assertEquals(SimPresence.ACTIVE_UNCONFIGURED, row.presence)
            assertEquals(EngineLifecycle.CONFIG_MISSING, row.lifecycle)
            assertFalse(row.canStart)
            assertFalse(row.canApply)
            assertEquals(completed, row.controlCompletion)
        } finally { source.close(); runCurrent() }
    }

    @Test fun closeCancelsAnAcceptedNotYetExecutedInventoryBatch() = runTest {
        val inventory = FakeSubscriptionInventory(SubscriptionInventoryResult.Active(listOf(simActive(SIM_KEY_A, 0))))
        val engine = FakeEngineControlSource(engineReady(engineRow(SIM_KEY_A, EngineLifecycle.STOPPED)))
        val source = DefaultSimControlSource(inventory, engine, OpaqueIccidHandleStore(), coroutineContext)
        try {
            runCurrent()
            val before = source.state.value
            source.refresh()
            val user = async(start = CoroutineStart.UNDISPATCHED) { source.refreshAndAwait() }
            assertFalse(user.isCompleted)
            source.close()
            runCurrent()
            try { user.await(); error("关闭源不得返回成功") } catch (_: CancellationException) { }
            assertEquals(RefreshResult.Unsupported, source.refreshAndAwait())
            assertEquals(1, inventory.listActiveCalls)
            assertEquals(before, source.state.value)
        } finally { source.close(); runCurrent() }
    }

    @Test fun joinsAcceptedAutomaticInventoryAndReturnsAfterCurrentEngineProjection() = runTest {
        val inventory = FakeSubscriptionInventory(SubscriptionInventoryResult.Active(listOf(simActive(SIM_KEY_A, 0))))
        val engine = FakeEngineControlSource(engineReady(engineRow(SIM_KEY_A, EngineLifecycle.STOPPED)))
        val running = engineReady(engineRow(SIM_KEY_A, EngineLifecycle.RUNNING, pid = 42))
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val delayed = object : EngineControlSource by engine {
            override val state = s3HoldEngineDelivery(engine.state) { value ->
                if (value == running) { entered.complete(Unit); hold.await() }
            }
        }
        val source = DefaultSimControlSource(inventory, delayed, OpaqueIccidHandleStore(), coroutineContext)
        try {
            runCurrent()
            engine.emit(running)
            runCurrent()
            assertTrue(entered.isCompleted)
            source.refresh() // 真实库存工作已接受，尚未获调度；用户须 join，而非误报 Busy/完成。
            val first = async(start = CoroutineStart.UNDISPATCHED) { source.refreshAndAwait() }
            val duplicate = async(start = CoroutineStart.UNDISPATCHED) { source.refreshAndAwait() }
            assertFalse(first.isCompleted)
            assertFalse(duplicate.isCompleted)
            assertEquals(1, inventory.listActiveCalls)
            runCurrent()
            assertTrue(first.isCompleted)
            assertTrue(duplicate.isCompleted)
            assertEquals(RefreshResult.Completed, first.await())
            assertEquals(first.await(), duplicate.await())
            assertEquals(2, inventory.listActiveCalls)
            assertEquals(0, engine.refreshCalls)
            assertEquals(42L, readyInstances(source.state.value).single().pid)
            assertEquals(EngineLifecycle.RUNNING, readyInstances(source.state.value).single().lifecycle)
        } finally { hold.complete(Unit); source.close(); runCurrent() }
    }

    @Test fun lateCollectorCannotRewindProjectionAfterCompletedRefresh() = runTest {
        val inventory = FakeSubscriptionInventory(SubscriptionInventoryResult.Active(listOf(simActive(SIM_KEY_A, 0))))
        val stopped = engineReady(engineRow(SIM_KEY_A, EngineLifecycle.STOPPED))
        val running = engineReady(engineRow(SIM_KEY_A, EngineLifecycle.RUNNING, pid = 42))
        val engine = FakeEngineControlSource(stopped)
        val runningHold = CompletableDeferred<Unit>()
        val stoppedHold = CompletableDeferred<Unit>()
        val stoppedEntered = CompletableDeferred<Unit>()
        var freeze = false
        val delayed = object : EngineControlSource by engine {
            override val state = s3HoldEngineDelivery(engine.state) { value ->
                if (freeze && value == running) runningHold.await()
                if (freeze && value == stopped) { stoppedEntered.complete(Unit); stoppedHold.await() }
            }
        }
        val source = DefaultSimControlSource(inventory, delayed, OpaqueIccidHandleStore(), coroutineContext)
        try {
            runCurrent()
            freeze = true
            engine.emit(running)
            runCurrent()
            engine.emit(stopped)
            val user = async(start = CoroutineStart.UNDISPATCHED) { source.refreshAndAwait() }
            runCurrent()
            assertTrue(user.isCompleted)
            assertEquals(RefreshResult.Completed, user.await())
            runningHold.complete(Unit)
            runCurrent() // 旧 RUNNING 已送达，较新 STOPPED 的 collector 仍被挡住。
            assertTrue(stoppedEntered.isCompleted)
            assertEquals(EngineLifecycle.STOPPED, readyInstances(source.state.value).single().lifecycle)
            assertNull(readyInstances(source.state.value).single().pid)
            assertEquals(2, inventory.listActiveCalls)
        } finally { runningHold.complete(Unit); stoppedHold.complete(Unit); source.close(); runCurrent() }
    }
}
