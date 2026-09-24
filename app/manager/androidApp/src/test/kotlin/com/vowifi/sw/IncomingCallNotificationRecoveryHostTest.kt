package com.vowifi.sw

import android.app.NotificationManager
import android.os.Looper
import com.vowifi.sw.manager.data.AndroidIncomingCallNotificationCoordinator
import com.vowifi.sw.manager.data.IncomingCallAction
import com.vowifi.sw.manager.data.IncomingCallRecoveryState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
class IncomingCallNotificationRecoveryHostTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Test fun foregroundSuppressedRingingPostsExactlyOnceWhenAppBackgrounds() {
        val fixture = CallPlatformFixture("RINGING")
        val coordinator = fixture.coordinator(context)
        try {
            coordinator.setAppForeground(true)
            coordinator.start()
            idle()
            val key = coordinator.keyForCurrent()
            assertIncomingCount(0)
            assertEquals(IncomingCallRecoveryState.NONE, coordinator.recoveryState.value)

            coordinator.setAppForeground(false)
            assertIncomingCount(1)
            assertEquals(key, coordinator.keyForCurrent())
            assertRepeatedUpdatesDoNotRepost(fixture, coordinator)
        } finally { coordinator.stop(); fixture.close() }
    }

    @Test fun callForegroundSuppressionWaitsUntilBothActivitiesAreBackgrounded() {
        val fixture = CallPlatformFixture("RINGING")
        val coordinator = fixture.coordinator(context)
        try {
            coordinator.setAppForeground(true)
            coordinator.setCallForeground(true)
            coordinator.start()
            idle()
            coordinator.setAppForeground(false)
            assertIncomingCount(0)
            coordinator.setCallForeground(false)
            assertIncomingCount(1)
            assertRepeatedUpdatesDoNotRepost(fixture, coordinator)
        } finally { coordinator.stop(); fixture.close() }
    }

    @Test fun deniedPostRecoversWhenPermissionReturnsAndSameStateIsReevaluated() {
        val fixture = CallPlatformFixture("RINGING")
        val coordinator = fixture.coordinator(context)
        try {
            shadowOf(manager).setNotificationsEnabled(false)
            coordinator.start()
            idle()
            val key = coordinator.keyForCurrent()
            assertIncomingCount(0)
            assertEquals(IncomingCallRecoveryState.NOTIFICATIONS_DENIED, coordinator.recoveryState.value)

            shadowOf(manager).setNotificationsEnabled(true)
            coordinator.setCallForeground(false)
            assertIncomingCount(1)
            assertEquals(key, coordinator.keyForCurrent())
            assertEquals(IncomingCallRecoveryState.NONE, coordinator.recoveryState.value)
            assertRepeatedUpdatesDoNotRepost(fixture, coordinator)
        } finally { coordinator.stop(); fixture.close() }
    }

    @Test fun suppressedCallThatEndsIsNotResurrectedOnBackgroundOrPermissionRecovery() {
        val fixture = CallPlatformFixture("RINGING")
        val coordinator = fixture.coordinator(context)
        try {
            coordinator.setAppForeground(true)
            shadowOf(manager).setNotificationsEnabled(false)
            coordinator.start()
            idle()
            val retiredKey = requireNotNull(coordinator.keyForCurrent())
            fixture.stage = "IDLE"
            fixture.revision++
            fixture.source.refresh()
            idle()

            shadowOf(manager).setNotificationsEnabled(true)
            coordinator.setAppForeground(false)
            coordinator.setCallForeground(false)
            assertIncomingCount(0)
            assertNull(coordinator.keyForCurrent())
            assertNull(coordinator.resolveKey(retiredKey))
            coordinator.handleAction(IncomingCallAction.REJECT, retiredKey)
            assertTrue(fixture.commands.isEmpty())
        } finally { coordinator.stop(); fixture.close() }
    }

    @Test fun replacementCallRecoversWithoutRevivingDeniedCallKey() {
        val fixture = CallPlatformFixture("RINGING")
        val coordinator = fixture.coordinator(context)
        try {
            shadowOf(manager).setNotificationsEnabled(false)
            coordinator.start()
            idle()
            val retiredKey = requireNotNull(coordinator.keyForCurrent())
            fixture.token = "replacement-call"
            fixture.epoch = "replacement-epoch"
            fixture.revision++
            fixture.source.refresh()
            idle()
            val replacementKey = requireNotNull(coordinator.keyForCurrent())
            assertNotEquals(retiredKey, replacementKey)

            shadowOf(manager).setNotificationsEnabled(true)
            coordinator.setCallForeground(false)
            assertIncomingCount(1)
            assertNull(coordinator.resolveKey(retiredKey))
            assertNotNull(coordinator.resolveKey(replacementKey))
            coordinator.handleAction(IncomingCallAction.REJECT, retiredKey)
            assertTrue(fixture.commands.isEmpty())
            assertRepeatedUpdatesDoNotRepost(fixture, coordinator)
        } finally { coordinator.stop(); fixture.close() }
    }

    private fun assertRepeatedUpdatesDoNotRepost(
        fixture: CallPlatformFixture,
        coordinator: AndroidIncomingCallNotificationCoordinator,
    ) {
        val posted = shadowOf(manager).getNotification(AndroidIncomingCallNotificationCoordinator.NOTIFICATION_ID)
        repeat(3) {
            fixture.revision++
            fixture.source.refresh()
            idle()
            coordinator.setCallForeground(false)
        }
        assertIncomingCount(1)
        assertSame(posted, shadowOf(manager).getNotification(AndroidIncomingCallNotificationCoordinator.NOTIFICATION_ID))
    }

    private fun assertIncomingCount(expected: Int) = assertEquals(
        expected,
        manager.activeNotifications.count { it.id == AndroidIncomingCallNotificationCoordinator.NOTIFICATION_ID },
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
