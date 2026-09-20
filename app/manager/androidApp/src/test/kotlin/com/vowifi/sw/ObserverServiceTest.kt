package com.vowifi.sw

import android.Manifest.permission.READ_PHONE_STATE
import android.app.Service
import android.content.Context
import android.content.Intent
import android.telephony.SubscriptionManager
import android.telephony.ims.ImsReasonInfo
import java.util.concurrent.ExecutorService
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [
    RuntimeApplicationShadow::class, ObserverPermissionShadow::class,
    ObserverImsManagerShadow::class, ObserverMmTelShadow::class,
    ObserverCallsShadow::class, ObserverMessagesShadow::class,
])
class ObserverServiceTest {
    private lateinit var controller: ServiceController<ObserverService>
    private val service get() = controller.get()
    private val observers get() = ReflectionHelpers.getField<Map<Int, RegistrationObserver>>(service, "observers")
    private val executor get() = ReflectionHelpers.getField<ExecutorService>(service, "executor")
    private var destroyed = false

    @Before fun prepare() {
        ObserverPermissionShadow.granted.clear()
        ObserverPermissionShadow.granted += "android.permission.READ_PRIVILEGED_PHONE_STATE"
        ObserverMmTelShadow.registered.clear()
        ObserverMmTelShadow.closed.clear()
        ObserverMmTelShadow.callbacks.clear()
        ObserverMmTelShadow.closeFailure = false
        ObserverMmTelShadow.registrationFailure = false
        ObserverImsManagerShadow.failedSubscription = -1
        controller = Robolectric.buildService(ObserverService::class.java)
        shadowOf(service.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfos(
            SubscriptionInfoBuilder.newBuilder().setId(1).buildSubscriptionInfo(),
            SubscriptionInfoBuilder.newBuilder().setId(2).buildSubscriptionInfo(),
        )
    }

    @After fun cleanup() {
        ObserverMmTelShadow.closeFailure = false
        if (!destroyed) controller.destroy()
        executor.shutdownNow()
    }

    @Test fun deniedBothPermissionsKeepsRuntimeWithoutObservers() {
        ObserverPermissionShadow.granted.clear()
        controller.create()
        assertTrue(observers.isEmpty())
        assertTrue(ObserverMmTelShadow.registered.isEmpty())
        assertRuntimeContinues()
    }

    @Test fun privilegedOnlyRegistersWithoutOrdinaryPermission() {
        controller.create()
        assertEquals(setOf(1, 2), observers.keys)
        assertEquals(listOf(1, 2), ObserverMmTelShadow.registered)
        assertRuntimeContinues()
    }

    @Test fun ordinaryOnlyCannotRegisterImsCallbacks() {
        ObserverPermissionShadow.granted.clear()
        ObserverPermissionShadow.granted += READ_PHONE_STATE
        controller.create()
        assertTrue(observers.isEmpty())
        assertTrue(ObserverMmTelShadow.registered.isEmpty())
        assertRuntimeContinues()
    }

    @Test fun ordinaryAndPrecisePermissionsRegisterImsCallbacks() {
        ObserverPermissionShadow.granted.clear()
        ObserverPermissionShadow.granted += READ_PHONE_STATE
        ObserverPermissionShadow.granted += android.Manifest.permission.READ_PRECISE_PHONE_STATE
        controller.create()
        assertEquals(setOf(1, 2), observers.keys)
        assertRuntimeContinues()
    }

    @Test fun frameworkSubscriptionDenialKeepsRuntimeAndSanitizesLogs() {
        shadowOf(service.getSystemService(SubscriptionManager::class.java)).setReadPhoneStatePermission(false)
        controller.create()
        assertTrue(observers.isEmpty())
        assertRuntimeContinues()
        assertSanitizedLogs()
    }

    @Test fun missingSubscriptionServiceKeepsRuntime() {
        Shadow.extract<ShadowContextImpl>(service.baseContext).removeSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
        controller.create()
        assertTrue(observers.isEmpty())
        assertRuntimeContinues()
    }

    @Test fun missingImsServiceKeepsRuntime() {
        Shadow.extract<ShadowContextImpl>(service.baseContext).removeSystemService(Context.TELEPHONY_IMS_SERVICE)
        controller.create()
        assertTrue(observers.isEmpty())
        assertRuntimeContinues()
    }

    @Test fun nullSubscriptionListKeepsRuntime() {
        shadowOf(service.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(null)
        controller.create()
        assertTrue(observers.isEmpty())
        assertRuntimeContinues()
    }

    @Test fun emptySubscriptionListKeepsRuntime() {
        shadowOf(service.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfos()
        controller.create()
        assertTrue(observers.isEmpty())
        assertRuntimeContinues()
    }

    @Test fun secondManagerFailureRetainsFirstObserver() {
        ObserverImsManagerShadow.failedSubscription = 2
        controller.create()
        assertEquals(setOf(1), observers.keys)
        assertRuntimeContinues()
        assertSanitizedLogs()
    }

    @Test fun firstManagerFailureDoesNotSkipSecondObserver() {
        ObserverImsManagerShadow.failedSubscription = 1
        controller.create()
        assertEquals(setOf(2), observers.keys)
        assertRuntimeContinues()
        assertSanitizedLogs()
    }

    @Test fun callbackPermissionRaceIsUnavailableAndSanitized() {
        ObserverMmTelShadow.registrationFailure = true
        controller.create()
        assertTrue(observers.isEmpty())
        assertRuntimeContinues()
        assertSanitizedLogs()
    }

    @Test fun failedFirstCloseStillClosesAllAndReleasesLocalResources() {
        controller.create()
        ObserverMmTelShadow.closeFailure = true
        destroyed = true
        controller.destroy()
        assertEquals(setOf(1, 2), ObserverMmTelShadow.closed.toSet())
        assertTrue(observers.isEmpty())
        assertTrue(executor.isShutdown)
        assertSanitizedLogs()
    }

    @Test fun destroyPreservesProcessRuntimeAndItsCallsAndMessages() {
        val application = RuntimeEnvironment.getApplication() as SoftVoWiFiApplication
        val runtime = application.runtime()
        val calls = runtime.calls
        val messages = ReflectionHelpers.getField<Any>(runtime, "messages")
        controller.create()
        assertRuntimeContinues()
        controller.destroy()
        destroyed = true
        assertSame(runtime, application.runtime())
        assertSame(calls, application.runtime().calls)
        assertSame(messages, ReflectionHelpers.getField<Any>(application.runtime(), "messages"))
        assertEquals(0, Shadow.extract<ObserverCallsShadow>(calls).closeCalls)
        assertEquals(0, Shadow.extract<ObserverMessagesShadow>(messages).closeCalls)
    }

    @Test fun callbackDoesNotLogRawReasonOrSubscription() {
        controller.create()
        ObserverMmTelShadow.callbacks.first().onUnregistered(ImsReasonInfo(0, 0, SENTINEL))
        assertSanitizedLogs()
    }

    private fun assertRuntimeContinues() {
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(Service.START_STICKY, service.onStartCommand(Intent(), 0, 1))
        assertEquals(1, Shadow.extract<RuntimeApplicationShadow>(RuntimeEnvironment.getApplication()).runtimeStarts)
    }

    private fun assertSanitizedLogs() {
        val logs = ShadowLog.getLogsForTag("SoftVoWiFiObserver")
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.all { it.throwable == null })
        assertTrue(logs.none { SENTINEL in it.msg || "subId" in it.msg })
    }
}
