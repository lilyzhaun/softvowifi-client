package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.CallNotificationSettingsLaunchResult
import com.vowifi.sw.manager.data.UnsupportedCallNotificationSettingsSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObserverChannelSettingsCatalogTest {
    private val snapshot = CallNotificationSettingsSnapshot(NotificationPermissionStatus.GRANTED, FullScreenStatus.ALLOWED)

    @Test fun separateObserverRowPreservesThreeCallRowsAndHasNoFakeToggle() {
        assertEquals(3, callNotificationPage(snapshot).rows.size)
        ObserverChannelStatus.entries.forEach { status ->
            val row = observerChannelRow(snapshot.copy(observerChannel = status))
            assertEquals(status != ObserverChannelStatus.UNAVAILABLE, row.enabled)
            assertEquals("后台常驻通知", row.title)
            if (row.enabled) assertEquals(SettingsRowTarget.Action(SettingsAction.ManageObserverChannel), row.target)
        }
        assertFalse(observerChannelRow(snapshot.copy(observerChannel = ObserverChannelStatus.ENABLED,
            freshness = NotificationSnapshotFreshness.STALE)).enabled)
        assertEquals(CallNotificationSettingsLaunchResult.UNSUPPORTED,
            UnsupportedCallNotificationSettingsSource.openSettings(SettingsAction.ManageObserverChannel))
    }

    @Test fun appDenialCannotBePresentedAsObserverEnabled() {
        val row = observerChannelRow(snapshot.copy(permission = NotificationPermissionStatus.DENIED,
            observerChannel = ObserverChannelStatus.ENABLED))
        assertTrue(row.enabled)
        assertTrue(row.summary.contains("应用通知已关闭"))
        assertFalse(row.summary.contains("此渠道已开启"))
    }
}
