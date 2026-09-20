package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallAudioControls
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal val IdleCallAudioControls: StateFlow<CallAudioControls> =
    MutableStateFlow(CallAudioControls()).asStateFlow()

interface CallSource {
    val state: StateFlow<CallUiState>
    val history: StateFlow<CallHistoryState>
    val historyOwners: StateFlow<List<CallHistoryOwnerChoice>>
        get() = EmptyCallHistoryOwners
    fun selectHistoryOwner(choice: CallHistoryOwnerChoice) = Unit
    val audioControls: StateFlow<CallAudioControls>
        get() = IdleCallAudioControls
    fun refresh()
    fun answer()
    fun reject()
    fun hangup()
    fun cancel()
    fun act(expected: com.vowifi.sw.manager.model.CallBinding, action: com.vowifi.sw.manager.model.CallAction)
    fun sendDtmf(expected: com.vowifi.sw.manager.model.CallBinding, digit: Char)
    fun dial(number: String)
    fun dial(expected: com.vowifi.sw.manager.model.CallBinding, number: String)
    fun clearHistory(expected: CallHistoryState.Ready)
    fun mute() = Unit
    fun speaker() = Unit
    fun close() = Unit
}

private val EmptyCallHistoryOwners: StateFlow<List<CallHistoryOwnerChoice>> = MutableStateFlow(emptyList())

interface CallAudioController {
    val events: SharedFlow<CallAudioEvent>
    val controls: StateFlow<CallAudioControls>
        get() = IdleCallAudioControls
    suspend fun start(session: CallAudioSession)
    fun stop()
    fun toggleMuted() = Unit
    fun toggleSpeaker() = Unit
}

data class CallAudioSession(
    val iccid: String,
    val token: String,
    val generation: Long,
    val transport: EngineMessageTransport,
)

sealed interface CallAudioEvent {
    data class Ended(val session: CallAudioSession) : CallAudioEvent
}
