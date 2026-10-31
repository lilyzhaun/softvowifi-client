package com.vowifi.sw

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.telephony.SubscriptionManager
import com.vowifi.sw.root.ObserverRootBridgeShadow
import com.vowifi.sw.root.ObserverRootTelephonyShadow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowContextImpl
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class, shadows = [
    RuntimeApplicationShadow::class, MessageSourceShadow::class, ObserverStartBoundaryShadow::class,
    ObserverImsManagerShadow::class, ObserverMmTelShadow::class,
    ObserverRootBridgeShadow::class, ObserverRootTelephonyShadow::class,
])
class ForegroundObserverStartHostTest {
    @Before fun prepare() {
        ObserverRootBridgeShadow.reset()
        RuntimeApplicationShadow.callFixture = CallPlatformFixture()
        ObserverStartBoundaryShadow.failure = null
        ObserverStartBoundaryShadow.attempts = 0
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            "android.permission.POST_NOTIFICATIONS",
        )
        ObserverMmTelShadow.registered.clear()
        ObserverMmTelShadow.closed.clear()
        ObserverMmTelShadow.callbacks.clear()
        ObserverMmTelShadow.closeFailure = false
        ObserverMmTelShadow.registrationFailure = false
        ObserverImsManagerShadow.failedSubscription = -1
    }

    @After fun cleanup() {
        val application = RuntimeEnvironment.getApplication() as SoftVoWiFiApplication
        application.runtime().notifications.stop()
        application.runtime().calls.lifecycle.close()
        RuntimeApplicationShadow.callFixture?.close()
        RuntimeApplicationShadow.callFixture = null
        ObserverStartBoundaryShadow.failure = null
        ObserverRootBridgeShadow.reset()
    }

    @Test fun freshForegroundEntryRequestsOnlyExistingObserver() {
        Robolectric.buildActivity(MainActivity::class.java).use { main ->
            main.create()
            assertNull(shadowOf(main.get()).nextStartedService)
            main.start().resume()
            val request = requireNotNull(shadowOf(main.get()).nextStartedService) { "foreground entry did not start Observer" }
            assertEquals(ComponentName(main.get(), ObserverService::class.java), request.component)
            assertNull(request.extras)
            assertEquals(1, ObserverStartBoundaryShadow.attempts)
            assertNull(shadowOf(main.get()).nextStartedService)
            main.pause().stop().destroy()
        }
    }

    @Test fun repeatedForegroundRequestsReuseObserverSubscriptionsAndProcessRuntime() {
        val application = RuntimeEnvironment.getApplication() as SoftVoWiFiApplication
        val runtime = application.runtime()
        val observer = Robolectric.buildService(ObserverService::class.java)
        try {
            shadowOf(observer.get().getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfos(
                SubscriptionInfoBuilder.newBuilder().setId(1).buildSubscriptionInfo(),
            )
            observer.create()
            val notification = shadowOf(observer.get()).lastForegroundNotification
            assertEquals("ims_observer", notification.channelId)
            Robolectric.buildActivity(MainActivity::class.java).use { main ->
                main.create().start().resume()
                repeat(3) { index ->
                    val request = requireNotNull(shadowOf(main.get()).nextStartedService)
                    assertEquals(ObserverService::class.java.name, request.component?.className)
                    assertEquals(Service.START_STICKY, observer.get().onStartCommand(request, 0, index + 1))
                    assertSame(runtime, application.runtime())
                    assertEquals(listOf(1), ObserverMmTelShadow.registered)
                    assertTrue(ObserverMmTelShadow.closed.isEmpty())
                    assertFalse(shadowOf(observer.get()).isStoppedBySelf)
                    assertSame(notification, shadowOf(observer.get()).lastForegroundNotification)
                    if (index < 2) main.pause().resume()
                }
                assertEquals(4, Shadow.extract<RuntimeApplicationShadow>(application).runtimeStarts)
                main.pause().stop().destroy()
            }
        } finally {
            observer.destroy()
        }
        assertEquals(listOf(1), ObserverMmTelShadow.closed)
    }

    @Test fun securityRejectionDoesNotCrashOrLeakAndNextForegroundCanRetry() {
        assertRejectedThenRetry(SecurityException("private-start-detail"), "observer_start_permission_denied")
    }

    @Test fun foregroundRestrictionDoesNotCrashOrLeakAndNextForegroundCanRetry() {
        assertRejectedThenRetry(ForegroundServiceStartNotAllowedException("private-start-detail"), "observer_start_not_allowed")
    }

    private fun assertRejectedThenRetry(failure: RuntimeException, reason: String) {
        ObserverStartBoundaryShadow.failure = failure
        Robolectric.buildActivity(MainActivity::class.java).use { main ->
            main.create().start().resume()
            assertFalse(main.get().isFinishing)
            assertEquals(1, ObserverStartBoundaryShadow.attempts)
            assertNull(shadowOf(main.get()).nextStartedService)
            val logs = ShadowLog.getLogsForTag("SoftVoWiFiObserver")
            assertTrue(logs.any { it.msg == reason })
            assertTrue(logs.all { it.throwable == null && "private-start-detail" !in it.msg })
            main.pause().stop()
            assertEquals(1, ObserverStartBoundaryShadow.attempts)
            ObserverStartBoundaryShadow.failure = null
            main.restart().start().resume()
            assertEquals(2, ObserverStartBoundaryShadow.attempts)
            assertEquals(ObserverService::class.java.name, shadowOf(main.get()).nextStartedService.component?.className)
            main.pause().stop().destroy()
        }
    }
}

@Implements(className = "android.app.ContextImpl")
class ObserverStartBoundaryShadow : ShadowContextImpl() {
    @Implementation override fun startForegroundService(intent: Intent): ComponentName? {
        if (intent.component?.className == ObserverService::class.java.name) {
            attempts++
            failure?.let { throw it }
        }
        return super.startForegroundService(intent)
    }
    companion object {
        var failure: RuntimeException? = null
        var attempts = 0
    }
}
