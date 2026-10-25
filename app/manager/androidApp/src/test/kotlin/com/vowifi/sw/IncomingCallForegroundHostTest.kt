package com.vowifi.sw

import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import android.os.VibratorManager
import android.media.AudioManager
import android.view.KeyEvent
import com.vowifi.sw.manager.data.AndroidIncomingCallNotificationCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class,
    shadows = [RuntimeApplicationShadow::class, CallAdmissionManagerShadow::class])
class IncomingCallForegroundHostTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Test fun silencedNativeCallStyleAlsoStaysSilentWhenTheObserverOwnsTheNotification() {
        val vibrator = shadowOf(context.getSystemService(VibratorManager::class.java).defaultVibrator)
        vibrator.setHasVibrator(true)
        context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_NORMAL
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        withRinging { fixture, coordinator ->
            assertTrue(vibrator.isVibrating)
            assertTrue(coordinator.handleRingingVolumeKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
            assertFalse(vibrator.isVibrating)
            val published = mutableListOf<Notification>(); var restores = 0
            coordinator.attachForegroundHost(Any(), { published += it }, { restores++ })
            CallAdmissionManagerShadow.rejection = ADMISSION_ERROR
            coordinator.setAppForeground(false)
            val notification = published.single()
            assertEquals("android.app.Notification\$CallStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
            assertNotNull(notification.fullScreenIntent)
            assertEquals(Notification.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
            assertNotNull(notification.group)
            assertEquals(0, notification.flags and (Notification.FLAG_INSISTENT or Notification.FLAG_GROUP_SUMMARY))
            coordinator.setAppForeground(true)
            assertEquals(1, restores); assertFalse(vibrator.isVibrating)
            assertTrue(fixture.commands.isEmpty())
        }
    }

    @Test fun rejectedCallStyleRemainsNativeAndIsOwnedByAttachedForegroundService() = withRinging { fixture, coordinator ->
        val host = Any()
        val published = mutableListOf<Notification>()
        var restores = 0
        coordinator.attachForegroundHost(host, { published += it }, { restores++ })
        CallAdmissionManagerShadow.rejection = ADMISSION_ERROR
        coordinator.setAppForeground(false)
        assertEquals(1, CallAdmissionManagerShadow.attempts)
        assertTrue(manager.activeNotifications.isEmpty())
        val notification = published.single()
        assertEquals("android.app.Notification\$CallStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(Notification.CATEGORY_CALL, notification.category)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.fullScreenIntent)
        assertEquals(0, notification.flags and Notification.FLAG_FOREGROUND_SERVICE)
        assertTrue(fixture.commands.isEmpty())
        coordinator.setCallForeground(false)
        assertEquals(1, published.size)
        coordinator.setAppForeground(true)
        assertEquals(1, restores)
    }

    @Test fun absentHostDoesNotPostPlainOrCacheSuccessAndAttachReevaluatesCurrentRinging() = withRinging { _, coordinator ->
        CallAdmissionManagerShadow.rejection = ADMISSION_ERROR
        coordinator.setAppForeground(false)
        assertEquals(1, CallAdmissionManagerShadow.attempts)
        assertTrue(manager.activeNotifications.isEmpty())
        val published = mutableListOf<Notification>()
        coordinator.attachForegroundHost(Any(), { published += it }, {})
        assertEquals(2, CallAdmissionManagerShadow.attempts)
        assertEquals("android.app.Notification\$CallStyle", published.single().extras.getString(Notification.EXTRA_TEMPLATE))
    }

    @Test fun failedForegroundPostIsNotHiddenOrCachedAsSuccessful() = withRinging { _, coordinator ->
        val host = Any()
        var failed = true
        var attempts = 0
        coordinator.attachForegroundHost(host, {
            attempts++
            if (failed) throw SecurityException("Synthetic foreground rejection")
        }, {})
        CallAdmissionManagerShadow.rejection = ADMISSION_ERROR
        assertThrows(SecurityException::class.java) { coordinator.setAppForeground(false) }
        assertTrue(manager.activeNotifications.isEmpty())
        failed = false
        coordinator.setCallForeground(false)
        assertEquals(2, attempts)
    }

    @Test fun detachOfOldServiceCannotReleaseNewServiceOwner() = withRinging { _, coordinator ->
        val old = Any()
        val current = Any()
        var oldRestores = 0
        var newPosts = 0
        coordinator.attachForegroundHost(old, {}, { oldRestores++ })
        CallAdmissionManagerShadow.rejection = ADMISSION_ERROR
        coordinator.setAppForeground(false)
        coordinator.attachForegroundHost(current, { newPosts++ }, {})
        coordinator.detachForegroundHost(old)
        assertEquals(1, oldRestores)
        assertEquals(1, newPosts)
        coordinator.setAppForeground(true)
    }

    @Test fun expiredRingingIsNotResurrectedWhenServiceArrives() = withRinging { fixture, coordinator ->
        CallAdmissionManagerShadow.rejection = ADMISSION_ERROR
        coordinator.setAppForeground(false)
        fixture.stage = "IDLE"
        fixture.revision++
        fixture.source.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        var posts = 0
        coordinator.attachForegroundHost(Any(), { posts++ }, {})
        assertEquals(0, posts)
        assertNull(coordinator.keyForCurrent())
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun productionObserverUsesRealStartForegroundAndRestoresObserverOnCallEnd() {
        CallAdmissionManagerShadow.rejection = null
        CallAdmissionManagerShadow.failForeground = false
        CallAdmissionManagerShadow.attempts = 0
        val fixture = CallPlatformFixture("RINGING")
        RuntimeApplicationShadow.callFixture = fixture
        val application = context as SoftVoWiFiApplication
        val coordinator = application.runtime().notifications
        val service = Robolectric.buildService(ObserverService::class.java)
        try {
            coordinator.setAppForeground(true)
            coordinator.start()
            shadowOf(Looper.getMainLooper()).idle()
            service.create()
            CallAdmissionManagerShadow.rejection = ADMISSION_ERROR
            coordinator.setAppForeground(false)
            val foreground = shadowOf(service.get()).lastForegroundNotification
            assertEquals("incoming_calls", foreground.channelId)
            assertEquals("android.app.Notification\$CallStyle", foreground.extras.getString(Notification.EXTRA_TEMPLATE))
            assertNotEquals(0, foreground.flags and Notification.FLAG_FOREGROUND_SERVICE)
            assertTrue(fixture.commands.isEmpty())
            fixture.stage = "IDLE"
            fixture.revision++
            fixture.source.refresh()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("ims_observer", shadowOf(service.get()).lastForegroundNotification.channelId)
            assertNull(shadowOf(manager).getNotification(AndroidIncomingCallNotificationCoordinator.NOTIFICATION_ID))
        } finally {
            service.destroy()
            coordinator.stop()
            fixture.close()
            RuntimeApplicationShadow.callFixture = null
        }
    }

    private fun withRinging(block: (CallPlatformFixture, AndroidIncomingCallNotificationCoordinator) -> Unit) {
        CallAdmissionManagerShadow.rejection = null
        CallAdmissionManagerShadow.failForeground = false
        CallAdmissionManagerShadow.attempts = 0
        val fixture = CallPlatformFixture("RINGING")
        val coordinator = fixture.coordinator(context)
        try {
            coordinator.setAppForeground(true)
            coordinator.start()
            shadowOf(Looper.getMainLooper()).idle()
            block(fixture, coordinator)
        } finally { coordinator.stop(); fixture.close() }
    }
}

private const val ADMISSION_ERROR = "Not posted. CallStyle notifications must be for a foreground service or user initated job or use a fullScreenIntent."
