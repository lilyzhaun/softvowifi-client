package com.vowifi.sw

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.provider.Settings
import com.vowifi.sw.manager.data.AndroidCallNotificationSettingsSource
import com.vowifi.sw.manager.data.CallNotificationSettingsLaunchResult
import com.vowifi.sw.manager.ui.SettingsAction
import com.vowifi.sw.manager.ui.ObserverChannelStatus
import com.vowifi.sw.manager.ui.NotificationPermissionStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ObserverChannelSettingsSourceTest {
    @Test fun observerActionExistsAndMissingChannelOpensAppWithoutCreation() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        val action = observerAction()
        assertNotNull("Observer channel action must exist", action)
        val source = AndroidCallNotificationSettingsSource(app)
        assertEquals(ObserverChannelStatus.MISSING, source.state.value.observerChannel)
        assertEquals(CallNotificationSettingsLaunchResult.OPENED, source.openSettings(requireNotNull(action)))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertNull(manager.getNotificationChannel("ims_observer"))
    }

    @Test fun existingChannelIntentTargetsOnlyObserverAndRefreshPreservesImportance() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("ims_observer", "Observer", NotificationManager.IMPORTANCE_LOW))
        val action = observerAction()
        assertNotNull("Observer channel action must exist", action)
        val source = AndroidCallNotificationSettingsSource(app)
        source.refresh()
        assertEquals(ObserverChannelStatus.ENABLED, source.state.value.observerChannel)
        assertEquals(CallNotificationSettingsLaunchResult.OPENED, source.openSettings(requireNotNull(action)))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals("ims_observer", intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        source.refresh()
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("ims_observer").importance)
    }

    private fun observerAction(): SettingsAction? = SettingsAction::class.java.declaredClasses
        .firstOrNull { it.simpleName == "ManageObserverChannel" }
        ?.getField("INSTANCE")?.get(null) as? SettingsAction

    @Test fun blockedObserverRemainsBlockedAcrossRefreshAndDoesNotAffectOtherChannels() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("ims_observer", "Observer", NotificationManager.IMPORTANCE_NONE))
        manager.createNotificationChannel(NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel("incoming_calls", "Calls", NotificationManager.IMPORTANCE_HIGH))
        val source = AndroidCallNotificationSettingsSource(app)
        assertEquals(ObserverChannelStatus.BLOCKED, source.state.value.observerChannel)
        shadowOf(manager).setNotificationsEnabled(false)
        source.refresh()
        assertEquals(NotificationPermissionStatus.DENIED, source.state.value.permission)
        assertEquals(ObserverChannelStatus.BLOCKED, source.state.value.observerChannel)
        shadowOf(manager).setNotificationsEnabled(true)
        source.refresh()
        assertEquals(ObserverChannelStatus.BLOCKED, source.state.value.observerChannel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("messages").importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("incoming_calls").importance)
    }

    @Test fun highObserverIsEnabledButGlobalPermissionIsSeparate() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("ims_observer", "Observer", NotificationManager.IMPORTANCE_HIGH))
        shadowOf(manager).setNotificationsEnabled(false)
        val source = AndroidCallNotificationSettingsSource(app)
        assertEquals(ObserverChannelStatus.ENABLED, source.state.value.observerChannel)
        assertEquals(NotificationPermissionStatus.DENIED, source.state.value.permission)
        source.refresh()
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("ims_observer").importance)
    }
}
