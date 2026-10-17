package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

internal class AndroidSimControlSource(
    inventory: SubscriptionInventory,
    engine: EngineControlSource,
    handles: OpaqueIccidHandleStore,
    parentContext: CoroutineContext = Dispatchers.Default,
) : SimControlSource {
    private val delegate = DefaultSimControlSource(
        inventory = inventory,
        engine = engine,
        handles = handles,
        parentContext = parentContext,
    )

    override val state: StateFlow<SimControlUiState> = delegate.state

    override fun refresh() = delegate.refresh()

    override suspend fun refreshAndAwait(): RefreshResult = delegate.refreshAndAwait()

    override fun start(handle: SimHandle) = delegate.start(handle)

    override fun stop(handle: SimHandle) = delegate.stop(handle)

    override fun close() = delegate.close()
}
