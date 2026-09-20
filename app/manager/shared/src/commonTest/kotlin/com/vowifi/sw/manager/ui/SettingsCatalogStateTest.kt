package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.OperationalLogUiState
import com.vowifi.sw.manager.model.SimControlUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsCatalogStateTest {
    @Test
    fun loadingPermissionUnavailableAndFailureSummariesAreBounded() {
        val loading = settingsCatalog(
            settingsSnapshot(
                configuration = ConfigurationUiState.Loading,
                sims = SimControlUiState.Loading,
                diagnostics = EnvironmentDiagnosticsUiState.Loading,
                logs = OperationalLogUiState.Loading,
            ),
        )
        assertEquals("正在读取 SIM 与网络配置", loading.root(SettingsRowId.Root.CONFIGURATION).summary)
        assertEquals("正在检查运行环境", loading.root(SettingsRowId.Root.DIAGNOSTICS).summary)
        assertEquals("正在读取运行日志", loading.root(SettingsRowId.Root.OPERATIONAL_LOGS).summary)

        val permission = settingsCatalog(
            settingsSnapshot(
                configuration = ConfigurationUiState.PermissionDenied("backend-detail-marker"),
                sims = SimControlUiState.PermissionDenied("backend-detail-marker"),
                diagnostics = EnvironmentDiagnosticsUiState.PermissionDenied("backend-detail-marker"),
                logs = OperationalLogUiState.PermissionDenied("backend-detail-marker"),
            ),
        )
        assertEquals("需要 KernelSU 授权", permission.root(SettingsRowId.Root.CONFIGURATION).summary)
        assertEquals("需要 KernelSU 授权", permission.root(SettingsRowId.Root.DIAGNOSTICS).summary)
        assertEquals("当前权限无法读取运行日志", permission.root(SettingsRowId.Root.OPERATIONAL_LOGS).summary)

        val unavailable = settingsCatalog(
            settingsSnapshot(
                configuration = ConfigurationUiState.Unavailable("raw"),
                sims = SimControlUiState.Failure("raw"),
                diagnostics = EnvironmentDiagnosticsUiState.Unavailable("raw"),
                logs = OperationalLogUiState.Unavailable("raw"),
            ),
        )
        assertFalse(unavailable.root(SettingsRowId.Root.CONFIGURATION).enabled)
        assertFalse(unavailable.root(SettingsRowId.Root.DIAGNOSTICS).enabled)
        assertFalse(unavailable.root(SettingsRowId.Root.OPERATIONAL_LOGS).enabled)

        val failure = settingsCatalog(
            settingsSnapshot(
                configuration = ConfigurationUiState.Failure("backend-detail-marker"),
                sims = SimControlUiState.Failure("backend-detail-marker"),
                diagnostics = EnvironmentDiagnosticsUiState.Failure("backend-detail-marker"),
                logs = OperationalLogUiState.Failure("backend-detail-marker"),
            ),
        )
        assertEquals("配置状态读取失败", failure.root(SettingsRowId.Root.CONFIGURATION).summary)
        assertEquals("环境检查未完成", failure.root(SettingsRowId.Root.DIAGNOSTICS).summary)
        assertEquals("日志读取失败", failure.root(SettingsRowId.Root.OPERATIONAL_LOGS).summary)
    }

    @Test
    fun readyDiagnosticsAndLogsSummariesExposeOnlyCountsAndSeverity() {
        val catalog = settingsCatalog(
            settingsSnapshot(
                diagnostics = EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.WARN, emptyList()),
                logs = readyOperationalLogs(2),
            ),
        )

        assertEquals("0 项检查 · 需注意", catalog.root(SettingsRowId.Root.DIAGNOSTICS).summary)
        assertEquals("2 条事件", catalog.root(SettingsRowId.Root.OPERATIONAL_LOGS).summary)
    }

    @Test
    fun logExportIsARealActionOnlyWhenEntriesExistAndExporterIsIdle() {
        val readyRow = settingsCatalog(settingsSnapshot(logs = readyOperationalLogs(2))).operationalLogs.rows.single()
        val export = assertIs<SettingsAction.ExportOperationalLogs>(
            assertIs<SettingsRowTarget.Action>(readyRow.target).action,
        )
        assertEquals(SettingsActionId.EXPORT_OPERATIONAL_LOGS, export.id)
        assertEquals(2, export.entries.size)

        val busyRow = settingsCatalog(
            settingsSnapshot(logs = readyOperationalLogs(1), exportBusy = true),
        ).operationalLogs.rows.single()
        assertEquals("正在导出日志", busyRow.summary)
        assertFalse(busyRow.enabled)
        assertNull(busyRow.target)

        val emptyRow = settingsCatalog(
            settingsSnapshot(logs = OperationalLogUiState.Empty),
        ).operationalLogs.rows.single()
        assertFalse(emptyRow.enabled)
        assertNull(emptyRow.target)
    }

    @Test
    fun routesAndAboutVersionAreStableAndSanitized() {
        SettingsRouteId.entries.forEach { route ->
            assertEquals(route, SettingsRouteId.fromWire(route.wireValue))
        }
        assertNull(SettingsRouteId.fromWire("../private"))
        assertNull(SettingsRouteId.fromWire("logs"))

        assertEquals("版本 fixture-desktop", settingsCatalog(settingsSnapshot()).about.versionLabel)
        assertEquals(
            "版本 未知",
            settingsCatalog(settingsSnapshot(appVersion = "invalid version with spaces")).about.versionLabel,
        )
    }

    @Test
    fun everyEnabledRowHasARealTargetAndPresentationContainsNoSensitiveCopy() {
        val catalog = settingsCatalog(settingsSnapshot())
        val rows = catalog.groups.flatMap { it.rows } +
            catalog.configuration.rows + catalog.operationalLogs.rows + catalog.callNotification.rows

        assertTrue(rows.all { it.enabled == (it.target != null) })
        assertFalse(
            rows.joinToString().contains(
                Regex("ICCID|IMSI|MSISDN|/data/|owner|token", RegexOption.IGNORE_CASE),
            ),
        )
    }
}

private fun SettingsCatalog.root(id: SettingsRowId.Root) =
    groups.flatMap { it.rows }.single { it.id == id }
