package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.DiagnosticCheck
import com.vowifi.sw.manager.model.DiagnosticCheckId
import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.ParseIssue
import com.vowifi.sw.manager.model.RegistrationEvent
import com.vowifi.sw.manager.model.RegistrationSnapshot
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeNoticeDestinationTest {
    @Test
    fun registrationNoticesRouteToSettingsDiagnostics() {
        val readErrorPresentation = homeDashboardPresentation(
            registrationState = RegistrationUiState.ReadError("raw observer read failure"),
            engineState = SimControlUiState.Loading,
            diagnosticsState = EnvironmentDiagnosticsUiState.Loading,
            nowMs = NOW_MS,
            localDateLabel = { "8月25日" },
        )
        val readErrorNotice: HomeNoticePresentation = readErrorPresentation.notices.single { it.title == "无法读取 IMS 状态" }
        assertEquals(AppRoute.SettingsDiagnostics, readErrorNotice.destination)

        val parseErrorPresentation = homeDashboardPresentation(
            registrationState = RegistrationUiState.ParseError(listOf(ParseIssue(1, "observer parse failure"))),
            engineState = SimControlUiState.Loading,
            diagnosticsState = EnvironmentDiagnosticsUiState.Loading,
            nowMs = NOW_MS,
            localDateLabel = { "8月25日" },
        )
        val parseErrorNotice: HomeNoticePresentation = parseErrorPresentation.notices.single { it.title == "IMS 事件无法解析" }
        assertEquals(AppRoute.SettingsDiagnostics, parseErrorNotice.destination)

        val issuesPresentation = homeDashboardPresentation(
            registrationState = RegistrationUiState.Data(
                RegistrationSnapshot(
                    events = listOf(RegistrationEvent(NOW_MS, 1, "REGISTERED")),
                    latestBySubId = listOf(RegistrationEvent(NOW_MS, 1, "REGISTERED")),
                    issues = listOf(ParseIssue(1, "observer line unparseable")),
                ),
            ),
            engineState = SimControlUiState.Loading,
            diagnosticsState = EnvironmentDiagnosticsUiState.Loading,
            nowMs = NOW_MS,
            localDateLabel = { "8月25日" },
        )
        val issuesNotice: HomeNoticePresentation = issuesPresentation.notices.single { it.title == "IMS 数据含告警" }
        assertEquals(AppRoute.SettingsDiagnostics, issuesNotice.destination)
    }

    @Test
    fun simControlNoticesRouteToSettingsConfiguration() {
        val failurePresentation = homeDashboardPresentation(
            registrationState = RegistrationUiState.Loading,
            engineState = SimControlUiState.Failure("raw sim failure"),
            diagnosticsState = EnvironmentDiagnosticsUiState.Loading,
            nowMs = NOW_MS,
            localDateLabel = { "8月25日" },
        )
        val failureNotice: HomeNoticePresentation = failurePresentation.notices.single { it.title == "无法读取 SIM 与引擎状态" }
        assertEquals(AppRoute.SettingsConfiguration, failureNotice.destination)

        val permissionPresentation = homeDashboardPresentation(
            registrationState = RegistrationUiState.Loading,
            engineState = SimControlUiState.PermissionDenied("raw permission denied"),
            diagnosticsState = EnvironmentDiagnosticsUiState.Loading,
            nowMs = NOW_MS,
            localDateLabel = { "8月25日" },
        )
        val permissionNotice: HomeNoticePresentation = permissionPresentation.notices.single { it.title == "SIM 控制权限不足" }
        assertEquals(AppRoute.SettingsConfiguration, permissionNotice.destination)

        val readyMessagePresentation = homeDashboardPresentation(
            registrationState = RegistrationUiState.Loading,
            engineState = SimControlUiState.Ready(
                instances = listOf(
                    SimControlInstance(
                        handle = SimHandle("sim-1"),
                        displayLabel = "SIM 1",
                        presence = SimPresence.ACTIVE_MATCHED,
                        slotIndex = 0,
                        carrierLabel = "Carrier",
                        mcc = "460",
                        mnc = "00",
                        lifecycle = com.vowifi.sw.manager.model.EngineLifecycle.STOPPED,
                        canStart = true,
                        canStop = false,
                        canApply = false,
                    ),
                ),
                message = "部分 SIM 状态异常",
            ),
            diagnosticsState = EnvironmentDiagnosticsUiState.Loading,
            nowMs = NOW_MS,
            localDateLabel = { "8月25日" },
        )
        val readyMessageNotice: HomeNoticePresentation = readyMessagePresentation.notices.single { it.title == "SIM 控制状态需注意" }
        assertEquals(AppRoute.SettingsConfiguration, readyMessageNotice.destination)
    }

    @Test
    fun diagnosticsNoticesRouteToSettingsDiagnostics() {
        val failurePresentation = homeDashboardPresentation(
            registrationState = RegistrationUiState.Loading,
            engineState = SimControlUiState.Loading,
            diagnosticsState = EnvironmentDiagnosticsUiState.Failure("raw diagnostics failure"),
            nowMs = NOW_MS,
            localDateLabel = { "8月25日" },
        )
        val failureNotice: HomeNoticePresentation = failurePresentation.notices.single { it.title == "环境检查失败" }
        assertEquals(AppRoute.SettingsDiagnostics, failureNotice.destination)

        val permissionPresentation = homeDashboardPresentation(
            registrationState = RegistrationUiState.Loading,
            engineState = SimControlUiState.Loading,
            diagnosticsState = EnvironmentDiagnosticsUiState.PermissionDenied("raw permission failure"),
            nowMs = NOW_MS,
            localDateLabel = { "8月25日" },
        )
        val permissionNotice: HomeNoticePresentation = permissionPresentation.notices.single { it.title == "环境检查权限不足" }
        assertEquals(AppRoute.SettingsDiagnostics, permissionNotice.destination)

        val unavailablePresentation = homeDashboardPresentation(
            registrationState = RegistrationUiState.Loading,
            engineState = SimControlUiState.Loading,
            diagnosticsState = EnvironmentDiagnosticsUiState.Unavailable("not supported"),
            nowMs = NOW_MS,
            localDateLabel = { "8月25日" },
        )
        val unavailableNotice: HomeNoticePresentation = unavailablePresentation.notices.single { it.title == "环境检查不可用" }
        assertEquals(AppRoute.SettingsDiagnostics, unavailableNotice.destination)

        val warnPresentation = homeDashboardPresentation(
            registrationState = RegistrationUiState.Loading,
            engineState = SimControlUiState.Loading,
            diagnosticsState = EnvironmentDiagnosticsUiState.Ready(
                severity = DiagnosticCheckStatus.WARN,
                checks = listOf(
                    DiagnosticCheck(DiagnosticCheckId.MODULE, DiagnosticCheckStatus.WARN, "模块", "存在告警"),
                ),
            ),
            nowMs = NOW_MS,
            localDateLabel = { "8月25日" },
        )
        val warnNotice: HomeNoticePresentation = warnPresentation.notices.single { it.title == "环境存在告警" }
        assertEquals(AppRoute.SettingsDiagnostics, warnNotice.destination)

        val failPresentation = homeDashboardPresentation(
            registrationState = RegistrationUiState.Loading,
            engineState = SimControlUiState.Loading,
            diagnosticsState = EnvironmentDiagnosticsUiState.Ready(
                severity = DiagnosticCheckStatus.FAIL,
                checks = listOf(
                    DiagnosticCheck(DiagnosticCheckId.MODULE, DiagnosticCheckStatus.FAIL, "模块", "检查未通过"),
                ),
            ),
            nowMs = NOW_MS,
            localDateLabel = { "8月25日" },
        )
        val failNotice: HomeNoticePresentation = failPresentation.notices.single { it.title == "环境检查未通过" }
        assertEquals(AppRoute.SettingsDiagnostics, failNotice.destination)
    }

    private companion object {
        const val NOW_MS = 1_800_000_000_000L
    }
}
