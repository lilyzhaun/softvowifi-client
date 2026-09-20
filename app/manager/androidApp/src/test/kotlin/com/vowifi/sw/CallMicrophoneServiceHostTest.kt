package com.vowifi.sw

import android.Manifest
import android.app.Notification
import android.app.Service
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.CallUiState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
class CallMicrophoneServiceHostTest {
    @Test
    @Config(shadows = [RuntimeApplicationShadow::class, OrderedMicrophoneServiceShadow::class])
    fun coldStaleStartPromotesPrivatelyBeforeRetiringWithoutGrant() = staleStart(false)

    @Test
    @Config(shadows = [RuntimeApplicationShadow::class, OrderedMicrophoneServiceShadow::class])
    fun expiredForegroundBarrierNeverGrantsLateServiceOrDials() = staleStart(true)

    @Test
    @Config(shadows = [RuntimeApplicationShadow::class, DeniedMicrophoneServiceShadow::class])
    fun revokedPermissionAtColdPromotionNeverGrantsAudioAndReportsFailure() = staleStart(false, true)

    private fun staleStart(expire: Boolean, denied: Boolean = false) = runBlocking {
        val fixture = CallPlatformFixture()
        RuntimeApplicationShadow.callFixture = fixture
        val app = RuntimeEnvironment.getApplication() as SoftVoWiFiApplication
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val runtime = app.runtime()
        val activity = Robolectric.buildActivity(CallGateActivity::class.java).setup()
        val service = Robolectric.buildService(CallMicrophoneService::class.java).create()
        OrderedMicrophoneServiceShadow.operations.clear()
        OrderedMicrophoneServiceShadow.promotedNotification = null
        try {
            val intent = if (expire) {
                runtime.calls.lifecycle.setVisible(activity.get(), true)
                val binding = (fixture.source.state.value as CallUiState.Ready).binding!!
                assertFalse(withTimeout(5000) {
                    runtime.calls.lifecycle.perform(activity.get(), binding, MicrophoneCallAction.Dial, "+12025550123")
                })
                activity.get().serviceIntent!!
            } else android.content.Intent().putExtra(AndroidCallLifecycle.EXTRA_REQUEST, "expired")
            if (denied) shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
            service.get().onStartCommand(intent, 0, 7)
            if (denied) {
                assertNotNull(runtime.calls.lifecycle.feedback.value)
                assertEquals(7, shadowOf(service.get()).stopSelfId)
            } else {
                assertEquals(listOf("promote", "retire", "stop:7"), OrderedMicrophoneServiceShadow.operations)
                val notification = requireNotNull(OrderedMicrophoneServiceShadow.promotedNotification)
                assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
                assertNotNull(notification.publicVersion)
                assertNull(notification.contentIntent)
            }
            assertFalse(fixture.microphone.foreground)
            assertTrue(fixture.commands.isEmpty())
            assertEquals(com.vowifi.sw.manager.model.CallPhase.Idle, (fixture.source.state.value as CallUiState.Ready).phase)
        } finally {
            runtime.calls.lifecycle.close(); service.destroy(); activity.close()
            RuntimeApplicationShadow.callFixture = null; fixture.close()
        }
    }

    @Test fun servicePublishesForegroundBeforeAuthorizingActualDial() = exercise(false)

    @Test
    @Config(shadows = [RuntimeApplicationShadow::class, DeniedMicrophoneServiceShadow::class])
    fun rejectedForegroundNeverAuthorizesDial() = exercise(true)

    private fun exercise(denied: Boolean) = runBlocking {
        val fixture = CallPlatformFixture()
        RuntimeApplicationShadow.callFixture = fixture
        val app = RuntimeEnvironment.getApplication() as SoftVoWiFiApplication
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val runtime = app.runtime()
        val activity = Robolectric.buildActivity(CallGateActivity::class.java).setup()
        val service = Robolectric.buildService(CallMicrophoneService::class.java).create()
        try {
            runtime.calls.lifecycle.setVisible(activity.get(), true)
            val binding = (fixture.source.state.value as CallUiState.Ready).binding!!
            val operation = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.calls.lifecycle.perform(activity.get(), binding, MicrophoneCallAction.Dial, "+12025550123")
            }
            assertTrue(fixture.commands.isEmpty())
            service.get().onStartCommand(activity.get().serviceIntent, 0, 1)
            val accepted = withTimeout(5000) { operation.await() }
            assertEquals(!denied, accepted)
            if (denied) {
                assertTrue(fixture.commands.isEmpty())
                assertFalse(fixture.microphone.foreground)
            } else {
                assertNotNull(shadowOf(service.get()).lastForegroundNotification)
                assertEquals(Notification.VISIBILITY_PRIVATE, shadowOf(service.get()).lastForegroundNotification.visibility)
                assertEquals(listOf("dial"), fixture.commands)
                assertTrue(fixture.microphone.foreground)
                service.get().onStartCommand(android.content.Intent().putExtra(AndroidCallLifecycle.EXTRA_REQUEST, "stale"), 0, 2)
                assertFalse(shadowOf(service.get()).isStoppedBySelf)
                assertTrue(fixture.microphone.foreground)
                runtime.calls.lifecycle.close()
                assertEquals(2, shadowOf(service.get()).stopSelfId)
                assertFalse(fixture.microphone.foreground)
            }
        } finally {
            runtime.calls.lifecycle.close(); service.destroy(); activity.close()
            RuntimeApplicationShadow.callFixture = null; fixture.close()
        }
    }
}

@Implements(Service::class)
class OrderedMicrophoneServiceShadow : ShadowService() {
    @Implementation override fun startForeground(id: Int, notification: Notification, type: Int) {
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, type)
        operations += "promote"
        promotedNotification = notification
        super.startForeground(id, notification, type)
    }
    @Implementation override fun stopForeground(flags: Int) {
        operations += "retire"
        super.stopForeground(flags)
    }
    @Implementation override fun stopSelf(id: Int) {
        operations += "stop:$id"
        super.stopSelf(id)
    }
    companion object {
        val operations = mutableListOf<String>()
        var promotedNotification: Notification? = null
    }
}

@Implements(Service::class)
class DeniedMicrophoneServiceShadow : ShadowService() {
    @Implementation override fun startForeground(id: Int, notification: Notification, type: Int) {
        throw SecurityException("synthetic microphone foreground denial")
    }
}
