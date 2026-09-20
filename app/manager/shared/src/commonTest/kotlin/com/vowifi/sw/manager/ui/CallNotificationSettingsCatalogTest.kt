package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallNotificationSettingsCatalogTest {
    @Test
    fun deniedCurrentSnapshotExposesOnlyRealRecoveryActions() {
        val catalog = settingsCatalog(
            settingsSnapshot(
                notification = CallNotificationSettingsSnapshot(
                    NotificationPermissionStatus.DENIED,
                    FullScreenStatus.DENIED,
                ),
            ),
        )

        assertEquals(
            listOf(
                SettingsAction.ManageNotificationPermission,
                SettingsAction.ManageFullScreenIntent,
            ),
            catalog.callNotification.rows.mapNotNull { (it.target as? SettingsRowTarget.Action)?.action },
        )
        assertEquals(
            "需要允许来电通知",
            catalog.groups.single { it.id == SettingsGroupId.INCOMING_CALL }.rows.single().summary,
        )
    }

    @Test
    fun grantedStatesKeepRealManagementAndUnavailableStatesPreventNoOpRecovery() {
        val granted = settingsCatalog(
            settingsSnapshot(
                notification = CallNotificationSettingsSnapshot(
                    NotificationPermissionStatus.GRANTED,
                    FullScreenStatus.ALLOWED,
                ),
            ),
        )
        assertTrue(granted.callNotification.rows.first().enabled)
        assertTrue(granted.callNotification.rows.single { it.id == SettingsRowId.FullScreenIntent }.enabled)

        val unavailable = settingsCatalog(
            settingsSnapshot(
                notification = CallNotificationSettingsSnapshot(
                    NotificationPermissionStatus.UNAVAILABLE,
                    FullScreenStatus.UNAVAILABLE,
                ),
            ),
        )
        assertFalse(unavailable.groups.single { it.id == SettingsGroupId.INCOMING_CALL }.rows.single().enabled)
        assertTrue(unavailable.callNotification.rows.all { !it.enabled && it.target == null })

        val recoverFullScreen = settingsCatalog(
            settingsSnapshot(
                notification = CallNotificationSettingsSnapshot(
                    NotificationPermissionStatus.UNAVAILABLE,
                    FullScreenStatus.DENIED,
                ),
            ),
        )
        assertEquals(
            SettingsAction.ManageFullScreenIntent,
            assertIs<SettingsRowTarget.Action>(recoverFullScreen.callNotification.rows[1].target).action,
        )
        assertNull(recoverFullScreen.callNotification.rows[0].target)
    }

    @Test
    fun staleSnapshotKeepsRefreshPageReachableWhileMalformedStateStaysDisabled() {
        listOf(NotificationSnapshotFreshness.STALE, NotificationSnapshotFreshness.MALFORMED).forEach { freshness ->
            val catalog = settingsCatalog(
                settingsSnapshot(
                    notification = CallNotificationSettingsSnapshot(
                        NotificationPermissionStatus.DENIED,
                        FullScreenStatus.DENIED,
                        freshness,
                    ),
                ),
            )

            val root = catalog.groups.single { it.id == SettingsGroupId.INCOMING_CALL }.rows.single()
            assertEquals("通知状态需要刷新", root.summary)
            assertEquals(freshness == NotificationSnapshotFreshness.STALE, root.enabled)
            assertTrue(catalog.callNotification.rows.all { it.target == null })
        }
    }

    @Test
    fun adapterContractReturnsOnlyTheStableAbstractSnapshot() {
        val expected = CallNotificationSettingsSnapshot(
            NotificationPermissionStatus.GRANTED,
            FullScreenStatus.DENIED,
        )
        val adapter = CallNotificationSettingsSnapshotAdapter { expected }

        assertEquals(expected, adapter.currentSnapshot())
    }

    @Test fun channelTransitionsChangeProjectionWithoutChangingAppPermission() {
        val base = CallNotificationSettingsSnapshot(NotificationPermissionStatus.GRANTED, FullScreenStatus.NOT_REQUIRED)
        val summaries = mutableSetOf<String>()
        listOf(IncomingCallChannelStatus.BLOCKED, IncomingCallChannelStatus.REDUCED,
            IncomingCallChannelStatus.HIGH, IncomingCallChannelStatus.MISSING).forEach { channel ->
            val snapshot = base.copy(channel = channel)
            val page = settingsCatalog(settingsSnapshot(notification = snapshot)).callNotification
            assertEquals("已允许 · 管理应用通知", page.rows.first().summary)
            val row = page.rows.single { it.id == SettingsRowId.IncomingCallChannel }
            summaries += row.summary
            assertEquals(channel != IncomingCallChannelStatus.MISSING, row.enabled)
            assertFalse(page.rows.single { it.id == SettingsRowId.FullScreenIntent }.enabled)
            assertFalse(callNotificationSummary(snapshot).contains("已就绪"))
        }
        assertEquals(4, summaries.size)
    }

    @Test fun unsupportedSourceAndFailedActionsRemainTruthfulAndExactlyOnce() {
        val source = com.vowifi.sw.manager.data.UnsupportedCallNotificationSettingsSource
        source.refresh()
        assertEquals(NotificationPermissionStatus.UNAVAILABLE, source.state.value.permission)
        assertTrue(callNotificationPreferenceRows(source.state.value).none { it.actionable })
        var invocations = 0
        val action = SettingsAction.ManageNotificationPermission
        val feedback = CallNotificationSettingsFeedback()
        feedback.open(action) { invocations++; com.vowifi.sw.manager.data.CallNotificationSettingsLaunchResult.FAILED }
        assertEquals(1, invocations)
        assertEquals(action, feedback.failedAction)
        assertTrue(feedback.message!!.contains("系统设置"))
        feedback.open(action) { invocations++; com.vowifi.sw.manager.data.CallNotificationSettingsLaunchResult.OPENED }
        assertEquals(2, invocations)
        assertNull(feedback.failedAction)
        assertNull(feedback.message)
    }
}
