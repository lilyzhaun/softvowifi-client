package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.data.EnvironmentDiagnosticsEvaluator
import com.vowifi.sw.manager.data.EnvironmentDiagnosticsSource
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.ConfigDiagnostics
import com.vowifi.sw.manager.model.EngineDiagnostics
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsSnapshot
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.ModuleDiagnostics
import com.vowifi.sw.manager.model.ModuleMarkers
import com.vowifi.sw.manager.model.ObserverDiagnostics
import com.vowifi.sw.manager.model.RootDiagnostics
import com.vowifi.sw.manager.model.StockWfcCard
import com.vowifi.sw.manager.model.StockWfcDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FixtureEnvironmentDiagnosticsSource(mode: String) : EnvironmentDiagnosticsSource {
    private val mutableState = MutableStateFlow(fixture(mode))
    override val state: StateFlow<EnvironmentDiagnosticsUiState> = mutableState.asStateFlow()
    private var closed = false

    override fun refresh() {
        refreshSnapshot()
    }

    override suspend fun refreshAndAwait(): RefreshResult = refreshSnapshot()

    private fun refreshSnapshot(): RefreshResult {
        if (closed) return RefreshResult.Unsupported
        mutableState.value = ready()
        return RefreshResult.Completed
    }

    override fun close() { closed = true }

    private fun fixture(mode: String): EnvironmentDiagnosticsUiState = when (mode) {
        "loading" -> EnvironmentDiagnosticsUiState.Loading
        "warning" -> warning()
        "failure" -> EnvironmentDiagnosticsEvaluator.failure("样例诊断结果无法解析")
        "permission" -> EnvironmentDiagnosticsEvaluator.permissionDenied("KernelSU 尚未授权")
        "unavailable" -> EnvironmentDiagnosticsEvaluator.unavailable("当前模块不支持环境诊断")
        else -> ready()
    }

    private fun ready(): EnvironmentDiagnosticsUiState = EnvironmentDiagnosticsEvaluator.ready(
        EnvironmentDiagnosticsSnapshot(
            ok = true,
            module = ModuleDiagnostics(
                true,
                version = "1.0.0",
                markers = ModuleMarkers(disable = false, remove = false, update = false),
            ),
            root = RootDiagnostics(true),
            config = ConfigDiagnostics(2),
            observer = ObserverDiagnostics(true, true, true),
            engine = EngineDiagnostics(
                instanceCount = 2,
                runningCount = 1,
                stoppedCount = 1,
                startingCount = 0,
                stoppingCount = 0,
                configMissingCount = 0,
            ),
        ),
        StockWfcDiagnostics.Readable(listOf(StockWfcCard(slot = 0, enabled = false), StockWfcCard(slot = 1, enabled = false))),
    )

    private fun warning(): EnvironmentDiagnosticsUiState = EnvironmentDiagnosticsEvaluator.ready(
        EnvironmentDiagnosticsSnapshot(
            ok = true,
            module = ModuleDiagnostics(
                true,
                version = "1.0.0",
                markers = ModuleMarkers(disable = true, remove = false, update = false),
            ),
            root = RootDiagnostics(true),
            config = ConfigDiagnostics(0),
            observer = ObserverDiagnostics(true, true, false),
            engine = EngineDiagnostics(
                instanceCount = 0,
                runningCount = 0,
                stoppedCount = 0,
                startingCount = 0,
                stoppingCount = 0,
                configMissingCount = 0,
            ),
        ),
        StockWfcDiagnostics.Readable(listOf(StockWfcCard(slot = 0, enabled = false), StockWfcCard(slot = 1, enabled = true))),
    )
}
