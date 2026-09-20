package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.SimControlUiState
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class FakeSubscriptionInventory(
    var result: SubscriptionInventoryResult = SubscriptionInventoryResult.Active(emptyList()),
    var registerListener: Boolean = true,
) : SubscriptionInventory {
    val events = mutableListOf<String>()
    var listActiveCalls = 0
    var startWatchingCalls = 0
    var stopWatchingCalls = 0
    private val watchers = mutableListOf<Watcher>()
    val onChanged: (() -> Unit)?
        get() = watchers.lastOrNull()?.onChanged

    override fun listActive(): SubscriptionInventoryResult {
        listActiveCalls += 1
        events += "listActive"
        return result
    }

    override fun startWatching(onChanged: () -> Unit): SubscriptionWatch {
        startWatchingCalls += 1
        events += "startWatching"
        val watcher = if (registerListener) Watcher(onChanged).also(watchers::add) else null
        var stopped = false
        return SubscriptionWatch {
            if (stopped) return@SubscriptionWatch
            stopped = true
            if (watcher != null) watchers.remove(watcher)
            stopWatchingCalls += 1
            events += "stopWatching"
        }
    }

    fun emitChange() {
        watchers.toList().forEach { it.onChanged() }
    }

    private class Watcher(val onChanged: () -> Unit)
}

internal class FakeEngineControlSource(
    initial: EngineControlUiState = EngineControlUiState.Loading,
) : EngineControlSource {
    private val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<EngineControlUiState> = mutableState.asStateFlow()
    val started = mutableListOf<String>()
    val stopped = mutableListOf<String>()
    var refreshCalls = 0

    override fun refresh() {
        refreshCalls += 1
    }

    override fun start(iccid: String) {
        started += iccid
    }

    override fun stop(iccid: String) {
        stopped += iccid
    }

    fun emit(value: EngineControlUiState) {
        mutableState.value = value
    }
}

internal fun simControlSource(
    inventory: FakeSubscriptionInventory,
    engine: FakeEngineControlSource,
    handles: OpaqueIccidHandleStore = OpaqueIccidHandleStore(),
    parentContext: CoroutineContext,
): DefaultSimControlSource = DefaultSimControlSource(
    inventory = inventory,
    engine = engine,
    handles = handles,
    parentContext = parentContext,
)

internal fun engineReady(vararg instances: EngineInstanceState): EngineControlUiState.Ready =
    EngineControlUiState.Ready(instances.toList())

internal fun engineRow(
    key: String,
    lifecycle: EngineLifecycle,
    pid: Long? = null,
): EngineInstanceState = EngineInstanceState(
    iccid = key,
    lifecycle = lifecycle,
    pid = pid,
)

internal fun readyInstances(state: SimControlUiState) =
    (state as SimControlUiState.Ready).instances
