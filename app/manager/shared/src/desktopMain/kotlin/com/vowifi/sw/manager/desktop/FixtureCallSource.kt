package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.data.CallSource
import com.vowifi.sw.manager.model.CallAudioControls
import com.vowifi.sw.manager.model.CallAudioState
import com.vowifi.sw.manager.model.CallFailureMessages
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallOrigin
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.CallRecord
import com.vowifi.sw.manager.model.CallDirection
import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.CallPeerPhone
import com.vowifi.sw.manager.model.CallPeer
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CallFixtureMode(val wire: String) {
    IDLE("idle"),
    RECENTS("recents"),
    RINGING("ringing"),
    DIALING("dialing"),
    CONNECTING("connecting"),
    IN_CALL("incall"),
    AUDIO_FAILED("audio-failed"),
    LOADING("loading"),
    PERMISSION("permission"),
    UNAVAILABLE("unavailable"),
    FAILURE("failure"),
    ;

    companion object {
        fun fromWire(value: String): CallFixtureMode =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown calls fixture: $value")
    }
}

class FixtureCallSource(private val mode: CallFixtureMode, private val simulateControls: Boolean = false) : CallSource {
    constructor(mode: String) : this(CallFixtureMode.fromWire(mode))

    private val mutableState = MutableStateFlow(fixture(mode))
    override val state: StateFlow<CallUiState> = mutableState.asStateFlow()
    private val mutableHistory = MutableStateFlow<CallHistoryState>(if (mode == CallFixtureMode.RECENTS) CallHistoryState.Ready(listOf(
        CallRecord(CallDirection.OUTGOING, CallPeerPhone.parse("15550101"), 1788600000000,
            1788600001000, 1788600061000, 60, CallOutcome.COMPLETED),
        CallRecord(CallDirection.INCOMING, null, 1788500000000,
            null, 1788500020000, null, CallOutcome.MISSED),
    )) else if (simulateControls && mode == CallFixtureMode.IN_CALL) CallHistoryState.Ready(listOf(
        CallRecord(CallDirection.OUTGOING, CallPeerPhone.parse("15550100123"), Clock.System.now().toEpochMilliseconds() - 44_000,
            Clock.System.now().toEpochMilliseconds() - 42_000, null, null, CallOutcome.CONNECTED),
    )) else CallHistoryState.Empty)
    override val history: StateFlow<CallHistoryState> = mutableHistory.asStateFlow()
    override fun clearHistory(expected: CallHistoryState.Ready) {
        if (mutableHistory.value === expected) mutableHistory.value = CallHistoryState.Empty
    }
    override fun deleteHistory(expected: CallHistoryState.Ready, ids: Set<com.vowifi.sw.manager.model.CallHistoryRecordId>) {
        if (mutableHistory.value !== expected) return
        val remaining = expected.records.filterNot { it.recordId in ids }
        mutableHistory.value = if (remaining.isEmpty()) CallHistoryState.Empty else CallHistoryState.Ready(remaining, expected.owner)
    }
    private val mutableControls = MutableStateFlow(CallAudioControls())
    override val audioControls: StateFlow<CallAudioControls> = mutableControls.asStateFlow()

    override fun refresh() {
        mutableState.value = fixture(mode)
    }

    override fun answer() {
        val current = mutableState.value as? CallUiState.Ready ?: return
        if (current.phase == CallPhase.Ringing && !current.busy) {
            mutableState.value = current.copy(phase = CallPhase.Connecting, busy = true)
        }
    }

    override fun reject() {
        val current = mutableState.value as? CallUiState.Ready ?: return
        if (current.phase == CallPhase.Ringing && !current.busy) {
            mutableState.value = ready(CallPhase.Idle)
        }
    }

    override fun dial(number: String) {
        val current = mutableState.value as? CallUiState.Ready ?: return
        if (current.phase == CallPhase.Idle && !current.busy) {
            mutableState.value = ready(CallPhase.Dialing, busy = true)
        }
    }
    override fun dial(expected: com.vowifi.sw.manager.model.CallBinding, number: String) {
        if ((state.value as? CallUiState.Ready)?.binding === expected) dial(number)
    }

    override fun hangup() {
        val current = mutableState.value as? CallUiState.Ready ?: return
        if (current.phase == CallPhase.InCall && !current.busy) {
            mutableState.value = ready(CallPhase.Idle)
            mutableControls.value = CallAudioControls()
        }
    }

    override fun mute() {
        if (!inCall()) return
        mutableControls.value = mutableControls.value.toggledMute()
    }

    override fun cancel() {
        val current = mutableState.value as? CallUiState.Ready ?: return
        if (current.phase == CallPhase.Dialing) mutableState.value = current.copy(phase = CallPhase.Canceling)
    }
    override fun act(expected: com.vowifi.sw.manager.model.CallBinding, action: com.vowifi.sw.manager.model.CallAction) {
        if ((state.value as? CallUiState.Ready)?.binding !== expected) return
        when (action) {
            com.vowifi.sw.manager.model.CallAction.Answer -> answer()
            com.vowifi.sw.manager.model.CallAction.Reject -> reject()
            com.vowifi.sw.manager.model.CallAction.Hangup -> hangup()
            com.vowifi.sw.manager.model.CallAction.Cancel -> cancel()
        }
    }
    override fun sendDtmf(expected: com.vowifi.sw.manager.model.CallBinding, digit: Char) {
        val current = state.value as? CallUiState.Ready ?: return
        if (current.binding === expected) mutableState.value = current.copy(operationMessage = "桌面预览不发送按键")
    }

    override fun speaker() {
        if (!inCall()) return
        mutableControls.value = mutableControls.value.toggledSpeaker()
    }

    private fun inCall(): Boolean {
        val current = mutableState.value as? CallUiState.Ready ?: return false
        return current.phase == CallPhase.InCall
    }

    private fun fixture(value: CallFixtureMode): CallUiState = when (value) {
        CallFixtureMode.IDLE, CallFixtureMode.RECENTS -> ready(CallPhase.Idle)
        CallFixtureMode.RINGING -> ready(CallPhase.Ringing)
        CallFixtureMode.DIALING -> ready(CallPhase.Dialing, busy = true)
        CallFixtureMode.CONNECTING -> ready(CallPhase.Connecting, busy = true)
        CallFixtureMode.IN_CALL -> ready(CallPhase.InCall, CallAudioState.Active)
        CallFixtureMode.AUDIO_FAILED -> ready(CallPhase.InCall, CallAudioState.Failed(CallFailureMessages.AUDIO))
        CallFixtureMode.LOADING -> CallUiState.Loading
        CallFixtureMode.PERMISSION -> CallUiState.PermissionDenied(CallFailureMessages.PERMISSION)
        CallFixtureMode.UNAVAILABLE -> CallUiState.Unavailable("当前没有可用的引擎通话会话")
        CallFixtureMode.FAILURE -> CallUiState.Failure(CallFailureMessages.CONNECT)
    }

    private fun ready(
        phase: CallPhase,
        audio: CallAudioState = CallAudioState.Idle,
        busy: Boolean = false,
    ) = CallUiState.Ready(
        phase = phase,
        audio = audio,
        origin = if (simulateControls) CallOrigin.ENGINE else CallOrigin.DESKTOP_FIXTURE,
        busy = busy,
        peer = if (simulateControls && phase != CallPhase.Idle) CallPeer.parse("+15550100123") else null,
        operationMessage = if (simulateControls) "桌面合成通话演练，不连接手机或运营商。" else null,
    )
}
