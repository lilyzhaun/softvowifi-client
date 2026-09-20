package com.vowifi.sw.manager.model

import kotlinx.serialization.Serializable

enum class DiagnosticCheckId {
    MODULE,
    ROOT,
    CONFIG,
    OBSERVER,
    ENGINE,
    WFC,
}

enum class DiagnosticCheckStatus {
    PASS,
    WARN,
    FAIL,
}

data class DiagnosticCheck(
    val id: DiagnosticCheckId,
    val status: DiagnosticCheckStatus,
    val title: String,
    val detail: String,
    val guidance: String? = null,
)

sealed interface EnvironmentDiagnosticsUiState {
    data object Loading : EnvironmentDiagnosticsUiState

    data class Ready(
        val severity: DiagnosticCheckStatus,
        val checks: List<DiagnosticCheck>,
    ) : EnvironmentDiagnosticsUiState

    data class Failure(
        val message: String,
    ) : EnvironmentDiagnosticsUiState

    data class PermissionDenied(
        val message: String,
    ) : EnvironmentDiagnosticsUiState

    data class Unavailable(
        val message: String,
    ) : EnvironmentDiagnosticsUiState
}

class EnvironmentDiagnosticsFormatException(
    message: String,
) : IllegalArgumentException(message)

@Serializable
data class EnvironmentDiagnosticsSnapshot(
    val ok: Boolean,
    val module: ModuleDiagnostics,
    val root: RootDiagnostics,
    val config: ConfigDiagnostics,
    val observer: ObserverDiagnostics,
    val engine: EngineDiagnostics,
    val wfc: WfcDiagnostics? = null,
)

@Serializable
data class ModuleMarkers(
    val disable: Boolean,
    val remove: Boolean,
    val update: Boolean,
)

@Serializable
data class ModuleDiagnostics(
    val installed: Boolean,
    val id: String? = null,
    val version: String? = null,
    val versionCode: Int? = null,
    val markers: ModuleMarkers,
)

@Serializable
data class RootDiagnostics(
    val capable: Boolean,
)

@Serializable
data class ConfigDiagnostics(
    val count: Int,
)

@Serializable
data class ObserverDiagnostics(
    val packagePid: Boolean,
    val processRecord: Boolean,
    val foreground: Boolean,
)

@Serializable
data class EngineDiagnostics(
    val instanceCount: Int,
    val runningCount: Int,
    val stoppedCount: Int,
    val startingCount: Int,
    val stoppingCount: Int,
    val configMissingCount: Int,
)

@Serializable
data class WfcDiagnostics(
    val readable: Boolean,
    val global: Int? = null,
)
