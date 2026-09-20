package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsCatalogTest {
    @Test
    fun rootCatalogHasExactGroupRowAndRouteOrder() {
        val catalog = settingsCatalog(settingsSnapshot())

        assertEquals(
            listOf(
                SettingsGroupId.APPEARANCE,
                SettingsGroupId.SIM_AND_NETWORK,
                SettingsGroupId.TROUBLESHOOTING,
                SettingsGroupId.INCOMING_CALL,
                SettingsGroupId.ABOUT,
            ),
            catalog.groups.map { it.id },
        )
        assertEquals(
            listOf(
                SettingsRowId.Root.APPEARANCE,
                SettingsRowId.Root.CONFIGURATION,
                SettingsRowId.Root.DIAGNOSTICS,
                SettingsRowId.Root.OPERATIONAL_LOGS,
                SettingsRowId.Root.CALL_NOTIFICATION,
                SettingsRowId.Root.ABOUT,
            ),
            catalog.groups.flatMap { it.rows }.map { it.id },
        )
        assertEquals(
            SettingsRouteId.entries,
            catalog.groups.flatMap { it.rows }.map { (it.target as SettingsRowTarget.Navigate).route },
        )
        assertEquals(
            listOf(
                "settings/appearance",
                "settings/configuration",
                "settings/diagnostics",
                "settings/operational-logs",
                "settings/call-notification",
                "settings/about",
            ),
            SettingsRouteId.entries.map { it.wireValue },
        )
    }

    @Test
    fun rootCatalogUsesExactChineseHubLabels() {
        val catalog = settingsCatalog(settingsSnapshot())

        assertEquals(
            listOf("界面与交互", "SIM 与网络", "诊断与日志", "来电通知", "关于"),
            catalog.groups.map { it.title },
        )
        assertEquals(
            "外观设置",
            catalog.groups.single { it.id == SettingsGroupId.APPEARANCE }.rows.single().title,
        )
    }

    @Test
    fun pagesHaveExactRefreshPolicies() {
        val catalog = settingsCatalog(settingsSnapshot())

        assertEquals(
            SettingsRefreshPolicy.OnEnter(setOf(RefreshTarget.CONFIGURATION, RefreshTarget.SIM_CONTROL)),
            catalog.configuration.refreshPolicy,
        )
        assertEquals(
            SettingsRefreshPolicy.OnEnter(setOf(RefreshTarget.ENVIRONMENT_DIAGNOSTICS)),
            catalog.diagnostics.refreshPolicy,
        )
        assertEquals(
            SettingsRefreshPolicy.OnEnter(setOf(RefreshTarget.OPERATIONAL_LOGS)),
            catalog.operationalLogs.refreshPolicy,
        )
        assertEquals(SettingsRefreshPolicy.None, catalog.callNotification.refreshPolicy)
        assertEquals(SettingsRefreshPolicy.None, catalog.appearance.refreshPolicy)
        assertEquals(SettingsRefreshPolicy.None, catalog.about.refreshPolicy)
    }

    @Test
    fun configuredRowsCarryRealApplyTargetsForOneAndMultipleSims() {
        val single = settingsCatalog(settingsSnapshot()).configuration.rows.single()
        val singleTarget = assertIs<SettingsRowTarget.Action>(single.target)
        assertEquals(SettingsRowId.Configuration.Configured(ConfigurationInstanceId("sim-1")), single.id)
        assertEquals(
            SettingsAction.ApplyConfiguration(ConfigurationInstanceId("sim-1")),
            singleTarget.action,
        )

        val multiple = settingsCatalog(
            settingsSnapshot(
                configuration = ConfigurationUiState.Ready(
                    listOf(configurationInstance("sim-1"), configurationInstance("sim-2")),
                ),
                sims = SimControlUiState.Ready(
                    listOf(
                        simInstance("sim-1", SimPresence.ACTIVE_MATCHED),
                        simInstance("sim-2", SimPresence.ACTIVE_MATCHED),
                    ),
                ),
            ),
        )
        assertEquals(2, multiple.configuration.rows.size)
        assertEquals(
            "2 张 SIM 可配置",
            multiple.groups.single { it.id == SettingsGroupId.SIM_AND_NETWORK }.rows.single().summary,
        )
        assertTrue(multiple.configuration.rows.all { it.target is SettingsRowTarget.Action })
    }

    @Test
    fun unconfiguredRowCarriesRealProvisionTarget() {
        val row = settingsCatalog(
            settingsSnapshot(
                configuration = ConfigurationUiState.Empty,
                sims = SimControlUiState.Ready(
                    listOf(
                        simInstance(
                            "sim-new",
                            SimPresence.ACTIVE_UNCONFIGURED,
                            canApply = false,
                            canProvision = true,
                        ),
                    ),
                ),
            ),
        ).configuration.rows.single()

        assertEquals(SettingsRowId.Configuration.ActiveSim(SimHandle("sim-new")), row.id)
        assertEquals(
            SettingsAction.ProvisionConfiguration(SimHandle("sim-new")),
            assertIs<SettingsRowTarget.Action>(row.target).action,
        )
    }

    @Test
    fun busyAndUnavailableConfigurationRowsPreventNoOpActions() {
        val busy = settingsCatalog(
            settingsSnapshot(
                configuration = ConfigurationUiState.Ready(
                    listOf(configurationInstance("sim-1")),
                    applyingInstanceId = ConfigurationInstanceId("sim-1"),
                ),
            ),
        )
        assertEquals(
            "正在保存配置",
            busy.groups.single { it.id == SettingsGroupId.SIM_AND_NETWORK }.rows.single().summary,
        )
        assertFalse(busy.configuration.rows.single().enabled)
        assertNull(busy.configuration.rows.single().target)

        val unavailable = settingsCatalog(
            settingsSnapshot(
                configuration = ConfigurationUiState.Unavailable("raw"),
                sims = SimControlUiState.Failure("raw"),
            ),
        )
        val root = unavailable.groups.single { it.id == SettingsGroupId.SIM_AND_NETWORK }.rows.single()
        assertEquals("当前环境不提供配置管理", root.summary)
        assertFalse(root.enabled)
        assertNull(root.target)
    }

    @Test
    fun operationalLogsWarningSummaryRendersExactTwoLines() {
        val entry = com.vowifi.sw.manager.model.OperationalLogEntry(
            "12:00",
            com.vowifi.sw.manager.model.OperationalLogLevel.INFO,
            com.vowifi.sw.manager.model.OperationalLogKind.ENGINE,
            "SIM 1",
            com.vowifi.sw.manager.model.OperationalLogMessage.ENGINE_STARTED,
            "engine started",
        )
        val warningState = com.vowifi.sw.manager.model.OperationalLogUiState.Warning(
            entries = listOf(entry, entry, entry),
            truncated = true,
            issues = emptyList(),
        )

        assertEquals("3 条事件\n可能不完整", operationalLogsSummary(warningState))
    }

    @Test
    fun rowInvariantRejectsEnabledNoOpAndDisabledTarget() {
        val navigation = SettingsRowTarget.Navigate(SettingsRouteId.ABOUT)

        assertFailsSettingsRow(enabled = true, target = null)
        assertFailsSettingsRow(enabled = false, target = navigation)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            SettingsRefreshPolicy.OnEnter(emptySet())
        }
    }
}

private fun assertFailsSettingsRow(enabled: Boolean, target: SettingsRowTarget?) {
    kotlin.test.assertFailsWith<IllegalArgumentException> {
        SettingsRowPresentation(SettingsRowId.Root.ABOUT, "标题", "摘要", enabled, target)
    }
}
