package com.vowifi.sw

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import com.vowifi.sw.manager.data.AndroidCallNotificationSettingsSource
import com.vowifi.sw.manager.data.CallNotificationSettingsLaunchResult
import com.vowifi.sw.manager.ui.FullScreenStatus
import com.vowifi.sw.manager.ui.IncomingCallChannelStatus
import com.vowifi.sw.manager.ui.NotificationPermissionStatus
import com.vowifi.sw.manager.ui.NotificationSnapshotFreshness
import com.vowifi.sw.manager.ui.SettingsAction
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CallNotificationSettingsSourceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Test fun refreshReadsIndependentAppAndChannelStatesWithoutCreatingChannel() {
        val source = AndroidCallNotificationSettingsSource(context)
        assertEquals(IncomingCallChannelStatus.MISSING, source.state.value.channel)
        assertNull(manager.getNotificationChannel("incoming_calls"))
        manager.createNotificationChannel(NotificationChannel("incoming_calls", "Calls", NotificationManager.IMPORTANCE_NONE))
        source.refresh()
        assertEquals(NotificationPermissionStatus.GRANTED, source.state.value.permission)
        assertEquals(IncomingCallChannelStatus.BLOCKED, source.state.value.channel)
        shadowOf(manager).setNotificationsEnabled(false)
        source.refresh()
        assertEquals(NotificationPermissionStatus.DENIED, source.state.value.permission)
        assertEquals(IncomingCallChannelStatus.BLOCKED, source.state.value.channel)
        shadowOf(manager).setNotificationsEnabled(true)
        source.refresh()
        assertEquals(NotificationPermissionStatus.GRANTED, source.state.value.permission)
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel("incoming_calls").importance)
    }

    @Test fun reducedChannelIsNotHighAndRefreshNeverRaisesImportance() {
        manager.createNotificationChannel(NotificationChannel("incoming_calls", "Calls", NotificationManager.IMPORTANCE_DEFAULT))
        val source = AndroidCallNotificationSettingsSource(context)
        assertEquals(IncomingCallChannelStatus.REDUCED, source.state.value.channel)
        source.refresh()
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel("incoming_calls").importance)
    }

    @Test fun highChannelRemainsIndependentWhenAppIsDenied() {
        manager.createNotificationChannel(NotificationChannel("incoming_calls", "Calls", NotificationManager.IMPORTANCE_HIGH))
        shadowOf(manager).setNotificationsEnabled(false)
        val source = AndroidCallNotificationSettingsSource(context)
        assertEquals(IncomingCallChannelStatus.HIGH, source.state.value.channel)
        assertEquals(NotificationPermissionStatus.DENIED, source.state.value.permission)
    }

    @Test fun realAppAndChannelIntentsHaveExactExtrasAndOneLaunch() {
        val source = AndroidCallNotificationSettingsSource(context)
        assertEquals(CallNotificationSettingsLaunchResult.OPENED, source.openSettings(SettingsAction.ManageNotificationPermission))
        val appIntent = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, appIntent.action)
        assertEquals(context.packageName, appIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertTrue(appIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(shadowOf(context).nextStartedActivity)
        manager.createNotificationChannel(NotificationChannel("incoming_calls", "Calls", NotificationManager.IMPORTANCE_HIGH))
        source.refresh()
        assertEquals(CallNotificationSettingsLaunchResult.OPENED, source.openSettings(SettingsAction.ManageIncomingCallChannel))
        val channelIntent = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channelIntent.action)
        assertEquals(context.packageName, channelIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals("incoming_calls", channelIntent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertNull(shadowOf(context).nextStartedActivity)
        assertEquals(NotificationSnapshotFreshness.STALE, source.state.value.freshness)
        shadowOf(manager).setNotificationsEnabled(false)
        source.refresh()
        assertEquals(NotificationSnapshotFreshness.CURRENT, source.state.value.freshness)
        assertEquals(NotificationPermissionStatus.DENIED, source.state.value.permission)
    }

    @Test fun api33HasNoSpecialFullScreenSettingsAction() {
        val source = AndroidCallNotificationSettingsSource(context)
        assertEquals(FullScreenStatus.NOT_REQUIRED, source.state.value.fullScreen)
        assertEquals(CallNotificationSettingsLaunchResult.UNSUPPORTED, source.openSettings(SettingsAction.ManageFullScreenIntent))
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun missingChannelActionDoesNotPretendToOpenSettings() {
        val source = AndroidCallNotificationSettingsSource(context)
        assertEquals(CallNotificationSettingsLaunchResult.UNSUPPORTED, source.openSettings(SettingsAction.ManageIncomingCallChannel))
        assertNull(shadowOf(context).nextStartedActivity)
        assertEquals(IncomingCallChannelStatus.MISSING, source.state.value.channel)
    }

    @Test fun blockedChannelPreventsPostingAndReportsNotificationRecovery() {
        manager.createNotificationChannel(NotificationChannel("incoming_calls", "Calls", NotificationManager.IMPORTANCE_NONE))
        val coordinator = com.vowifi.sw.manager.data.AndroidIncomingCallNotificationCoordinator(
            context, kotlinx.coroutines.flow.MutableStateFlow(com.vowifi.sw.manager.model.CallUiState.Loading),
            com.vowifi.sw.manager.data.IncomingCallNotificationCallbacks({}, {}),
            IncomingCallActionReceiver::class.java, MainActivity::class.java,
        )
        val post = coordinator.javaClass.getDeclaredMethod("postNotification", String::class.java, String::class.java)
        post.isAccessible = true
        assertEquals(false, post.invoke(coordinator, "Unknown", "test-notification"))
        assertEquals(com.vowifi.sw.manager.data.IncomingCallRecoveryState.NOTIFICATIONS_DENIED, coordinator.recoveryState.value)
        assertEquals(0, shadowOf(manager).size())
        coordinator.stop()
    }

    @Test @Config(sdk = [34], shadows = [FullScreenNotificationManagerShadow::class])
    fun api34ReadsSpecialAccessAndOpensPackageSpecificIntent() {
        FullScreenNotificationManagerShadow.allowed = false
        val source = AndroidCallNotificationSettingsSource(context)
        assertEquals(FullScreenStatus.DENIED, source.state.value.fullScreen)
        assertEquals(CallNotificationSettingsLaunchResult.OPENED, source.openSettings(SettingsAction.ManageFullScreenIntent))
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
        assertNull(shadowOf(context).nextStartedActivity)
        FullScreenNotificationManagerShadow.allowed = true
        source.refresh()
        assertEquals(FullScreenStatus.ALLOWED, source.state.value.fullScreen)
    }

    @Test fun highChannelStillPostsThroughTheExistingCoordinator() {
        manager.createNotificationChannel(NotificationChannel("incoming_calls", "Calls", NotificationManager.IMPORTANCE_HIGH))
        val coordinator = com.vowifi.sw.manager.data.AndroidIncomingCallNotificationCoordinator(
            context, kotlinx.coroutines.flow.MutableStateFlow(com.vowifi.sw.manager.model.CallUiState.Loading),
            com.vowifi.sw.manager.data.IncomingCallNotificationCallbacks({}, {}),
            IncomingCallActionReceiver::class.java, MainActivity::class.java,
        )
        try {
            val post = coordinator.javaClass.getDeclaredMethod("postNotification", String::class.java, String::class.java)
            post.isAccessible = true
            assertEquals(true, post.invoke(coordinator, "Unknown", "test-notification"))
            assertEquals(1, shadowOf(manager).size())
        } finally {
            coordinator.stop()
        }
        assertEquals(0, shadowOf(manager).size())
    }

    @Test fun activityMissingAndSecurityFailureAreBoundedAndCanRetry() {
        listOf(ActivityNotFoundException("private detail"), SecurityException("private detail")).forEach { failure ->
            var launches = 0
            var failing = true
            val launcherContext = object : ContextWrapper(context) {
                override fun startActivity(intent: Intent) {
                    launches++
                    if (failing) throw failure
                    super.startActivity(intent)
                }
            }
            val source = AndroidCallNotificationSettingsSource(launcherContext)
            assertEquals(CallNotificationSettingsLaunchResult.FAILED, source.openSettings(SettingsAction.ManageNotificationPermission))
            assertEquals(1, launches)
            assertEquals(NotificationSnapshotFreshness.CURRENT, source.state.value.freshness)
            failing = false
            assertEquals(CallNotificationSettingsLaunchResult.OPENED, source.openSettings(SettingsAction.ManageNotificationPermission))
            assertEquals(2, launches)
        }
    }
}

@Implements(NotificationManager::class)
class FullScreenNotificationManagerShadow : org.robolectric.shadows.ShadowNotificationManager() {
    @Implementation(minSdk = 34) fun canUseFullScreenIntent(): Boolean = allowed
    companion object { var allowed = false }
}
