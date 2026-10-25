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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeNoticeDestinationTest {
    @Test
    fun observerErrorsStayOutOfHomeSoftwareHealthNotices() {
        val readErrorPresentation = homeDashboardPresentation(
            registrationState = RegistrationUiState.ReadError("raw observer read failure"),
            engineState = SimControlUiState.Loading,
            diagnosticsState = EnvironmentDiagnosticsUiState.Loading,
            nowMs = NOW_MS,
            localDateLabel = { "8月25日" },
        )
        assertEquals(emptyList(), readErrorPresentation.notices)

        val parseErrorPresentation = homeDashboardPresentation(
            registrationState = RegistrationUiState.ParseError(listOf(ParseIssue(1, "observer parse failure"))),
            engineState = SimControlUiState.Loading,
            diagnosticsState = EnvironmentDiagnosticsUiState.Loading,
            nowMs = NOW_MS,
            localDateLabel = { "8月25日" },
        )
        assertEquals(emptyList(), parseErrorPresentation.notices)

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
        assertEquals(emptyList(), issuesPresentation.notices)
    }

    @Test
    fun simControlRecoveryStaysOnHomeButOtherControlNoticesKeepTheirDestination() {
        val failurePresentation = homeDashboardPresentation(
            registrationState = RegistrationUiState.Loading,
            engineState = SimControlUiState.Failure("raw sim failure"),
            diagnosticsState = EnvironmentDiagnosticsUiState.Loading,
            nowMs = NOW_MS,
            localDateLabel = { "8月25日" },
        )
        val failureNotice: HomeNoticePresentation = failurePresentation.notices.single { it.title == "无法读取 SIM 与引擎状态" }
        assertNull(failureNotice.destination, "读取失败应在卡内重试，不能跳配置页")

        val permissionPresentation = homeDashboardPresentation(
            registrationState = RegistrationUiState.Loading,
            engineState = SimControlUiState.PermissionDenied("raw permission denied"),
            diagnosticsState = EnvironmentDiagnosticsUiState.Loading,
            nowMs = NOW_MS,
            localDateLabel = { "8月25日" },
        )
        val permissionNotice: HomeNoticePresentation = permissionPresentation.notices.single { it.title == "SIM 控制权限不足" }
        assertNull(permissionNotice.destination, "权限不足不能导航或代替用户授权")
        assertEquals(ROOT_INSTRUCTION, permissionNotice.summary)

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
        assertNull(permissionNotice.destination, "环境检查权限不足只在首页说明授权步骤并刷新")
        assertEquals(ROOT_INSTRUCTION, permissionNotice.summary)

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

    @Test fun provisionableSimKeepsItsRealRowActionWithoutARepeatedNotice() {
        val result = homeDashboardPresentation(RegistrationUiState.Empty,
            SimControlUiState.Ready(listOf(unconfigured(0, true), unconfigured(1, true))),
            EnvironmentDiagnosticsUiState.Ready(com.vowifi.sw.manager.model.DiagnosticCheckStatus.PASS, emptyList()), NOW_MS)
        assertEquals(2, result.actions.count { it.action.id == HomeActionId.PROVISION && it.action.enabled })
        assertTrue(result.notices.isEmpty(), "已有可用创建配置操作，不再重复未配置告警")
        assertEquals("需要创建 SIM 配置", result.hero.title)
    }

    @Test fun onlyTheSimWithoutAutomaticProvisioningGetsItsOwnConfigurationNotice() {
        val result = homeDashboardPresentation(RegistrationUiState.Empty,
            SimControlUiState.Ready(listOf(unconfigured(0, true), unconfigured(1, false))),
            EnvironmentDiagnosticsUiState.Loading, NOW_MS)
        val notice = result.notices.single()
        assertEquals("SIM 2 无法自动创建配置", notice.title)
        assertEquals(AppRoute.SettingsConfiguration, notice.destination)
        assertFalse(notice.summary.contains("SIM 1"))
        assertFalse(result.actions.single { it.action.handle == SimHandle("sim-2") }.action.enabled)
    }

    private fun unconfigured(slot: Int, canProvision: Boolean) = SimControlInstance(
        SimHandle("sim-${slot + 1}"), "SIM ${slot + 1}", SimPresence.ACTIVE_UNCONFIGURED, slot,
        "合成运营商", "001", "01", com.vowifi.sw.manager.model.EngineLifecycle.CONFIG_MISSING,
        canStart = false, canStop = false, canApply = false, canProvision = canProvision)

    private companion object {
        const val NOW_MS = 1_800_000_000_000L
        const val ROOT_INSTRUCTION = "在 KernelSU Manager 中为 SoftVoWiFi 授予 Root 权限后返回刷新"
    }
}
