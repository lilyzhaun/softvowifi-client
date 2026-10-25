package com.vowifi.sw

import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import com.vowifi.sw.manager.data.CallOngoingNotification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class,
    shadows = [RuntimeApplicationShadow::class, CallAdmissionManagerShadow::class])
class CallOngoingWithoutMicrophoneHostTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Test fun stoppedMicrophoneServiceReplacesCallStyleInsteadOfCrashing() = inCall { fixture, coordinator ->
        coordinator.setMicrophoneRunning(true)
        assertNull(ongoing())
        assertEquals(CALL_STYLE, shadowOf(manager).getNotification(CallOngoingNotification.ID)?.extras?.getString(Notification.EXTRA_TEMPLATE))
        // The service has stopped foreground while the observed call is still in progress (remote BYE, audio loss).
        CallAdmissionManagerShadow.rejection = ADMISSION_ERROR
        coordinator.setMicrophoneRunning(false)
        val plain = requireNotNull(ongoing())
        assertNull(shadowOf(manager).getNotification(CallOngoingNotification.ID))
        assertNotEquals(CALL_STYLE, plain.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals("挂断", plain.actions.single().title)
        fixture.stage = "IDLE"
        fixture.revision++
        fixture.source.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(ongoing())
        assertNull(shadowOf(manager).getNotification(CallOngoingNotification.ID))
    }

    @Test fun inCallObservedWithoutMicrophoneServiceNeverPostsUnbackedCallStyle() = inCall(rejected = true) { _, _ ->
        val plain = requireNotNull(ongoing())
        assertNotEquals(CALL_STYLE, plain.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(Notification.CATEGORY_CALL, plain.category)
    }

    @Test fun microphoneRecoverySwitchesKeysWithoutDuplicatingNotificationsAndStopClearsBoth() = inCall { _, coordinator ->
        repeat(3) {
            assertNotNull(ongoing())
            assertNull(shadowOf(manager).getNotification(CallOngoingNotification.ID))
            coordinator.setMicrophoneRunning(true)
            assertNull(ongoing())
            assertEquals(CALL_STYLE, shadowOf(manager).getNotification(CallOngoingNotification.ID)?.extras?.getString(Notification.EXTRA_TEMPLATE))
            coordinator.setMicrophoneRunning(false)
            assertNotNull(ongoing())
            assertNull(shadowOf(manager).getNotification(CallOngoingNotification.ID))
        }
        coordinator.stop()
        assertNull(ongoing())
        assertNull(shadowOf(manager).getNotification(CallOngoingNotification.ID))
    }

    private fun ongoing(): Notification? = shadowOf(manager).getNotification(CallOngoingNotification.WITHOUT_MICROPHONE_ID)

    private fun inCall(rejected: Boolean = false,
                       block: (CallPlatformFixture, com.vowifi.sw.manager.data.AndroidIncomingCallNotificationCoordinator) -> Unit) {
        CallAdmissionManagerShadow.rejection = if (rejected) ADMISSION_ERROR else null
        CallAdmissionManagerShadow.failForeground = false
        CallAdmissionManagerShadow.attempts = 0
        val fixture = CallPlatformFixture("IN_CALL")
        val coordinator = fixture.coordinator(context)
        try {
            coordinator.setAppForeground(true)
            coordinator.start()
            shadowOf(Looper.getMainLooper()).idle()
            block(fixture, coordinator)
        } finally { CallAdmissionManagerShadow.rejection = null; coordinator.stop(); fixture.close() }
    }
}

private const val CALL_STYLE = "android.app.Notification\$CallStyle"
private const val ADMISSION_ERROR = "Not posted. CallStyle notifications must be for a foreground service or user initated job or use a fullScreenIntent."
