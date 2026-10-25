package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.More
import com.vowifi.sw.manager.model.CallAudioControls
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallHistoryViewSnapshot
import com.vowifi.sw.manager.model.CallHistoryDeleteResult
import com.vowifi.sw.manager.model.CallHistoryRecordId
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
    onEntriesObscuredChange: (Boolean) -> Unit = {},
    historySnapshot: CallHistoryViewSnapshot? = null,
    onDeleteRecordsAndAwait: (suspend (CallHistoryState.Ready, Set<CallHistoryRecordId>) -> CallHistoryDeleteResult)? = null,
    onRetryHistory: (() -> Unit)? = null,
    showUnregisteredNotice: Boolean = false,
    onGoHome: (() -> Unit)? = null,
) = PhoneStyle {
    val routeActive = LocalAppRouteActive.current
    val confirmedHistory = historySnapshot?.view ?: history
    var lastHistory by remember { mutableStateOf<CallHistoryState?>(null) }
    var lastChange by remember { mutableStateOf(historySnapshot?.change) }
    SideEffect {
        if (confirmedHistory is CallHistoryState.Ready || confirmedHistory == CallHistoryState.Empty) {
            lastHistory = confirmedHistory; lastChange = historySnapshot?.change
        }
    }
    val loading = rememberManagerLoadingState(confirmedHistory == CallHistoryState.Loading, lastHistory != null)
    val displayedHistory = if (confirmedHistory == CallHistoryState.Loading) lastHistory ?: confirmedHistory else confirmedHistory
    val displayedChange = if (confirmedHistory == CallHistoryState.Loading && lastHistory != null) lastChange else historySnapshot?.change
    val historyEnabled = confirmedHistory != CallHistoryState.Loading && !loading.showLoading && !loading.hidden
    val ready = state as? CallUiState.Ready
    val active = ready != null && ready.phase != CallPhase.Idle
    var entriesObscured by remember { mutableStateOf(false) }
    var retainedCall by remember { mutableStateOf(ready?.takeIf { active }) }
    val availability = if (state is CallUiState.Ready) null else callStatePanelSpec(state)?.let {
        it to (canRefreshCallState(state) && onRefresh != null)
    }
    var retainedAvailability by remember { mutableStateOf(availability) }
    SideEffect {
        if (active) retainedCall = ready
        if (availability != null) retainedAvailability = availability
    }
    val historyOwner = historySnapshot?.owners?.singleOrNull { it.selected }?.owner
        ?: (displayedHistory as? CallHistoryState.Ready)?.owner
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    OnConfirmedManagerOwnerChange(historyOwner) { searching = false; query = "" }
    val search = query.filterNot { it.isWhitespace() || it == '-' }
    val selection = Triple(historyOwner, query, displayedChange?.scope)
    var deletion by remember(selection, routeActive) { mutableStateOf<Set<CallHistoryRecordId>?>(null) }
    LaunchedEffect(confirmedHistory, routeActive) { deletion = null }
    val deletionUi = rememberCallsHistoryDeleteUi(selection, routeActive, onDeleteRecordsAndAwait, onDeleteRecords)
    val groups = remember(displayedHistory, search) {
        (displayedHistory as? CallHistoryState.Ready)?.let(::callHistoryGroups).orEmpty()
            .filter { search.isEmpty() || it.peer?.digits()?.contains(search.removePrefix("+")) == true }
    }
    val rows = rememberCallsHistoryRows(groups, displayedChange, selection, routeActive,
        key = { it.handle.value }, ids = { it.ids }, pending = historySnapshot?.deletePending.orEmpty())
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
        { searching = false; query = "" }, showDial = !active && hasRegisteredEngine, onDial = onOpenDialpad,
        owner = historyOwner, onEntriesObscuredChange = {
            entriesObscured = it
            onEntriesObscuredChange(it)
        }, historyChange = displayedChange) {
              if (!active) lineContent?.let { item(key = "outgoing-line") { it() } }
            unregisteredCommunicationNotice("calls-unregistered", showUnregisteredNotice && !active &&
                availability == null && ready?.operationMessage == null && confirmedHistory != CallHistoryState.Loading,
                "暂无已注册线路，暂时不能拨号", onGoHome)
            if (state is CallUiState.Ready && state.origin == CallOrigin.DESKTOP_FIXTURE) {
                item(key = "fixture-disclosure") { CallsFixtureDisclosureBand() }
            }
            communicationNoticeItem("calls-state", availability?.first?.let { CommunicationNoticeSpec(it.title, it.description) }) { _, interactive ->
                val shown = availability ?: retainedAvailability
                if (shown != null) CommunicationListState(shown.first) {
                    if (shown.second) {
                        if (interactive && onRefresh != null) CommunicationTextAction("刷新通话状态", onRefresh)
                        else Box(Modifier.heightIn(min = 48.dp).padding(12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text("刷新通话状态", color = communicationColors().accent, fontSize = 17.sp, lineHeight = 24.sp)
                        }
                    }
                }
            }
            item(key = "calls-session") {
                if (onOpenCall != null) CommunicationEntryVisibility(active && !entriesObscured) { hitEnabled ->
                    Column {
                        currentLineLabel?.let { PhoneText("当前通话 · $it", size = 14, secondary = true) }
                        (ready?.takeIf { active } ?: retainedCall)?.let {
                            OngoingCallAffordance(it, hitEnabled = hitEnabled, onClick = onOpenCall)
                        }
                    }
                } else if (state is CallUiState.Ready && active) {
                    Column {
                        currentLineLabel?.let { PhoneText("当前通话 · $it", size = 14, secondary = true) }
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
            communicationNoticeItem("calls-operation-notice", ready?.operationMessage?.takeIf { !active }
                ?.let { CommunicationNoticeSpec(it) })
            if (!active) {
                if (ready != null && canRefreshCallState(state)) onRefresh?.let {
                    item(key = "calls-refresh") { CommunicationTextAction("刷新通话状态", it) }
                }
            }
             if (!active) {
                    if (historyEnabled && search.isNotEmpty() && displayedHistory is CallHistoryState.Ready && rows.rows.isEmpty()) {
                     item(key = "calls-search-empty") { CommunicationListState(StatePanelSpec(CommunicationSearch,
                         StatePanelTone.NEUTRAL, "没有匹配的通话记录", "试试其他号码，或清空搜索")) }
                    } else callsHistoryContent(displayedHistory, onOpenHistory, callbackEnabled, onCallback,
                      { deletionUi.clearMessage(); deletion = it }, rows = rows,
                       pending = historySnapshot?.deletePending.orEmpty(), deletionUi = deletionUi,
                        rootCustomActionsBlocked = deletion != null, loading = loading, historyEnabled = historyEnabled,
                        onRetry = onRetryHistory)
             }
    }
    deletion?.takeIf { routeActive }?.let { ids -> (confirmedHistory as? CallHistoryState.Ready)?.let { ready ->
        CallHistoryDeleteConfirmation(ready, ids, false, { deletion = null }, deletionUi.submit)
    } }
    }
}
