package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSubpageGrammarTest {
    @Test
    fun callNotificationRowsUseRealNavigationOnlyForSupportedCapabilities() {
        // Given
        val snapshot = CallNotificationSettingsSnapshot(
            permission = NotificationPermissionStatus.DENIED,
            fullScreen = FullScreenStatus.ALLOWED,
        )

        // When
        val rows = callNotificationPreferenceRows(snapshot)

        // Then
        assertEquals(3, rows.size)
        assertTrue(rows[0].actionable)
        assertEquals("应用通知", rows[0].title)
        assertEquals("需要在系统设置中允许", rows[0].summary)
        assertEquals("全屏来电", rows[1].title)
        assertEquals("已允许 · 管理全屏访问", rows[1].summary)
        assertEquals("来电通知渠道", rows[2].title)
        assertFalse(rows[2].actionable)
        assertEquals("当前环境不支持", rows[2].summary)
    }

    @Test
    fun aboutRowsUseInformationPreferenceItemsWithSafeVersionAndNonActionableState() {
        // Given
        val appVersion = "1.2.3-alpha"
        val unsafeVersion = "1.0.0; DROP TABLE; /proc/secret"

        // When
        val safeRows = aboutInformationRows(appVersion)
        val sanitizedRows = aboutInformationRows(unsafeVersion)

        // Then
        assertEquals(listOf("应用版本", "应用信息", "模块版本", "引擎标识", "运行实例"), safeRows.map { it.title })
        assertTrue(safeRows.none { it.actionable })
        assertEquals("应用版本", safeRows[0].title)
        assertEquals("版本 1.2.3-alpha", safeRows[0].summary)
        assertEquals("应用信息", safeRows[1].title)
        assertEquals("SoftVoWiFi 管理应用", safeRows[1].summary)

        assertEquals("版本 未知", sanitizedRows[0].summary)
    }

    @Test
    fun subpageActionBundlesMaintainParameterDiscipline() {
        // Given
        var provisionInvoked = false
        val configActions = SettingsConfigurationActions(
            onSubmit = { _, _, _ -> com.vowifi.sw.manager.model.ConfigurationApplyAcceptance.Unavailable },
            onProvision = { provisionInvoked = true },
            onRefresh = { com.vowifi.sw.manager.data.RefreshResult.Completed },
        )
        var fileSelected = ""
        val logsActions = SettingsLogsActions(
            selectFile = { fileSelected = it },
            setKind = {},
            setLevel = {},
            setInstance = {},
            onExport = {},
        )

        // When
        configActions.onProvision(com.vowifi.sw.manager.model.SimHandle("sim1"))
        logsActions.selectFile("file_1")

        // Then
        assertTrue(provisionInvoked)
        assertEquals("file_1", fileSelected)
    }
}
