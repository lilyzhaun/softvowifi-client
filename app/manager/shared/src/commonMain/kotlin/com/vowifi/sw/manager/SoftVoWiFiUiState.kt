package com.vowifi.sw.manager

import com.vowifi.sw.manager.data.CallHistoryOwnerChoice
import com.vowifi.sw.manager.data.CallLineState
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.AppearanceSettings

internal data class SoftVoWiFiContentState(
    val observer: RegistrationUiState,
    val simControl: SimControlUiState,
    val configuration: ConfigurationUiState,
    val diagnostics: EnvironmentDiagnosticsUiState,
    val logs: OperationalLogUiState,
    val messages: MessageUiState,
    val calls: CallUiState,
    val callLines: CallLineState,
    val callHistory: CallHistoryState,
    val callAudio: CallAudioControls,
    val appearance: AppearanceSettings,
    val callHistorySnapshot: CallHistoryViewSnapshot? = null,
    val callHistoryOwners: List<CallHistoryOwnerChoice> = emptyList(),
)
