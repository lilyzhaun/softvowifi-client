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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
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
    lineContent: (@Composable () -> Unit)? = null,
    currentLineLabel: String? = null,
    lineChoices: List<com.vowifi.sw.manager.data.CallLineChoice> = emptyList(),
    onOpenHistory: (com.vowifi.sw.manager.ui.navigation.CallHistoryHandle) -> Unit = {},
    callbackEnabled: Boolean = false,
    onCallback: (String) -> Unit = {},
    onDeleteRecords: (CallHistoryState.Ready, Set<com.vowifi.sw.manager.model.CallHistoryRecordId>) -> Unit = { _, _ -> },
    onRefresh: (() -> Unit)? = null,
    hasRegisteredEngine: Boolean = false,
) = PhoneStyle {
    var deletion by remember { mutableStateOf<Set<com.vowifi.sw.manager.model.CallHistoryRecordId>?>(null) }
    val routeActive = LocalAppRouteActive.current
    LaunchedEffect(history, routeActive) { deletion = null }
    val ready = state as? CallUiState.Ready
    val active = ready != null && ready.phase != CallPhase.Idle
    val historyOwner = (history as? CallHistoryState.Ready)?.owner
    var searching by remember(historyOwner) { mutableStateOf(false) }
    var query by remember(historyOwner) { mutableStateOf("") }
    val search = query.filterNot { it.isWhitespace() || it == '-' }
    val shownHistory = if (history is CallHistoryState.Ready && search.isNotEmpty())
        CallHistoryState.Ready(history.records.filter { it.peer?.digits()?.contains(search.removePrefix("+")) == true }, history.owner) else history
    val actions = CallActions(
        onRequestMicrophonePermission,
        onAnswer,
        onReject,
        onHangup,
        onMute,
        onSpeaker,
    )
    CommunicationFeedbackHost {
    PhoneHistoryScaffold(searching, query, { searching = !searching; query = "" }, { query = it },
        { searching = false; query = "" }, showDial = !active && hasRegisteredEngine, onDial = onOpenDialpad) {
             if (!active) lineContent?.let { item(key = "outgoing-line") { it() } }
            if (state is CallUiState.Ready && state.origin == CallOrigin.DESKTOP_FIXTURE) {
                item(key = "fixture-disclosure") { CallsFixtureDisclosureBand() }
            }
            if (state !is CallUiState.Ready) callStatePanelSpec(state)?.let { panel ->
                item(key = "calls-state") { CommunicationListState(panel) {
                    if (canRefreshCallState(state)) onRefresh?.let { CommunicationTextAction("刷新通话状态", it) }
                } }
            }
            if (state is CallUiState.Ready && active) {
                item(key = "calls-session") {
                    currentLineLabel?.let { PhoneText("当前通话 · $it", size = 14, secondary = true) }
                    if (onOpenCall != null) OngoingCallAffordance("${callSessionStatusTitle(state)} · 返回通话", onOpenCall)
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
                ready?.operationMessage?.let { message -> item { CommunicationNotice(message) } }
                if (ready != null && canRefreshCallState(state)) onRefresh?.let {
                    item(key = "calls-refresh") { CommunicationTextAction("刷新通话状态", it) }
                }
            }
             if (!active) {
                 if (search.isNotEmpty() && shownHistory is CallHistoryState.Ready && shownHistory.records.isEmpty()) {
                     item(key = "calls-search-empty") { CommunicationListState(StatePanelSpec(CommunicationSearch,
                         StatePanelTone.NEUTRAL, "没有匹配的通话记录", "试试其他号码，或清空搜索")) }
                 } else callsHistoryContent(shownHistory, onOpenHistory, callbackEnabled, onCallback, { deletion = it })
             }
    }
    deletion?.takeIf { routeActive }?.let { ids -> (history as? CallHistoryState.Ready)?.let { ready ->
        CallHistoryDeleteConfirmation(ready, ids, false, { deletion = null }, onDeleteRecords)
    } }
    }
}
