package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimEngineJoinInput
import com.vowifi.sw.manager.model.SimHandle
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class DefaultSimControlSource(
    private val inventory: SubscriptionInventory,
    private val engine: EngineControlSource,
    private val handles: OpaqueIccidHandleStore,
    parentContext: CoroutineContext,
) : SimControlSource {
    private val scope = CoroutineScope(parentContext.minusKey(Job) + SupervisorJob())
    private val operationGate = EngineOperationGate()
    private val mutableState = MutableStateFlow<SimControlUiState>(SimControlUiState.Loading)
    override val state: StateFlow<SimControlUiState> = mutableState.asStateFlow()

    @Volatile
    private var closed = false

    @Volatile
    private var latestInventory: SubscriptionInventoryResult? = null

    @Volatile
    private var latestEngine: EngineControlUiState = engine.state.value
    private val subscriptionWatch = inventory.startWatching(::refresh)

    init {
        scope.launch {
            engine.state.collect { snapshot ->
                if (closed) return@collect
                latestEngine = snapshot
                publish()
            }
        }
        refresh()
    }

    override fun refresh() {
        if (closed || !operationGate.tryEnter()) return
        scope.launch {
            val result = inventory.listActive()
            if (closed) return@launch
            latestInventory = result
            publish()
        }.invokeOnCompletion { operationGate.release() }
    }

    override fun start(handle: SimHandle) {
        val key = rawKeyIfCapable(handle) { it.canStart } ?: return
        engine.start(key)
    }

    override fun stop(handle: SimHandle) {
        val key = rawKeyIfCapable(handle) { it.canStop } ?: return
        engine.stop(key)
    }

    override fun close() {
        closed = true
        subscriptionWatch.stop()
        scope.cancel()
    }

    private fun rawKeyIfCapable(
        handle: SimHandle,
        capable: (SimControlInstance) -> Boolean,
    ): String? {
        if (closed) return null
        val key = handles.keyFor(handle) ?: return null
        val current = mutableState.value as? SimControlUiState.Ready ?: return null
        val instance = current.instances.firstOrNull { it.handle == handle } ?: return null
        return key.takeIf { capable(instance) }
    }

    private fun publish() {
        if (closed) return
        val inventory = latestInventory ?: return
        mutableState.value = when (inventory) {
            is SubscriptionInventoryResult.PermissionDenied ->
                SimControlEvaluator.permissionDenied(inventory.message)
            is SubscriptionInventoryResult.Failure ->
                SimControlEvaluator.failure(inventory.message)
            is SubscriptionInventoryResult.Active -> when (val engineState = latestEngine) {
                EngineControlUiState.Loading -> SimControlUiState.Loading
                is EngineControlUiState.PermissionDenied ->
                    SimControlEvaluator.permissionDenied(engineState.message)
                is EngineControlUiState.Failure ->
                    SimControlEvaluator.failure(engineState.message)
                is EngineControlUiState.Ready -> SimControlEvaluator.join(
                    inventory.subscriptions,
                    engineState.instances.map(::engineJoinInput),
                    handles,
                )
            }
        }
    }
}

private fun engineJoinInput(instance: EngineInstanceState) = SimEngineJoinInput(
    key = instance.iccid,
    lifecycle = instance.lifecycle,
    pid = instance.pid,
    runtime = instance.runtime,
    message = instance.message,
)
