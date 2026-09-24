package com.vowifi.sw.manager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.vowifi.sw.manager.ui.AppDestination
import com.vowifi.sw.manager.ui.AppShell
import com.vowifi.sw.manager.ui.AppearanceSettingsSource
import com.vowifi.sw.manager.ui.InMemoryAppearanceSettingsSource
import com.vowifi.sw.manager.ui.SoftVoWiFiTheme
import com.vowifi.sw.manager.ui.messageRouteTopBarTitle
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.data.CallSource
import com.vowifi.sw.manager.data.ConfigurationSource
import com.vowifi.sw.manager.data.EngineControlSource
import com.vowifi.sw.manager.data.EnvironmentDiagnosticsSource
import com.vowifi.sw.manager.data.MessageSource
import com.vowifi.sw.manager.data.ObserverLogSource
import com.vowifi.sw.manager.data.OperationalLogSource
import com.vowifi.sw.manager.data.SimControlSource
import com.vowifi.sw.manager.ui.navigation.AppNavigationState
import com.vowifi.sw.manager.ui.navigation.AppNavigator
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.navigation.NavigationRestoreResult
import com.vowifi.sw.manager.ui.navigation.destination
import com.vowifi.sw.manager.ui.navigation.rootRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow

@Composable
fun SoftVoWiFiApp(
    observerSource: ObserverLogSource,
    engineSource: EngineControlSource,
    simControlSource: SimControlSource,
    configurationSource: ConfigurationSource,
    diagnosticsSource: EnvironmentDiagnosticsSource,
    operationalLogSource: OperationalLogSource,
    messageSource: MessageSource,
    callSource: CallSource,
    microphonePermissionGranted: Boolean,
    onRequestMicrophonePermission: () -> Unit,
    onExportLogs: (String) -> Unit,
    exportBusy: Boolean,
    appVersion: String,
    initialDestination: AppDestination = AppDestination.HOME,
    incomingRoutes: Flow<AppRoute> = emptyFlow(),
    forceDark: Boolean? = null,
    appearanceSource: AppearanceSettingsSource = InMemoryAppearanceSettingsSource(),
    blurSupported: Boolean = false,
    initialSubpage: AppRoute.Subpage? = null,
) {
    SoftVoWiFiApp(
        sources = SoftVoWiFiSources(
            observerSource,
            engineSource,
            simControlSource,
            configurationSource,
            diagnosticsSource,
            operationalLogSource,
            messageSource,
            callSource,
        ),
        onExportLogs = onExportLogs,
        exportBusy = exportBusy,
        appVersion = appVersion,
        initialDestination = initialDestination,
        incomingRoutes = incomingRoutes,
        forceDark = forceDark,
        microphonePermissionGranted = microphonePermissionGranted,
        onRequestMicrophonePermission = onRequestMicrophonePermission,
        appearanceSource = appearanceSource,
        blurSupported = blurSupported,
        initialSubpage = initialSubpage,
    )
}

