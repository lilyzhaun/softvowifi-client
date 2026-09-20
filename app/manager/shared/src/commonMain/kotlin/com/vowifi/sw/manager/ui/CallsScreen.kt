package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.More
import com.vowifi.sw.manager.model.CallAudioControls
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallOrigin
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.ui.navigation.AppRoute

internal data class CallActions(
    val requestMicrophonePermission: () -> Unit,
    val answer: () -> Unit,
    val reject: () -> Unit,
    val hangup: () -> Unit,
    val mute: () -> Unit,
    val speaker: () -> Unit,
)

@Composable
fun CallsScreen(
    state: CallUiState,
    microphonePermissionGranted: Boolean,
    onRequestMicrophonePermission: () -> Unit,
    history: CallHistoryState,
    onOpenDialpad: () -> Unit,
    onClearHistory: (CallHistoryState.Ready) -> Unit,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onHangup: () -> Unit,
    audioControls: CallAudioControls = CallAudioControls(),
    onMute: () -> Unit = {},
    onSpeaker: () -> Unit = {},
    historyOwners: List<com.vowifi.sw.manager.data.CallHistoryOwnerChoice> = emptyList(),
    onSelectHistoryOwner: (com.vowifi.sw.manager.data.CallHistoryOwnerChoice) -> Unit = {},
    onOpenCall: (() -> Unit)? = null,
) {
    var clearConfirmation by remember { mutableStateOf<CallHistoryState.Ready?>(null) }
    var showHistoryActions by remember { mutableStateOf(false) }
    val ready = state as? CallUiState.Ready
    val active = ready != null && ready.phase != CallPhase.Idle
    LaunchedEffect(history, active) { clearConfirmation = null }
    val actions = CallActions(
        onRequestMicrophonePermission,
        onAnswer,
        onReject,
        onHangup,
        onMute,
        onSpeaker,
    )
    AppPageScaffold(title = appRootTitle(AppRoute.Calls), actions = {
        if (!active) {
            ManagerIconAction(MiuixIcons.Phone, "拨号盘", ManagerAction.Navigate, onOpenDialpad)
            if (history is CallHistoryState.Ready && history.records.isNotEmpty()) {
                ManagerIconAction(MiuixIcons.More, "通话记录选项", ManagerAction.ContextAction,
                    { showHistoryActions = !showHistoryActions })
            }
        }
    }) {
            if (state is CallUiState.Ready && state.origin == CallOrigin.DESKTOP_FIXTURE) {
                item(key = "fixture-disclosure") { CallsFixtureDisclosureBand() }
            }
            if (state !is CallUiState.Ready) callStatePanelSpec(state)?.let { panel ->
                item(key = "calls-state") { CommunicationNotice(panel.title, panel.description) }
            }
            if (state is CallUiState.Ready && active) {
                item(key = "calls-session") {
                    if (onOpenCall != null) OngoingCallAffordance("${callPhaseTitle(state.phase)} · 返回通话", onOpenCall)
                    else {
                    CallSessionCard(
                        state = state,
                        peerDisplay = activeCallPeerDisplay(state),
                        microphonePermissionGranted = microphonePermissionGranted,
                        audioControls = audioControls,
                        actions = actions,
                    )
                    }
                }
            }
            if (!active) {
                if (historyOwners.size > 1) {
                    item {
                        Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${historyOwners.singleOrNull { it.selected }?.label.orEmpty()} · 本地记录，线路编号不代表 SIM 槽位")
                            if (ready == null) historyOwners.filterNot { it.selected }.forEach { choice ->
                                ManagerTextAction("查看${choice.label}", ManagerAction.SegmentSelection,
                                    { clearConfirmation = null; onSelectHistoryOwner(choice) })
                            }
                        }
                    }
                }
                ready?.operationMessage?.let { message -> item { CommunicationNotice(message) } }
                val confirmation = clearConfirmation
                if (confirmation != null) {
                    item {
                        ClearCallHistoryConfirmation(
                            onCancel = { clearConfirmation = null },
                            onConfirm = { clearConfirmation = null; onClearHistory(confirmation) },
                        )
                    }
                } else if (showHistoryActions && history is CallHistoryState.Ready && history.records.isNotEmpty()) {
                    item {
                        ManagerTextAction("清除通话记录", ManagerAction.ContextAction,
                            { showHistoryActions = false; clearConfirmation = history }, Modifier.padding(bottom = 12.dp))
                    }
                }
            }
            if (!active) callsHistoryContent(history)
    }
}
