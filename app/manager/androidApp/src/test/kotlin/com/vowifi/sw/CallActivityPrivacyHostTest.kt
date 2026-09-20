package com.vowifi.sw

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
class CallActivityPrivacyHostTest {
    @Test fun malformedAndStaleLaunchesNeverShowOverLockScreen() {
        val fixture = CallPlatformFixture("RINGING")
        RuntimeApplicationShadow.callFixture = fixture
        try {
            Robolectric.buildActivity(CallActivity::class.java, Intent().putExtra("destination", "calls")).use { controller ->
                controller.create().start().resume()
                assertTrue(controller.get().isFinishing)
                assertFalse(shadowOf(controller.get()).showWhenLocked)
                assertFalse(shadowOf(controller.get()).turnScreenOn)
            }
            Robolectric.buildActivity(CallActivity::class.java, intent("stale-key")).use { controller ->
                controller.create().start().resume()
                assertTrue(controller.get().isFinishing)
                assertFalse(shadowOf(controller.get()).showWhenLocked)
                assertFalse(shadowOf(controller.get()).turnScreenOn)
            }
        } finally { RuntimeApplicationShadow.callFixture = null; fixture.close() }
    }

    @Test fun validatedCallRecreationDoesNotDisposeProcessSource() {
        val fixture = CallPlatformFixture("RINGING")
        RuntimeApplicationShadow.callFixture = fixture
        val runtime = (RuntimeEnvironment.getApplication() as SoftVoWiFiApplication).runtime()
        val key = runtime.notifications.keyForCurrent()!!
        try {
            Robolectric.buildActivity(CallActivity::class.java, intent(key)).use { controller ->
                controller.create().start().resume()
                assertTrue(shadowOf(controller.get()).showWhenLocked)
                assertTrue(shadowOf(controller.get()).turnScreenOn)
                controller.pause().stop().destroy()
            }
            fixture.stage = "IN_CALL"; fixture.revision++
            fixture.source.refresh()
            assertEquals(CallPhase.InCall, (fixture.source.state.value as CallUiState.Ready).phase)
            Robolectric.buildActivity(CallActivity::class.java, intent(key)).use { controller ->
                controller.create().start().resume()
                assertTrue(shadowOf(controller.get()).showWhenLocked)
                assertFalse(shadowOf(controller.get()).turnScreenOn)
                controller.newIntent(intent("another-stale-key"))
                assertFalse(shadowOf(controller.get()).showWhenLocked)
            }
        } finally { runtime.calls.lifecycle.close(); RuntimeApplicationShadow.callFixture = null; fixture.close() }
    }

    @Test fun currentSnapshotReconstructsOpaqueNotificationKeyButNewEpochRejectsIt() {
        val context = RuntimeEnvironment.getApplication()
        val first = CallPlatformFixture("RINGING")
        val second = CallPlatformFixture("RINGING")
        val firstCoordinator = first.coordinator(context)
        val secondCoordinator = second.coordinator(context)
        try {
            val key = firstCoordinator.keyForCurrent()!!
            assertEquals(key, secondCoordinator.keyForCurrent())
            assertNotNull(secondCoordinator.resolveKey(key))
            second.epoch = "new-epoch"; second.source.refresh()
            assertNull(secondCoordinator.resolveKey(key))
        } finally { firstCoordinator.stop(); secondCoordinator.stop(); first.close(); second.close() }
    }

    @Test fun incomingAndOngoingNotificationsKeepCallerOutOfPublicPreview() {
        val context = RuntimeEnvironment.getApplication()
        val fixture = CallPlatformFixture("RINGING")
        val coordinator = fixture.coordinator(context)
        try {
            coordinator.start()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val manager = context.getSystemService(NotificationManager::class.java)
            val incoming = manager.activeNotifications.single { it.id == AndroidIncomingCallNotificationCoordinator.NOTIFICATION_ID }.notification
            assertEquals(Notification.VISIBILITY_PRIVATE, incoming.visibility)
            assertNotNull(incoming.publicVersion)
            assertFalse(incoming.publicVersion.extras.toString().contains("+12025550123"))
            assertNotNull(incoming.contentIntent)
            val ongoing = CallOngoingNotification.build(context, coordinator.keyForCurrent(), fixture.source.state.value as CallUiState.Ready)
            assertEquals(Notification.VISIBILITY_PRIVATE, ongoing.visibility)
            assertFalse(ongoing.publicVersion.extras.toString().contains("+12025550123"))
        } finally { coordinator.stop(); fixture.close() }
    }

    @Test fun connectingNotificationHangupUsesCurrentKeyAndRejectsRetiredKey() {
        val fixture = CallPlatformFixture("CONNECTING")
        RuntimeApplicationShadow.callFixture = fixture
        val context = RuntimeEnvironment.getApplication() as SoftVoWiFiApplication
        val runtime = context.runtime()
        try {
            val key = runtime.notifications.keyForCurrent()!!
            val notification = CallOngoingNotification.build(context, key, fixture.source.state.value as CallUiState.Ready)
            val action = notification.actions.single()
            assertEquals("挂断", action.title)
            val launch = shadowOf(action.actionIntent).savedIntent
            IncomingCallActionReceiver().onReceive(context, launch)
            assertEquals(listOf("hangup"), fixture.commands)
            fixture.epoch = "replacement-epoch"; fixture.revision++; fixture.source.refresh()
            IncomingCallActionReceiver().onReceive(context, launch)
            assertEquals(listOf("hangup"), fixture.commands)
        } finally { runtime.calls.lifecycle.close(); RuntimeApplicationShadow.callFixture = null; fixture.close() }
    }

    @Test fun manifestKeepsCallComponentsPrivateAndPreservesObserverPermissions() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.packageManager
        val activity = manager.getActivityInfo(ComponentName(context, CallActivity::class.java), 0)
        val service = manager.getServiceInfo(ComponentName(context, CallMicrophoneService::class.java), 0)
        assertFalse(activity.exported)
        assertFalse(service.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, service.foregroundServiceType)
        val permissions = manager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue("android.permission.FOREGROUND_SERVICE_MICROPHONE" in permissions)
        assertTrue("android.permission.RECORD_AUDIO" in permissions)
        assertTrue("android.permission.WAKE_LOCK" in permissions)
        assertTrue("android.permission.READ_PRIVILEGED_PHONE_STATE" in permissions)
        assertTrue("android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in permissions)
    }
    private fun intent(key: String) = Intent().putExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY, key)
}
