package com.vowifi.sw.manager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.vowifi.sw.manager.data.OperationalLogExporter
import com.vowifi.sw.manager.model.CallAudioControls
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.ui.CallsDialpadScreen
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OperationalLogUiState
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.ui.CallsScreen
import com.vowifi.sw.manager.ui.AppearanceSettings
import com.vowifi.sw.manager.ui.HomeScreen
import com.vowifi.sw.manager.ui.MessageThreadScreen
import com.vowifi.sw.manager.ui.MessagesScreen
import com.vowifi.sw.manager.ui.NewMessageScreen
import com.vowifi.sw.manager.ui.SettingsScreen
import com.vowifi.sw.manager.ui.SettingsAppearanceScreen
import com.vowifi.sw.manager.ui.SettingsConfigurationActions
import com.vowifi.sw.manager.ui.SettingsConfigurationScreen
import com.vowifi.sw.manager.ui.SettingsDiagnosticsScreen
import com.vowifi.sw.manager.ui.SettingsLogsActions
import com.vowifi.sw.manager.ui.SettingsLogsScreen
import com.vowifi.sw.manager.ui.SettingsCallNotificationsScreen
import com.vowifi.sw.manager.ui.SettingsAboutScreen
import com.vowifi.sw.manager.ui.SettingsCatalogSnapshot
import com.vowifi.sw.manager.ui.settingsCatalog
import com.vowifi.sw.manager.ui.navigation.AppRoute

internal data class SoftVoWiFiContentState(
    val observer: RegistrationUiState,
    val simControl: SimControlUiState,
    val configuration: ConfigurationUiState,
    val diagnostics: EnvironmentDiagnosticsUiState,
    val logs: OperationalLogUiState,
    val messages: MessageUiState,
    val calls: CallUiState,
    val callLines: com.vowifi.sw.manager.data.CallLineState,
    val callHistory: CallHistoryState,
    val callAudio: CallAudioControls,
    val appearance: AppearanceSettings,
)

internal data class SoftVoWiFiContentActions(
    val sources: SoftVoWiFiSources,
    val microphonePermissionGranted: Boolean,
    val requestMicrophonePermission: () -> Unit,
    val exportLogs: (String) -> Unit,
    val exportBusy: Boolean,
    val appVersion: String,
    val refreshHome: () -> Unit,
    val messageDraft: com.vowifi.sw.manager.ui.BoundMessageDraft,
    val callDraft: com.vowifi.sw.manager.ui.CallDialDraft,
    val onCallDraftChange: (String) -> Unit,
    val onNavigate: (AppRoute.Subpage) -> Unit,
    val onOpenHomeNotice: (AppRoute.Subpage) -> Unit,
    val blurSupported: Boolean,
    val appearanceActions: com.vowifi.sw.manager.ui.AppearanceSettingsActions,
    val configurationDrafts: com.vowifi.sw.manager.ui.ConfigurationDraftStore = com.vowifi.sw.manager.ui.ConfigurationDraftStore(),
    val callDial: ((com.vowifi.sw.manager.model.CallBinding, String) -> Unit)? = null,
    val openCall: (() -> Unit)? = null,
    val callLineLabel: String = "当前绑定线路",
    val callPermissions: (() -> Unit)? = null,
) {
    override fun toString(): String = "SoftVoWiFiContentActions"
}

