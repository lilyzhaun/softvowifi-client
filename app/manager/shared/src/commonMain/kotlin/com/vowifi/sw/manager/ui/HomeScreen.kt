package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
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
    onRefresh: () -> Unit,
    onStartEngine: (SimHandle) -> Unit,
    onStopEngine: (SimHandle) -> Unit,
    onProvision: (SimHandle) -> Unit,
    onOpenNotice: (AppRoute.Subpage) -> Unit,
) {
    val presentation = homeDashboardPresentation(
            registrationState = state,
            engineState = engineState,
            diagnosticsState = diagnosticsState,
            nowMs = Clock.System.now().toEpochMilliseconds(),
        )
    AppPageScaffold(
        title = appRootTitle(AppRoute.Home),
        contentTopInset = homeContentTopInset(),
        actions = {
            ManagerIconAction(MiuixIcons.Refresh, "刷新首页状态", ManagerAction.Refresh, onRefresh)
        },
    ) {
        homeDashboardItems(
            presentation = presentation,
            onStart = onStartEngine,
            onStop = onStopEngine,
            onProvision = onProvision,
            onOpenNotice = onOpenNotice,
        )
    }
}
