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
    val messageDraftRecipient: String,
    val messageDraftBody: String,
    val callDraft: String,
    val onCallDraftChange: (String) -> Unit,
    val onMessageDraftRecipientChange: (String) -> Unit,
    val onMessageDraftBodyChange: (String) -> Unit,
    val onClearMessageDraft: () -> Unit,
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
        )
        AppRoute.Messages -> MessagesScreen(
            state = state.messages,
            onOpenThread = { handle -> actions.onNavigate(AppRoute.MessageThread(handle)) },
            onNewMessage = { actions.onNavigate(AppRoute.NewMessage) },
            notificationsEnabled = sources.messages.notificationsEnabled.collectAsState().value,
            onNotificationSettings = sources.messages::openNotificationSettings,
            historyChoices = sources.messages.historyChoices.collectAsState().value,
            onSelectHistoryOwner = sources.messages::selectHistoryOwner,
            onSaveOwnerRemark = sources.messages::saveOwnerRemark,
            onRefreshHistory = sources.messages::refresh,
            onSearchHistory = sources.messages::searchHistory,
            onLoadMore = sources.messages::loadMoreHistory,
        )
        is AppRoute.MessageThread -> MessageThreadScreen(
            handle = route.conversation,
            state = state.messages,
            onSend = { number, text -> sources.messages.sendFromDisplayedOwner((state.messages as? MessageUiState.Available)?.ownerId, number, text) },
            onVisible = sources.messages::observeVisible,
            onLoadOlder = sources.messages::loadOlderMessages,
        )
        AppRoute.NewMessage -> NewMessageScreen(
            state = state.messages,
            recipient = actions.messageDraftRecipient,
            body = actions.messageDraftBody,
            onRecipientChange = actions.onMessageDraftRecipientChange,
            onBodyChange = actions.onMessageDraftBodyChange,
            onClearDraft = actions.onClearMessageDraft,
            onSend = { number, text -> sources.messages.sendFromDisplayedOwner((state.messages as? MessageUiState.Available)?.ownerId, number, text) },
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
        AppRoute.Dialpad -> CallsDialpadScreen(
            state = state.calls,
            draft = actions.callDraft,
            onDraftChange = actions.onCallDraftChange,
            onDial = { number -> (state.calls as? CallUiState.Ready)?.binding?.let { binding ->
                actions.callDial?.invoke(binding, number) ?: sources.calls.dial(binding, number)
            } },
            lineLabel = actions.callLineLabel,
            onPermissions = actions.callPermissions,
        )
    }
}
