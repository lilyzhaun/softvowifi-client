package com.vowifi.sw.manager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.vowifi.sw.manager.data.OperationalLogExporter
import com.vowifi.sw.manager.model.OperationalLogUiState
import com.vowifi.sw.manager.model.SYSTEM_REGISTRATION_LOG_ID
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.ui.CallsDialpadScreen
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.CallsScreen
import com.vowifi.sw.manager.ui.HomeScreen
import com.vowifi.sw.manager.ui.MessageThreadScreen
import com.vowifi.sw.manager.ui.MessagesScreen
import com.vowifi.sw.manager.ui.NewMessageScreen
import com.vowifi.sw.manager.ui.SettingsScreen
import com.vowifi.sw.manager.ui.SettingsAppearanceScreen
import com.vowifi.sw.manager.ui.SettingsConfigurationActions
import com.vowifi.sw.manager.ui.SettingsConfigurationScreen
import com.vowifi.sw.manager.ui.SettingsConfigurationSimScreen
import com.vowifi.sw.manager.ui.SettingsDiagnosticsScreen
import com.vowifi.sw.manager.ui.SettingsLogsActions
import com.vowifi.sw.manager.ui.SettingsLogsScreen
import com.vowifi.sw.manager.ui.SettingsCallNotificationsScreen
import com.vowifi.sw.manager.ui.SettingsAboutScreen
import com.vowifi.sw.manager.ui.SettingsEngineInformationScreen
import com.vowifi.sw.manager.ui.SettingsCatalogSnapshot
import com.vowifi.sw.manager.ui.settingsCatalog
import com.vowifi.sw.manager.ui.navigation.AppRoute

