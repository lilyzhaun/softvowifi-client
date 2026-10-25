package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.ConfigurationProvisionState
import kotlinx.coroutines.flow.StateFlow
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlin.time.Clock
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh

@Composable
fun HomeScreen(
    state: RegistrationUiState,
    engineState: SimControlUiState,
    diagnosticsState: EnvironmentDiagnosticsUiState,
    onRefresh: suspend () -> RefreshResult,
    onStartEngine: (SimHandle) -> Unit,
    onStopEngine: (SimHandle) -> Unit,
    onProvision: (SimHandle) -> Unit,
    onOpenNotice: (AppRoute.Subpage) -> Unit,
    provisioning: StateFlow<ConfigurationProvisionState>? = null,
    provisionBusy: Boolean = false,
    callProtection: () -> Map<SimHandle, Int?> = { com.vowifi.sw.manager.data.activeCallSimsFor(engineState) },
) {
    val progress = provisioning?.collectAsState()?.value ?: ConfigurationProvisionState()
    val refresh = rememberManagerRefreshController(onRefresh, enabled = !provisionBusy && progress.pending.isEmpty())
    val rotation = rememberManagerRefreshRotation(refresh)
    val hasContent = engineState is SimControlUiState.Ready || state is RegistrationUiState.Data ||
        diagnosticsState is EnvironmentDiagnosticsUiState.Ready
    val loading = rememberManagerLoadingState(
        engineState == SimControlUiState.Loading || diagnosticsState == EnvironmentDiagnosticsUiState.Loading,
        hasContent,
    )
    val emptyFade = rememberManagerEmptyFade(!loading.hidden && !loading.showLoading &&
        engineState is SimControlUiState.Ready && engineState.instances.isEmpty())
    val presentation = homeDashboardPresentation(
            registrationState = state,
            engineState = engineState,
            diagnosticsState = diagnosticsState,
            nowMs = Clock.System.now().toEpochMilliseconds(),
        )
    AppPageScaffold(
        title = appRootTitle(AppRoute.Home),
        contentTopInset = homeContentTopInset(),
        refreshController = refresh,
        refreshRotation = rotation,
        contentAlpha = loading.alpha,
        emptyAlpha = emptyFade,
        actions = {
            ManagerIconAction(MiuixIcons.Refresh, "刷新首页状态", ManagerAction.Refresh, { refresh.request() },
                enabled = refresh.enabled, iconRotation = rotation, modifier = Modifier.semantics {
                    if (refresh.running) {
                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        stateDescription = "正在刷新"
                    }
                })
        },
    ) {
        managerRefreshResultItem(refresh, "home-refresh-result")
        if (loading.showLoading) {
            item(key = "home-first-loading") {
                StatePanel(StatePanelSpec(MiuixIcons.Refresh, StatePanelTone.INTERACTIVE,
                    "正在汇总 VoWiFi 状态", "读取 IMS、引擎与环境状态。", loading = true))
            }
        } else if (!loading.hidden) homeDashboardItems(
            presentation = presentation,
            onStart = onStartEngine,
            onStop = onStopEngine,
             onProvision = onProvision,
              onOpenNotice = onOpenNotice,
              onRefresh = { refresh.request() },
              refreshEnabled = refresh.enabled,
              refreshRunning = refresh.running,
              provisioning = progress,
              callProtection = callProtection,
         )
    }
}
