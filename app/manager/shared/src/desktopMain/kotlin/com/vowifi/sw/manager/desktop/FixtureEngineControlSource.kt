package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.data.EngineControlSource
import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineRuntimeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FixtureEngineControlSource(mode: String) : EngineControlSource {
    private val mutableState = MutableStateFlow(fixture(mode))
    override val state: StateFlow<EngineControlUiState> = mutableState.asStateFlow()

    override fun refresh() = Unit

    override fun start(iccid: String) {
        replace(iccid, EngineLifecycle.RUNNING, RUNTIME)
    }

    override fun stop(iccid: String) {
        replace(iccid, EngineLifecycle.STOPPED, null)
    }

    private fun replace(iccid: String, lifecycle: EngineLifecycle, runtime: EngineRuntimeStatus?) {
        val current = mutableState.value
        if (current !is EngineControlUiState.Ready) return
        mutableState.value = current.copy(
            instances = current.instances.map { instance ->
                if (instance.iccid == iccid) {
                    instance.copy(lifecycle = lifecycle, pid = runtime?.let { 4242 }, runtime = runtime)
                } else {
                    instance
                }
            },
        )
    }

    private fun fixture(mode: String): EngineControlUiState = when (mode) {
        "permission" -> EngineControlUiState.PermissionDenied("KernelSU 尚未授权")
        "engine-error" -> EngineControlUiState.Failure("无法连接引擎控制 socket")
        "no-config" -> EngineControlUiState.Ready(emptyList())
        "starting" -> ready(EngineLifecycle.STARTING)
        "running", "data" -> ready(EngineLifecycle.RUNNING, RUNTIME)
        else -> ready(EngineLifecycle.STOPPED)
    }

    private fun ready(
        lifecycle: EngineLifecycle,
        runtime: EngineRuntimeStatus? = null,
    ) = EngineControlUiState.Ready(
        listOf(
            EngineInstanceState(
                iccid = ICCID,
                lifecycle = lifecycle,
                pid = runtime?.let { 4242 },
                runtime = runtime,
            ),
        ),
    )

    private companion object {
        const val ICCID = "8986000000000000001"
        val RUNTIME = EngineRuntimeStatus(
            tunnel = "CONNECTED",
            tunnelDesc = "ready",
            reconnects = 2,
            ims = "REGISTERED",
            call = "IDLE",
            transport = "UDP",
            pcscf = listOf("10.0.0.1"),
        )
    }
}