@Composable
internal fun SoftVoWiFiDestinationContent(
    route: AppRoute,
    state: SoftVoWiFiContentState,
    actions: SoftVoWiFiContentActions,
) {
    val sources = actions.sources
    val callNotificationSnapshot = sources.callNotificationSettings.state.collectAsState().value
    when (route) {
        AppRoute.Home -> HomeScreen(
            state = state.observer,
            engineState = state.simControl,
            diagnosticsState = state.diagnostics,
            onRefresh = actions.refreshHome,
            onStartEngine = sources::startSim,
            onStopEngine = sources::stopSim,
            onProvision = sources::provisionSim,
            onOpenNotice = actions.onOpenHomeNotice,
        )
        AppRoute.Calls -> CallsScreen(
            state = state.calls,
            microphonePermissionGranted = actions.microphonePermissionGranted,
            onRequestMicrophonePermission = actions.requestMicrophonePermission,
            history = state.callHistory,
            historyOwners = sources.calls.historyOwners.collectAsState().value,
            onSelectHistoryOwner = sources.calls::selectHistoryOwner,
            onOpenDialpad = { actions.onNavigate(AppRoute.Dialpad) },
            onClearHistory = sources.calls::clearHistory,
            onAnswer = sources.calls::answer,
            onReject = sources.calls::reject,
            onHangup = sources.calls::hangup,
            audioControls = state.callAudio,
            onMute = sources.calls::mute,
            onSpeaker = sources.calls::speaker,
            onOpenCall = actions.openCall,
            lineContent = { com.vowifi.sw.manager.ui.CallLineEntry(state.callLines, actions.callDraft.label, {
                actions.callDraft.select(it, state.callLines); sources.calls.selectLine(it)
             }, enabled = (state.calls as? CallUiState.Ready)?.busy != true,
                 historyOwners = sources.calls.historyOwners.collectAsState().value,
                 onSelectHistory = sources.calls::selectHistoryOwner) },
            currentLineLabel = actions.callLineLabel,
            lineChoices = state.callLines.choices,
            onOpenHistory = { actions.onNavigate(AppRoute.CallHistoryDetail(it)) },
            callbackEnabled = state.callLines.outgoingBinding != null && actions.callDial != null &&
                (state.callHistory as? CallHistoryState.Ready)?.owner == state.callLines.selectedOwner,
            onCallback = { number -> state.callLines.outgoingBinding?.let { actions.callDial?.invoke(it, number) } },
            onDeleteRecords = sources.calls::deleteHistory,
            onRefresh = sources.calls::refresh,
            hasRegisteredEngine = com.vowifi.sw.manager.ui.hasRegisteredCommunicationEngine(state.callLines),
        )
        is AppRoute.CallHistoryDetail -> com.vowifi.sw.manager.ui.CallHistoryDetailScreen(route.history,
            state.callHistory, sources.calls::deleteHistory)
        AppRoute.Messages -> MessagesScreen(
            state = state.messages,
            onOpenThread = { handle -> actions.onNavigate(AppRoute.MessageThread(handle)) },
            onNewMessage = { actions.onNavigate(AppRoute.NewMessage) },
            notificationsEnabled = sources.messages.notificationsEnabled.collectAsState().value,
            onNotificationSettings = sources.messages::openNotificationSettings,
            historyChoices = sources.messages.historyChoices.collectAsState().value,
            onSelectHistoryOwner = actions.messageDraft.selectOwner,
            onSaveOwnerRemark = sources.messages::saveOwnerRemark,
            onRefreshHistory = sources.messages::refresh,
            onSearchHistory = sources.messages::searchHistory,
            onConversationAction = { handle, action -> sources.messages.changeConversation(handle.value, action) },
            onLoadMore = sources.messages::loadMoreHistory,
            selectionNotice = actions.messageDraft.selectionNotice,
            hasRegisteredEngine = com.vowifi.sw.manager.ui.hasRegisteredCommunicationEngine(state.callLines),
        )
        is AppRoute.MessageThread -> MessageThreadScreen(
            handle = route.conversation,
            state = state.messages,
            onSend = { number, text -> sources.messages.sendFromDisplayedOwner((state.messages as? MessageUiState.Available)?.ownerId, number, text) },
            onVisible = sources.messages::observeVisible,
            onLoadOlder = sources.messages::loadOlderMessages,
            historyChoices = sources.messages.historyChoices.collectAsState().value,
        )
        AppRoute.NewMessage -> NewMessageScreen(
            state = com.vowifi.sw.manager.ui.messageStateForDraft(state.messages, actions.messageDraft.owner),
            recipient = actions.messageDraft.draft.recipient,
            body = actions.messageDraft.draft.body,
            onRecipientChange = actions.messageDraft.draft.updateRecipient,
            onBodyChange = actions.messageDraft.draft.updateBody,
            onClearDraft = actions.messageDraft.draft.clear,
            onSend = { number, text -> sources.messages.sendFromDisplayedOwner(actions.messageDraft.owner, number, text) },
            draftOwner = actions.messageDraft.owner,
            historyChoices = sources.messages.historyChoices.collectAsState().value,
            onSelectHistoryOwner = actions.messageDraft.selectOwner,
            onSaveOwnerRemark = sources.messages::saveOwnerRemark,
            onRefreshHistory = sources.messages::refresh,
            selectionNotice = actions.messageDraft.selectionNotice,
            onClearUnboundDraft = actions.messageDraft.clearUnbound,
        )
        AppRoute.Settings -> SettingsScreen(
            catalog = settingsCatalog(
                SettingsCatalogSnapshot(
                    configuration = state.configuration,
                    sims = state.simControl,
                    diagnostics = state.diagnostics,
                    operationalLogs = state.logs,
                    callNotification = callNotificationSnapshot,
                    appVersion = actions.appVersion,
                    exportBusy = actions.exportBusy,
                ),
            ),
            onNavigate = actions.onNavigate,
        )
        AppRoute.SettingsAppearance -> SettingsAppearanceScreen(
            settings = state.appearance,
            blurSupported = actions.blurSupported,
            actions = actions.appearanceActions,
        )
        AppRoute.SettingsConfiguration -> SettingsConfigurationScreen(
            state = state.configuration,
            simState = state.simControl,
            drafts = actions.configurationDrafts,
            actions = SettingsConfigurationActions(
                onProvision = sources::provisionSim,
                onSubmit = sources.configuration::submit,
                onRefresh = sources.configuration::refresh,
            ),
        )
        AppRoute.SettingsDiagnostics -> SettingsDiagnosticsScreen(state.diagnostics)
        AppRoute.SettingsLogs -> SettingsLogsScreen(
            state = state.logs,
            actions = SettingsLogsActions(
                selectFile = sources.logs::selectFile,
                setKind = sources.logs::setKindFilter,
                setLevel = sources.logs::setLevelFilter,
                setInstance = sources.logs::setInstanceFilter,
                onExport = { entries ->
                    if (!actions.exportBusy) actions.exportLogs(OperationalLogExporter.utf8(entries))
                },
            ),
            exportBusy = actions.exportBusy,
        )
        AppRoute.SettingsCallNotifications -> SettingsCallNotificationsScreen(sources.callNotificationSettings)
        AppRoute.SettingsAbout -> SettingsAboutScreen(actions.appVersion)
        AppRoute.Dialpad -> {
        val outgoingBinding = actions.callDraft.binding(state.callLines)
        CallsDialpadScreen(
            state = state.calls,
            draft = actions.callDraft.number,
            onDraftChange = actions.onCallDraftChange,
            onDial = { number -> outgoingBinding?.let { binding ->
                actions.callDial?.invoke(binding, number) ?: if ((state.calls as? CallUiState.Ready)?.origin == com.vowifi.sw.manager.model.CallOrigin.DESKTOP_FIXTURE) {
                    sources.calls.dial(binding, number)
                } else Unit
            } },
            lineLabel = actions.callDraft.label,
            onPermissions = actions.callPermissions,
            outgoingEnabled = outgoingBinding != null &&
                (actions.callDial != null || (state.calls as? CallUiState.Ready)?.origin == com.vowifi.sw.manager.model.CallOrigin.DESKTOP_FIXTURE),
            outgoingNotice = if (actions.callDial == null && (state.calls as? CallUiState.Ready)?.origin != com.vowifi.sw.manager.model.CallOrigin.DESKTOP_FIXTURE)
                "拨号授权入口不可用" else actions.callDraft.notice(state.callLines),
            onRefresh = sources.calls::refresh,
        )
        }
    }
}