internal data class SoftVoWiFiContentActions(
    val sources: SoftVoWiFiSources,
    val microphonePermissionGranted: Boolean,
    val requestMicrophonePermission: () -> Unit,
    val exportLogs: (String) -> Unit,
    val exportBusy: Boolean,
    val appVersion: String,
    val refreshHome: suspend () -> com.vowifi.sw.manager.data.RefreshResult,
    val messageDraft: com.vowifi.sw.manager.ui.BoundMessageDraft,
    val callDraft: com.vowifi.sw.manager.ui.CallDialDraft,
    val onCallDraftChange: (String) -> Unit,
    val onNavigate: (AppRoute.Subpage) -> Unit,
    val onOpenNewMessageConversation: (com.vowifi.sw.manager.ui.navigation.ConversationHandle) -> Unit,
    val onCommunicationHome: () -> Unit,
    val onOpenHomeNotice: (AppRoute.Subpage) -> Unit,
    // Opens a child of the current branch, keeping the source root (e.g. Home → configuration → one SIM).
    val onOpenSubpage: (AppRoute.Subpage) -> Unit = onOpenHomeNotice,
    val blurSupported: Boolean,
    val appearanceActions: com.vowifi.sw.manager.ui.AppearanceSettingsActions,
    val configurationDrafts: com.vowifi.sw.manager.ui.ConfigurationDraftStore = com.vowifi.sw.manager.ui.ConfigurationDraftStore(),
    val callDial: ((com.vowifi.sw.manager.model.CallBinding, String) -> Unit)? = null,
    val openCall: (() -> Unit)? = null,
    val callLineLabel: String = "当前绑定线路",
    val callPermissions: (() -> Unit)? = null,
    val exportCompletion: com.vowifi.sw.manager.model.LogExportCompletion? = null,
    val onEntriesObscuredChange: (AppRoute.Root, Boolean) -> Unit = { _, _ -> },
    val onConfirmMessageOwner: suspend (com.vowifi.sw.manager.data.SimOwnerId) -> Boolean,
    val appVersionCode: Int? = null,
    val onMessageFromCall: (suspend (com.vowifi.sw.manager.data.SimOwnerId, String) -> Boolean)? = null,
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
    fun selectCallLine(owner: com.vowifi.sw.manager.data.SimOwnerId): Boolean {
        sources.calls.selectLine(owner)
        val confirmed = sources.calls.lines.value
        if (confirmed.selectedOwner != owner) return false
        actions.callDraft.select(owner, confirmed)
        return true
    }
    val callNotificationSnapshot = sources.callNotificationSettings.state.collectAsState().value
    val history = state.callHistorySnapshot?.view ?: state.callHistory
    val callbackReason = com.vowifi.sw.manager.ui.callHistoryCallbackUnavailableReason(history, state.callLines, actions.callDial != null)
    val callbackBinding = state.callLines.outgoingBinding
    val callback: (String) -> Unit = { number ->
        val current = sources.calls.lines.value
        if (callbackReason == null && callbackBinding != null && current.outgoingBinding === callbackBinding &&
            current.selectedOwner == (history as? CallHistoryState.Ready)?.owner) {
            actions.callDial?.invoke(callbackBinding, number)
        }
    }
    when (route) {
        AppRoute.Home -> HomeScreen(
            state = state.observer,
            engineState = state.simControl,
            diagnosticsState = state.diagnostics,
            onRefresh = actions.refreshHome,
            onStartEngine = sources::startSim,
            onStopEngine = sources::stopSim,
            callProtection = { sources.simControl.activeCallSims(sources.calls.state.value) },
            onProvision = sources::provisionSim,
            onOpenNotice = actions.onOpenHomeNotice,
            provisioning = sources.configuration.provisioning,
        )
        AppRoute.Calls -> CallsScreen(
            state = state.calls,
            microphonePermissionGranted = actions.microphonePermissionGranted,
            onRequestMicrophonePermission = actions.requestMicrophonePermission,
            history = state.callHistory,
            historyOwners = state.callHistoryOwners,
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
                 selectCallLine(it)
             }, enabled = (state.calls as? CallUiState.Ready)?.busy != true,
                  historyOwners = state.callHistoryOwners,
                  onSelectHistory = sources.calls::selectHistoryOwner,
                  onConfirm = { selectCallLine(it) },
                  onConfirmHistory = sources.calls::selectHistoryOwnerAndAwait) },
            currentLineLabel = actions.callLineLabel,
            lineChoices = state.callLines.choices,
            onOpenHistory = { actions.onNavigate(AppRoute.CallHistoryDetail(it)) },
            callbackEnabled = callbackReason == null,
            onCallback = callback,
            onDeleteRecords = sources.calls::deleteHistory,
            historySnapshot = state.callHistorySnapshot,
            onDeleteRecordsAndAwait = if (state.callHistorySnapshot != null) sources.calls::deleteHistoryAndAwait else null,
            onRefresh = sources.calls::refresh,
            onRetryHistory = sources.calls::refreshHistory,
            showUnregisteredNotice = com.vowifi.sw.manager.ui.confirmedNoRegisteredCommunicationLine(state.callLines, state.calls),
            onGoHome = actions.onCommunicationHome,
            hasRegisteredEngine = com.vowifi.sw.manager.ui.hasRegisteredCommunicationEngine(state.callLines),
            onEntriesObscuredChange = { actions.onEntriesObscuredChange(AppRoute.Calls, it) },
        )
        is AppRoute.CallHistoryDetail -> com.vowifi.sw.manager.ui.CallHistoryDetailScreen(route.history,
            state.callHistory, sources.calls::deleteHistory, historySnapshot = state.callHistorySnapshot,
            onDeleteAndAwait = if (state.callHistorySnapshot != null) sources.calls::deleteHistoryAndAwait else null,
            onCallback = callback, callbackUnavailableReason = callbackReason,
            onRetry = sources.calls::refreshHistory, onMessage = actions.onMessageFromCall)
        AppRoute.Messages -> MessagesScreen(
            state = state.messages,
            onOpenThread = { handle -> actions.onNavigate(AppRoute.MessageThread(handle)) },
            onNewMessage = { actions.messageDraft.openNew(); actions.onNavigate(AppRoute.NewMessage) },
            notificationsEnabled = sources.messages.notificationsEnabled.collectAsState().value,
            onNotificationSettings = sources.messages::openNotificationSettings,
            historyChoices = sources.messages.historyChoices.collectAsState().value,
            onSelectHistoryOwner = actions.messageDraft.selectOwner,
            onConfirmHistoryOwner = actions.onConfirmMessageOwner,
            onSaveOwnerRemark = sources.messages::saveOwnerRemark,
            onRefreshHistory = sources.messages::refresh,
            onSearchHistory = sources.messages::searchHistory,
            onConversationAction = { handle, action -> sources.messages.changeConversation(handle.value, action) },
            onLoadMore = sources.messages::loadMoreHistory,
            selectionNotice = actions.messageDraft.selectionNotice,
            hasRegisteredEngine = com.vowifi.sw.manager.ui.hasRegisteredCommunicationEngine(state.callLines),
            onEntriesObscuredChange = { actions.onEntriesObscuredChange(AppRoute.Messages, it) },
            showUnregisteredNotice = com.vowifi.sw.manager.ui.confirmedNoRegisteredCommunicationLine(state.callLines, state.calls),
            onGoHome = actions.onCommunicationHome,
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
            onConfirmHistoryOwner = actions.onConfirmMessageOwner,
            onSaveOwnerRemark = sources.messages::saveOwnerRemark,
            onRefreshHistory = sources.messages::refresh,
            selectionNotice = actions.messageDraft.selectionNotice,
            onClearUnboundDraft = actions.messageDraft.clearUnbound,
            findConversation = sources.messages::findConversation,
            onOpenConversation = actions.onOpenNewMessageConversation,
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
            provisioning = sources.configuration.provisioning,
            actions = SettingsConfigurationActions(
                onProvision = sources::provisionSim,
                onSubmit = sources.configuration::submit,
                onRefresh = sources.configuration::refreshIdentitiesAndAwait,
                onOpenSim = { actions.onOpenSubpage(AppRoute.SettingsConfigurationSim(it)) },
                onRebuild = sources.configuration.takeIf { it.supportsRebuild }?.let { it::rebuild },
            ),
        )
        is AppRoute.SettingsConfigurationSim -> SettingsConfigurationSimScreen(
            handle = route.sim,
            state = state.configuration,
            simState = state.simControl,
            drafts = actions.configurationDrafts,
            activeCallSims = sources.simControl.activeCallSims(state.calls),
            provisioning = sources.configuration.provisioning,
            actions = SettingsConfigurationActions(
                onProvision = sources::provisionSim,
                onSubmit = sources.configuration::submit,
                onRefresh = sources.configuration::refreshIdentitiesAndAwait,
                onRebuild = sources.configuration.takeIf { it.supportsRebuild }?.let { it::rebuild },
            ),
        )
        AppRoute.SettingsDiagnostics -> SettingsDiagnosticsScreen(state.diagnostics, onRefresh = sources.diagnostics::refreshAndAwait,
            onOpenConfiguration = { actions.onNavigate(AppRoute.SettingsConfiguration) }, onHome = actions.onCommunicationHome)
        AppRoute.SettingsLogs -> {
            val selectedLog = when (val logs = state.logs) {
                is OperationalLogUiState.Ready -> logs.selectedFileId
                is OperationalLogUiState.Warning -> logs.selectedFileId
                else -> ""
            }
            val fullExport = sources.logs.fullDiagnosticExport && selectedLog != SYSTEM_REGISTRATION_LOG_ID
            SettingsLogsScreen(
            state = state.logs,
            actions = SettingsLogsActions(
                selectFile = sources.logs::selectFile,
                setKind = sources.logs::setKindFilter,
                setLevel = sources.logs::setLevelFilter,
                setInstance = sources.logs::setInstanceFilter,
                fullDiagnosticExport = fullExport,
                exportWindow = sources.logs.diagnosticExportWindow?.collectAsState()?.value ?: sources.logs.exportWindow(),
                setExportWindow = sources.logs::setExportWindow,
                onExport = { entries ->
                    if (!actions.exportBusy) actions.exportLogs(if(fullExport) "" else OperationalLogExporter.utf8(entries))
                },
            ),
            exportBusy = actions.exportBusy,
            onRefresh = sources.logs::refreshAndAwait,
            exportCompletion = actions.exportCompletion,
        )
        }
        AppRoute.SettingsCallNotifications -> SettingsCallNotificationsScreen(sources.callNotificationSettings)
        AppRoute.SettingsAbout -> SettingsAboutScreen(actions.appVersion, appVersionCode = actions.appVersionCode,
            diagnosticsState = state.diagnostics,
            onOpenEngineInformation = { actions.onOpenSubpage(AppRoute.SettingsEngineInformation) })
        AppRoute.SettingsEngineInformation -> SettingsEngineInformationScreen(sources.readEngineIntegrity)
        AppRoute.Dialpad -> {
        val outgoingBinding = actions.callDraft.binding(state.callLines)
        CallsDialpadScreen(
            state = state.calls,
            draft = actions.callDraft.number,
            onDraftChange = actions.onCallDraftChange,
            onDial = { number -> outgoingBinding?.let { binding ->
                actions.callDraft.submit(number)
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
