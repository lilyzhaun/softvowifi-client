package com.vowifi.sw

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import com.vowifi.sw.manager.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class,
    shadows = [RuntimeApplicationShadow::class, CallAdmissionManagerShadow::class])
class IncomingCallAdmissionHostTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Test fun rejectedCallStylePostsActionablePrivateNativeForegroundNotificationOnce() {
        withForegroundCall { fixture, coordinator ->
            CallAdmissionManagerShadow.rejection = ADMISSION_ERROR
            coordinator.setAppForeground(false)
            val notification = manager.activeNotifications.single().notification
            assertEquals(2, CallAdmissionManagerShadow.attempts)
            assertEquals("android.app.Notification\$CallStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
            assertNotEquals(0, notification.flags and Notification.FLAG_FOREGROUND_SERVICE)
            assertEquals(Notification.CATEGORY_CALL, notification.category)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals("来电", notification.extras.getString(Notification.EXTRA_TEXT))
            assertEquals(AndroidIncomingCallNotificationCoordinator.CHANNEL_ID, notification.channelId)
            assertNotNull(notification.fullScreenIntent)
            val key = requireNotNull(coordinator.keyForCurrent())
            val answer = notification.actions.single {
                shadowOf(it.actionIntent).savedIntent.action == IncomingCallAction.ANSWER.actionName
            }.actionIntent
            val reject = notification.actions.single {
                shadowOf(it.actionIntent).savedIntent.action == IncomingCallAction.REJECT.actionName
            }.actionIntent
            assertTrue(answer.isActivity && answer.isImmutable)
            assertTrue(reject.isBroadcast && reject.isImmutable)
            assertEquals(CallActivity::class.java.name, shadowOf(answer).savedIntent.component?.className)
            assertEquals(IncomingCallAction.ANSWER.actionName, shadowOf(answer).savedIntent.action)
            assertEquals(key, shadowOf(answer).savedIntent.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY))
            assertFalse(notification.publicVersion.extras.toString().contains("+12025550123"))
            assertFalse(notification.publicVersion.extras.toString().contains(key))
            assertTrue(fixture.commands.isEmpty())
            assertNull(shadowOf(context).nextStartedActivity)
            coordinator.setCallForeground(false)
            assertEquals(2, CallAdmissionManagerShadow.attempts)
            coordinator.handleAction(IncomingCallAction.REJECT, "stale-key")
            assertTrue(fixture.commands.isEmpty())
            coordinator.setAppForeground(true)
            assertTrue(manager.activeNotifications.isEmpty())
        }
    }

    @Test fun normalSystemKeepsCallStyleWithoutAlternativePost() {
        withForegroundCall { _, coordinator ->
            coordinator.setAppForeground(false)
            assertEquals(1, CallAdmissionManagerShadow.attempts)
            assertEquals("android.app.Notification\$CallStyle",
                manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEMPLATE))
        }
    }

    @Test fun unrelatedIllegalArgumentIsNotHiddenOrRetried() {
        withForegroundCall { _, coordinator ->
            CallAdmissionManagerShadow.rejection = "Invalid notification channel"
            val error = assertThrows(IllegalArgumentException::class.java) { coordinator.setAppForeground(false) }
            assertEquals("Invalid notification channel", error.message)
            assertEquals(1, CallAdmissionManagerShadow.attempts)
            assertTrue(manager.activeNotifications.isEmpty())
        }
    }

    @Test fun foregroundFailureDoesNotCacheSuccessfulPost() {
        withForegroundCall { _, coordinator ->
            CallAdmissionManagerShadow.rejection = ADMISSION_ERROR
            CallAdmissionManagerShadow.failForeground = true
            assertThrows(SecurityException::class.java) { coordinator.setAppForeground(false) }
            assertTrue(manager.activeNotifications.isEmpty())
            CallAdmissionManagerShadow.failForeground = false
            coordinator.setCallForeground(false)
            assertEquals(4, CallAdmissionManagerShadow.attempts)
            assertEquals(1, manager.activeNotifications.size)
        }
    }

    private fun withForegroundCall(block: (CallPlatformFixture, AndroidIncomingCallNotificationCoordinator) -> Unit) {
        CallAdmissionManagerShadow.rejection = null
        CallAdmissionManagerShadow.failForeground = false
        CallAdmissionManagerShadow.attempts = 0
        val fixture = CallPlatformFixture("RINGING")
        val coordinator = fixture.coordinator(context)
        val foreground = Robolectric.buildService(AdmissionForegroundService::class.java).create()
        try {
            coordinator.setAppForeground(true)
            coordinator.start()
            shadowOf(Looper.getMainLooper()).idle()
            coordinator.attachForegroundHost(foreground.get(), {
                foreground.get().startForeground(AndroidIncomingCallNotificationCoordinator.NOTIFICATION_ID, it)
            }, {})
            block(fixture, coordinator)
        } finally { coordinator.stop(); foreground.destroy(); fixture.close() }
    }
}

@Implements(NotificationManager::class)
class CallAdmissionManagerShadow : ShadowNotificationManager() {
    @Implementation(minSdk = 34) fun canUseFullScreenIntent() = true
    @Implementation override fun notify(id: Int, notification: Notification) {
        attempts++
        if (notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) {
            if (failForeground) throw SecurityException("Synthetic foreground post failure")
        } else if (notification.extras.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification\$CallStyle") {
            rejection?.let { throw IllegalArgumentException(it) }
        }
        super.notify(id, notification)
    }
    companion object {
        var rejection: String? = null
        var failForeground = false
        var attempts = 0
    }
}

class AdmissionForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}

private const val ADMISSION_ERROR = "Not posted. CallStyle notifications must be for a foreground service or user initated job or use a fullScreenIntent."
