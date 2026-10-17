@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

// 使用实际 DefaultSimControlSource；只阻挡既有假 engine 的 flow 投递，不复制 join。
class S3SimRefreshProjectionTest {
    @Test fun inventoryRefreshPublishesCurrentEngineEvenBeforeCollectorConsumesIt() = runTest {
        val inventory = FakeSubscriptionInventory(SubscriptionInventoryResult.Active(listOf(simActive(SIM_KEY_A, 0))))
        val engine = FakeEngineControlSource(engineReady(engineRow(SIM_KEY_A, EngineLifecycle.STOPPED)))
        val running = engineReady(engineRow(SIM_KEY_A, EngineLifecycle.RUNNING, pid = 42))
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val delayedEngine = object : EngineControlSource by engine {
            override val state = s3HoldEngineDelivery(engine.state) { value ->
                if (value == running) { entered.complete(Unit); hold.await() }
            }
        }
        val source = DefaultSimControlSource(inventory, delayedEngine, OpaqueIccidHandleStore(), coroutineContext)
        try {
            runCurrent()
            engine.emit(running)
            runCurrent()
            assertTrue(entered.isCompleted)
            assertEquals(running, delayedEngine.state.value)
            assertEquals(EngineLifecycle.STOPPED, readyInstances(source.state.value).single().lifecycle)

            source.refresh()
            source.refresh()
            runCurrent()
            assertEquals(2, inventory.listActiveCalls) // 初始化一次 + 本轮一次；不再刷库存补投影。
            assertEquals(0, engine.refreshCalls)
            val row = readyInstances(source.state.value).single()
            assertEquals(EngineLifecycle.RUNNING, row.lifecycle)
            assertEquals(42L, row.pid)
        } finally {
            hold.complete(Unit)
            source.close()
            runCurrent()
        }
    }
}

// 仅测试依赖的投递屏障，value/replayCache 仍来自原 StateFlow；不是新调度器或镜像状态机。
@OptIn(ExperimentalForInheritanceCoroutinesApi::class, InternalCoroutinesApi::class)
internal fun s3HoldEngineDelivery(
    upstream: StateFlow<EngineControlUiState>,
    beforeEmit: suspend (EngineControlUiState) -> Unit,
): StateFlow<EngineControlUiState> = object : StateFlow<EngineControlUiState> by upstream {
    override suspend fun collect(collector: FlowCollector<EngineControlUiState>): Nothing =
        upstream.collect(object : FlowCollector<EngineControlUiState> {
            override suspend fun emit(value: EngineControlUiState) {
                beforeEmit(value)
                collector.emit(value)
            }
        })
}
