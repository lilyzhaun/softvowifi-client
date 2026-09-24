package com.vowifi.sw

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Looper
import com.vowifi.sw.manager.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class,
    shadows = [RuntimeApplicationShadow::class, FullScreenNotificationManagerShadow::class])
class IncomingCallStyleContractHostTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Test fun unlockedHeadsUpRetainsRequiredFullScreenMetadata() = assertPosted(locked = false, allowed = true)
    @Test fun deniedFullScreenStillRequestsMetadataForSystemHeadsUpDowngrade() = assertPosted(locked = true, allowed = false)
    @Test fun allowedLockedFullScreenKeepsDirectImmutableCallActivity() = assertPosted(locked = true, allowed = true)

    private fun assertPosted(locked: Boolean, allowed: Boolean) {
        FullScreenNotificationManagerShadow.allowed = allowed
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(locked)
        val fixture = CallPlatformFixture("RINGING")
        val coordinator = fixture.coordinator(context)
        try {
            coordinator.start()
            shadowOf(Looper.getMainLooper()).idle()
            val notification = manager.activeNotifications.single {
                it.id == AndroidIncomingCallNotificationCoordinator.NOTIFICATION_ID
            }.notification
            assertEquals("android.app.Notification\$CallStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
            val fullScreen = notification.fullScreenIntent
            assertNotNull(fullScreen)
            assertTrue(fullScreen.isImmutable)
            assertTrue(fullScreen.isActivity)
            val key = requireNotNull(coordinator.keyForCurrent())
            val fullScreenIntent = shadowOf(fullScreen).savedIntent
            assertEquals(CallActivity::class.java.name, fullScreenIntent.component?.className)
            assertEquals(key, fullScreenIntent.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY))
            val answer = notification.actions.single {
                shadowOf(it.actionIntent).savedIntent.action == IncomingCallAction.ANSWER.actionName
            }.actionIntent
            assertTrue(answer.isActivity)
            assertTrue(answer.isImmutable)
            assertEquals(CallActivity::class.java.name, shadowOf(answer).savedIntent.component?.className)
            val reject = notification.actions.single {
                shadowOf(it.actionIntent).savedIntent.action == IncomingCallAction.REJECT.actionName
            }.actionIntent
            assertTrue(reject.isBroadcast)
            assertTrue(reject.isImmutable)
            assertEquals(IncomingCallActionReceiver::class.java.name, shadowOf(reject).savedIntent.component?.className)
            assertFalse(context.packageManager.getReceiverInfo(ComponentName(context, IncomingCallActionReceiver::class.java), 0).exported)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals("+12025550123", notification.extras.getString(Notification.EXTRA_TITLE))
            assertFalse(notification.publicVersion.extras.toString().contains("+12025550123"))
            assertFalse(notification.publicVersion.extras.toString().contains(key))
            assertFalse(notification.publicVersion.extras.toString().contains(fixture.token))
            assertEquals(if (locked && !allowed) IncomingCallRecoveryState.FULL_SCREEN_DENIED else IncomingCallRecoveryState.NONE,
                coordinator.recoveryState.value)
            assertNull(shadowOf(context).nextStartedActivity)
        } finally { coordinator.stop(); fixture.close() }
    }

    @Test fun foregroundAndDeniedNotificationPermissionStillDoNotPost() {
        val fixture = CallPlatformFixture("RINGING")
        val coordinator = fixture.coordinator(context)
        try {
            coordinator.setAppForeground(true)
            coordinator.start()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(manager.activeNotifications.isEmpty())
            shadowOf(manager).setNotificationsEnabled(false)
            coordinator.setAppForeground(false)
            assertTrue(manager.activeNotifications.isEmpty())
            assertEquals(IncomingCallRecoveryState.NOTIFICATIONS_DENIED, coordinator.recoveryState.value)
        } finally { coordinator.stop(); fixture.close() }
    }

    @Test fun manifestDeclaresNormalVibrationPermission() {
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertTrue(Manifest.permission.VIBRATE in permissions)
    }

    @Test fun freshIncomingChannelDefaultsToHighImportanceAndVibration() {
        val fixture = CallPlatformFixture()
        val coordinator = fixture.coordinator(context)
        try {
            coordinator.start()
            val channel = manager.getNotificationChannel(AndroidIncomingCallNotificationCoordinator.CHANNEL_ID)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
            assertTrue(channel.shouldVibrate())
        } finally { coordinator.stop(); fixture.close() }
    }

    @Test fun existingSilentChannelKeepsUserVibrationSoundAndImportance() {
        manager.createNotificationChannel(NotificationChannel(AndroidIncomingCallNotificationCoordinator.CHANNEL_ID,
            "Existing", NotificationManager.IMPORTANCE_LOW).apply { enableVibration(false); setSound(null, null) })
        val fixture = CallPlatformFixture()
        val coordinator = fixture.coordinator(context)
        try {
            coordinator.start()
            val channel = manager.getNotificationChannel(AndroidIncomingCallNotificationCoordinator.CHANNEL_ID)
            assertFalse(channel.shouldVibrate())
            assertNull(channel.sound)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        } finally { coordinator.stop(); fixture.close() }
    }
}