@Composable
fun SoftVoWiFiApp(
    sources: SoftVoWiFiSources,
    onExportLogs: (String) -> Unit,
    exportBusy: Boolean,
    appVersion: String,
    initialDestination: AppDestination = AppDestination.HOME,
    incomingRoutes: Flow<AppRoute> = emptyFlow(),
    forceDark: Boolean? = null,
    microphonePermissionGranted: Boolean = true,
    onRequestMicrophonePermission: () -> Unit = {},
    appearanceSource: AppearanceSettingsSource = InMemoryAppearanceSettingsSource(),
    blurSupported: Boolean = false,
    initialSubpage: AppRoute.Subpage? = null,
    configurationDraftMemory: com.vowifi.sw.manager.ui.ConfigurationDraftMemory? = null,
    onCallDial: ((com.vowifi.sw.manager.model.CallBinding, String) -> Unit)? = null,
    onOpenCall: (() -> Unit)? = null,
    callLineLabel: String = "当前绑定线路",
    onCallPermissions: (() -> Unit)? = null,
) {
    val observerState by sources.observer.state.collectAsState()
    val engineState by sources.engine.state.collectAsState()
    val simControlState by sources.simControl.state.collectAsState()
    val configurationState by sources.configuration.state.collectAsState()
    val draftMemory = configurationDraftMemory ?: remember { com.vowifi.sw.manager.ui.ConfigurationDraftMemory() }
    val configurationDrafts = com.vowifi.sw.manager.ui.rememberConfigurationDraftStore(
        sources.configuration, draftMemory, configurationState,
    )
    val diagnosticsState by sources.diagnostics.state.collectAsState()
    val operationalLogState by sources.logs.state.collectAsState()
    val messageState by sources.messages.state.collectAsState()
    val messageUnreadSummary by sources.messages.unreadSummary.collectAsState()
    val callState by sources.calls.state.collectAsState()
    val callLines by sources.calls.lines.collectAsState()
    val callHistory by sources.calls.history.collectAsState()
    val callAudioControls by sources.calls.audioControls.collectAsState()
    val appearanceSettings by appearanceSource.state.collectAsState()
    val initialNavigationState = remember(initialDestination, initialSubpage) {
        val root = AppNavigationState.initial(initialDestination.rootRoute)
        initialSubpage?.let { AppNavigator.push(root, it) } ?: root
    }
    val navigationStateSaver = remember(initialNavigationState) {
        Saver<AppNavigationState, String>(
            save = { state -> state.save() },
            restore = { serialized ->
                when (val result = AppNavigationState.restore(serialized, initialNavigationState)) {
                    is NavigationRestoreResult.Success -> result.state
                    is NavigationRestoreResult.Failure -> result.state
                }
            },
        )
    }
    var navigationState by rememberSaveable(
        initialDestination,
        stateSaver = navigationStateSaver,
    ) {
        mutableStateOf(initialNavigationState)
    }
    var previousEngineAttachmentSignature by remember {
        mutableStateOf<EngineCallAttachmentSignature?>(null)
    }
    fun navigate(next: AppNavigationState) {
        sources.messages.selectConversation((next.topRoute as? AppRoute.MessageThread)?.conversation?.value)
        navigationState = next
    }
    LaunchedEffect(Unit) {
        (navigationState.topRoute as? AppRoute.MessageThread)?.let { sources.messages.selectConversation(it.conversation.value) }
    }
    val engineAttachmentSignature = engineCallAttachmentSignature(engineState)
    LaunchedEffect(engineAttachmentSignature) {
        if (shouldRefreshCallsForEngineAvailability(previousEngineAttachmentSignature, engineAttachmentSignature)) {
            sources.calls.refresh()
            sources.messages.refresh()
        }
        previousEngineAttachmentSignature = engineAttachmentSignature
    }
    var previousCallPhase by remember { mutableStateOf<CallPhase?>(null) }
    var rootSnapRequestId by rememberSaveable { mutableLongStateOf(0L) }
    val messageDraft = com.vowifi.sw.manager.ui.rememberBoundMessageDraft(
        messageState, navigationState.topRoute == AppRoute.NewMessage, sources.messages::selectHistoryOwner,
    )
    val callDraft = com.vowifi.sw.manager.ui.rememberCallDialDraft(callLines)
    val callPhase = (callState as? CallUiState.Ready)?.phase
    LaunchedEffect(callPhase) {
        val routed = AppNavigator.forCallTransition(navigationState, previousCallPhase, callPhase)
        if (routed !== navigationState) rootSnapRequestId += 1
        if (routed !== navigationState) navigate(routed)
        previousCallPhase = callPhase
    }
    LaunchedEffect(incomingRoutes) {
        incomingRoutes.collect { route ->
            navigate(when (route) {
                is AppRoute.Root -> AppNavigator.selectRoot(navigationState, route)
                is AppRoute.Subpage -> AppNavigator.open(navigationState, route)
            })
            rootSnapRequestId += 1
        }
    }
    val selectedDestination = navigationState.currentRoot.destination
    LaunchedEffect(selectedDestination) { sources.refresh(selectedDestination.refreshTargets) }
    val refreshHome = { sources.refresh(AppDestination.HOME.refreshTargets) }
    val contentState = SoftVoWiFiContentState(
        observer = observerState,
        simControl = simControlState,
        configuration = configurationState,
        diagnostics = diagnosticsState,
        logs = operationalLogState,
        messages = messageState,
        calls = callState,
        callLines = callLines,
        callHistory = callHistory,
        callAudio = callAudioControls,
        appearance = appearanceSettings,
    )
    val contentActions = SoftVoWiFiContentActions(
        sources = sources,
        configurationDrafts = configurationDrafts,
        callDial = onCallDial,
        openCall = onOpenCall,
        callLineLabel = callLineLabel,
        callPermissions = onCallPermissions,
        microphonePermissionGranted = microphonePermissionGranted,
        requestMicrophonePermission = onRequestMicrophonePermission,
        exportLogs = onExportLogs,
        exportBusy = exportBusy,
        appVersion = appVersion,
        refreshHome = refreshHome,
        messageDraft = messageDraft,
        callDraft = callDraft,
        onCallDraftChange = { callDraft.number = it },
        onNavigate = { subpage ->
            navigate(AppNavigator.push(navigationState, subpage))
        },
        onOpenHomeNotice = { subpage ->
            navigate(AppNavigator.open(navigationState, subpage))
        },
        blurSupported = blurSupported,
        appearanceActions = com.vowifi.sw.manager.ui.AppearanceSettingsActions(
            onFloatingNavigationChange = appearanceSource::setFloatingNavigation,
            onBlurRequestedChange = appearanceSource::setBlurRequested,
            onLiquidGlassRequestedChange = appearanceSource::setLiquidGlassRequested,
            onPredictiveBackRequestedChange = appearanceSource::setPredictiveBackRequested,
            onHapticsEnabledChange = appearanceSource::setHapticsEnabled,
        ),
    )
    SoftVoWiFiTheme(forceDark) {
        AppShell(
            navigationState = navigationState,
            onNavigationStateChange = ::navigate,
            topBarTitleOverride = messageRouteTopBarTitle(navigationState.topRoute, messageState),
            appearanceSettings = appearanceSettings,
            blurSupported = blurSupported,
            rootSnapRequestId = rootSnapRequestId,
            messageUnreadSummary = messageUnreadSummary,
            ongoingCall = if (onOpenCall != null && callPhase != null && callPhase != CallPhase.Idle && navigationState.currentRoot != AppRoute.Calls) {
                { com.vowifi.sw.manager.ui.OngoingCallAffordance(onClick = onOpenCall) }
            } else null,
            destinationContent = { route ->
                SoftVoWiFiDestinationContent(route, contentState, contentActions)
            },
        )
    }
}
