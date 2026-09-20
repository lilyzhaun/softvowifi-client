package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogMessage
import com.vowifi.sw.manager.model.OperationalLogUiState
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence

internal fun settingsSnapshot(
    configuration: ConfigurationUiState = ConfigurationUiState.Ready(listOf(configurationInstance("sim-1"))),
    sims: SimControlUiState = SimControlUiState.Ready(listOf(simInstance("sim-1", SimPresence.ACTIVE_MATCHED))),
    diagnostics: EnvironmentDiagnosticsUiState = EnvironmentDiagnosticsUiState.Ready(
        DiagnosticCheckStatus.PASS,
        emptyList(),
    ),
    logs: OperationalLogUiState = readyOperationalLogs(1),
    notification: CallNotificationSettingsSnapshot = CallNotificationSettingsSnapshot(
        NotificationPermissionStatus.DENIED,
        FullScreenStatus.ALLOWED,
    ),
    appVersion: String = "fixture-desktop",
    exportBusy: Boolean = false,
) = SettingsCatalogSnapshot(configuration, sims, diagnostics, logs, notification, appVersion, exportBusy)

internal fun configurationInstance(id: String) = ConfigurationInstance(
    ConfigurationInstanceId(id),
    "SIM 配置",
    ConfigurationLifecycle.STOPPED,
    ConfigurationTransport.AUTO,
    "",
    "",
    "",
    "",
    "epdg.example.invalid",
    "ims.example.invalid",
    "001",
    "01",
)

internal fun simInstance(
    id: String,
    presence: SimPresence,
    canApply: Boolean = true,
    canProvision: Boolean = false,
) = SimControlInstance(
    SimHandle(id),
    "SIM 配置",
    presence,
    0,
    null,
    "001",
    "01",
    EngineLifecycle.STOPPED,
    canStart = false,
    canStop = false,
    canApply = canApply,
    canProvision = canProvision,
)

internal fun readyOperationalLogs(count: Int) = OperationalLogUiState.Ready(
    List(count) { index ->
        OperationalLogEntry(
            "bounded-$index",
            OperationalLogLevel.INFO,
            OperationalLogKind.ENGINE,
            "实例 ••••",
            OperationalLogMessage.ENGINE_STARTED,
            "engine started",
        )
    },
)
