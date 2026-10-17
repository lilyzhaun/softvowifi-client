package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.data.SimControlSource
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineRuntimeStatus
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FixtureSimControlSource(mode: String) : SimControlSource {
    private val mutableState = MutableStateFlow(fixture(mode))
    override val state: StateFlow<SimControlUiState> = mutableState.asStateFlow()
    private var closed = false

    override fun refresh() { readSnapshot() }
    override suspend fun refreshAndAwait(): RefreshResult = readSnapshot()
    override fun close() { closed = true }

    private fun readSnapshot(): RefreshResult {
        if (closed) return RefreshResult.Unsupported
        val snapshot = mutableState.value
        mutableState.value = snapshot
        return when (snapshot) {
            is SimControlUiState.Ready -> RefreshResult.Completed
            is SimControlUiState.Failure -> RefreshResult.Failed(snapshot.message)
            is SimControlUiState.PermissionDenied -> RefreshResult.Failed(snapshot.message)
            SimControlUiState.Loading -> RefreshResult.Unsupported
        }
    }

    override fun start(handle: SimHandle) {
        replace(handle) { instance ->
            if (!instance.canStart) instance else activeMatched(instance, EngineLifecycle.RUNNING, RUNTIME)
        }
    }

    override fun stop(handle: SimHandle) {
        replace(handle) { instance ->
            if (!instance.canStop) instance else when (instance.presence) {
                SimPresence.ACTIVE_MATCHED -> activeMatched(instance, EngineLifecycle.STOPPED)
                SimPresence.REMOVED_CONFIG_RETAINED -> instance.copy(
                    lifecycle = EngineLifecycle.STOPPED,
                    pid = null,
                    runtime = null,
                    canStart = false,
                    canStop = false,
                    canApply = true,
                )
                SimPresence.ACTIVE_UNCONFIGURED -> instance
            }
        }
    }

    private fun replace(handle: SimHandle, update: (SimControlInstance) -> SimControlInstance) {
        if (closed) return
        val current = mutableState.value as? SimControlUiState.Ready ?: return
        mutableState.value = current.copy(
            instances = current.instances.map { if (it.handle == handle) update(it) else it },
        )
    }

    private fun fixture(mode: String): SimControlUiState = when (mode) {
        "dual" -> SimControlUiState.Ready(listOf(unconfigured(), matched(SimHandle("sim-2"))))
        "provision" -> SimControlUiState.Ready(
            listOf(
                unconfigured(mcc = "460", mnc = "09", canProvision = true),
                unconfigured(
                    handle = SimHandle("sim-2"),
                    displayLabel = "SIM 2 · Lebara",
                    slotIndex = 1,
                    mcc = "505",
                    mnc = "03",
                    canProvision = true,
                ),
            ),
        )
        "matched-running" -> SimControlUiState.Ready(
            listOf(matched(SimHandle("sim-1"), EngineLifecycle.RUNNING, RUNTIME)),
        )
        "removed-running",
        "removed-stopped",
        -> SimControlUiState.Ready(emptyList())
        "empty" -> SimControlUiState.Ready(emptyList())
        "permission" -> SimControlUiState.PermissionDenied("当前权限无法读取 SIM 控制状态")
        "failure" -> SimControlUiState.Failure("无法读取 SIM 控制状态")
        "loading" -> SimControlUiState.Loading
        else -> throw IllegalArgumentException("unknown SIM fixture: $mode")
    }

    private fun unconfigured(
        handle: SimHandle = SimHandle("sim-1"),
        displayLabel: String = "SIM 1 · 未配置",
        slotIndex: Int = 0,
        mcc: String = "001",
        mnc: String = "01",
        canProvision: Boolean = false,
    ) = SimControlInstance(
        handle = handle,
        displayLabel = displayLabel,
        presence = SimPresence.ACTIVE_UNCONFIGURED,
        slotIndex = slotIndex,
        carrierLabel = "Fixture Carrier A",
        mcc = mcc,
        mnc = mnc,
        lifecycle = EngineLifecycle.CONFIG_MISSING,
        canStart = false,
        canStop = false,
        canApply = false,
        canProvision = canProvision,
    )

    private fun matched(
        handle: SimHandle,
        lifecycle: EngineLifecycle = EngineLifecycle.STOPPED,
        runtime: EngineRuntimeStatus? = null,
    ) = activeMatched(
        SimControlInstance(
            handle = handle,
            displayLabel = "SIM 2 · Fixture Carrier B",
            presence = SimPresence.ACTIVE_MATCHED,
            slotIndex = 1,
            carrierLabel = "Fixture Carrier B",
            mcc = "999",
            mnc = "99",
            lifecycle = lifecycle,
            canStart = false,
            canStop = false,
            canApply = false,
        ),
        lifecycle,
        runtime,
    )

    private fun activeMatched(
        instance: SimControlInstance,
        lifecycle: EngineLifecycle,
        runtime: EngineRuntimeStatus? = null,
    ) = instance.copy(
        lifecycle = lifecycle,
        pid = runtime?.let { 4242 },
        runtime = runtime,
        canStart = lifecycle == EngineLifecycle.STOPPED,
        canStop = lifecycle == EngineLifecycle.RUNNING,
        canApply = fixtureCanApply(SimPresence.ACTIVE_MATCHED, lifecycle),
    )

    private companion object {
        val RUNTIME = EngineRuntimeStatus(
            tunnel = "CONNECTED",
            tunnelDesc = "ready",
            reconnects = 0,
            ims = "REGISTERED",
            call = "IDLE",
            transport = "UDP",
            pcscf = emptyList(),
        )
    }
}

internal fun fixtureCanApply(presence: SimPresence, lifecycle: EngineLifecycle): Boolean =
    presence != SimPresence.ACTIVE_UNCONFIGURED &&
        (lifecycle == EngineLifecycle.STOPPED || lifecycle == EngineLifecycle.RUNNING)
