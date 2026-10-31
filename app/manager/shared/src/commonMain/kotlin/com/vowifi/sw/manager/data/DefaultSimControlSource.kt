package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimEngineJoinInput
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.CallUiState
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val lock = Any()
    private var refreshInFlight: CompletableDeferred<RefreshResult>? = null
    private var inventoryGeneration = 0L
    private val mutableState = MutableStateFlow<SimControlUiState>(SimControlUiState.Loading)
    override val state: StateFlow<SimControlUiState> = mutableState.asStateFlow()

    @Volatile
    private var closed = false

    @Volatile
    private var latestInventory: SubscriptionInventoryResult? = null

    private val subscriptionWatch = inventory.startWatching(::onSubscriptionsChanged)

    private fun onSubscriptionsChanged() {
        synchronized(lock) {
            if (closed) return
            inventoryGeneration++
            latestInventory = null
            mutableState.value = SimControlUiState.Loading
        }
        refresh()
    }

    init {
        scope.launch {
            engine.state.collect {
                synchronized(lock) { publish() }
            }
        }
        refresh()
    }

    override fun refresh() {
        requestRefresh()
    }

    override suspend fun refreshAndAwait(): RefreshResult {
        val result = requestRefresh().await()
        // A user may have joined an older automatic inventory job; use the engine's value now.
        synchronized(lock) { publish() }
        return result
    }

    private fun requestRefresh(): Deferred<RefreshResult> {
        var producer: Job? = null
        val accepted = synchronized(lock) {
            if (closed) return@synchronized CompletableDeferred(RefreshResult.Unsupported)
            refreshInFlight?.let { return@synchronized it }
            if (!operationGate.tryEnter()) return@synchronized CompletableDeferred(RefreshResult.Busy)
            val completion = CompletableDeferred<RefreshResult>()
            val generation = inventoryGeneration
            refreshInFlight = completion
            var outcome: RefreshResult? = null
            producer = scope.launch(start = CoroutineStart.LAZY) {
                val result = inventory.listActive()
                currentCoroutineContext().ensureActive()
                val current = synchronized(lock) {
                    if (!closed && generation == inventoryGeneration) {
                        if (result is SubscriptionInventoryResult.Active) handles.reconcileActive(result.subscriptions)
                        latestInventory = result
                        publish()
                        true
                    } else false
                }
                outcome = if (!current) RefreshResult.Cancelled else when (result) {
                    is SubscriptionInventoryResult.Active -> RefreshResult.Completed
                    is SubscriptionInventoryResult.PermissionDenied -> RefreshResult.Failed("SIM 控制权限不足")
                    is SubscriptionInventoryResult.Failure -> RefreshResult.Failed("无法读取活动订阅")
                }
            }.also { job ->
                job.invokeOnCompletion { error ->
                    val changed = synchronized(lock) {
                        refreshInFlight = null
                        operationGate.release()
                        !closed && generation != inventoryGeneration
                    }
                    when (error) {
                        null -> completion.complete(checkNotNull(outcome))
                        is CancellationException -> completion.cancel(error)
                        else -> completion.completeExceptionally(error)
                    }
                    if (changed) refresh()
                }
            }
            completion
        }
        producer?.start()
        return accepted
    }

    override fun start(handle: SimHandle) {
        val key = rawKeyIfCapable(handle) { it.canStart } ?: return
        engine.start(key)
    }

    override fun stop(handle: SimHandle) {
        val key = rawKeyIfCapable(handle) { it.canStop } ?: return
        engine.stop(key)
    }

    override fun activeCallSims(currentCall: CallUiState): Map<SimHandle, Int?> =
        activeCallSimsFor(state.value, currentCall, handles::keyFor)

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            refreshInFlight?.cancel()
        }
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
            is SubscriptionInventoryResult.Active -> when (val engineState = engine.state.value) {
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
    operationPending = instance.operationPending,
    controlCompletion = instance.controlCompletion,
)
