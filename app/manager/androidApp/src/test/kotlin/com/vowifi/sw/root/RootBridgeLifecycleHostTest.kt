package com.vowifi.sw.root

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.vowifi.sw.ObserverImsManagerShadow
import com.vowifi.sw.ObserverMmTelShadow
import com.vowifi.sw.RegistrationObserver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowProcess
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [33],
    application = Application::class,
    manifest = Config.NONE,
    shadows = [
        FakeRootServiceBindShadow::class,
        ObserverImsManagerShadow::class,
        ObserverMmTelShadow::class,
        ObserverRootTelephonyShadow::class,
    ],
)
class RootBridgeLifecycleHostTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        FakeRootServiceBindShadow.reset()
        ObserverRootBridgeShadow.reset()
        ObserverMmTelShadow.registered.clear()
        ObserverMmTelShadow.closed.clear()
        ObserverMmTelShadow.callbacks.clear()
        ObserverMmTelShadow.closeFailure = false
        ObserverMmTelShadow.registrationFailure = false
        ObserverImsManagerShadow.failedSubscription = -1
        resetRootBridgeState()
    }

    @After
    fun tearDown() {
        resetRootBridgeState()
        FakeRootServiceBindShadow.reset()
        ObserverRootBridgeShadow.reset()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun resetRootBridgeState() {
        val lock = ReflectionHelpers.getStaticField<Any>(RootBridge::class.java, "LOCK")
        synchronized(lock) {
            ReflectionHelpers.setStaticField(RootBridge::class.java, "backend", null)
            ReflectionHelpers.setStaticField(RootBridge::class.java, "environment", null)
            ReflectionHelpers.setStaticField(RootBridge::class.java, "binding", false)
            runCatching {
                val pending = ReflectionHelpers.getStaticField<MutableList<Runnable>>(RootBridge::class.java, "PENDING_READY")
                pending.clear()
            }
        }
    }

    private fun flushWorkQueue() {
        val work = ReflectionHelpers.getStaticField<ScheduledExecutorService>(RootBridge::class.java, "WORK")
        val done = CountDownLatch(1)
        work.execute { done.countDown() }
        assertTrue("RootBridge WORK executor did not drain", done.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun fakeBackend(prepare: () -> String): IRootBackend.Stub = object : IRootBackend.Stub() {
        override fun runReadOnlyChecks(): String = "{}"
        override fun attachProbeStream(descriptor: ParcelFileDescriptor?) = Unit
        override fun getProbeStreamStatus(): String = "ready"
        override fun activeSubscriptions(): MutableList<SubscriptionInfo> = mutableListOf()
        override fun subscriptionExtras(subId: Int): String = "{}"
        override fun nativeStatus(): String = "[]"
        override fun nativeCallsIdle(): Boolean = true
        override fun observeNativeRegistration(events: INativeRegistrationEvents?) = Unit
        override fun prepareRuntime(): String = prepare()
        override fun attachEngineStream(iccid: String?, audio: Boolean, descriptor: ParcelFileDescriptor?) = Unit
        override fun registeredAccess(): String? = null
    }

    @Test
    fun secondBindWhileBindingInFlightStillReceivesReadyCallback() {
        val ready1 = AtomicBoolean(false)
        val ready2 = AtomicBoolean(false)
        RootBridge.bind(app) { ready1.set(true) }
        RootBridge.bind(app) { ready2.set(true) }
        assertEquals(1, FakeRootServiceBindShadow.connections.size)

        val binder = fakeBackend { "export ENV=1" }
        FakeRootServiceBindShadow.connections.first().onServiceConnected(
            ComponentName(app, RootBackendService::class.java),
            binder,
        )
        flushWorkQueue()

        assertTrue("first bind ready callback must run", ready1.get())
        assertTrue("second bind caller while binding in flight must also receive ready callback", ready2.get())
    }

    @Test
    fun staleServiceDisconnectCannotWipeReplacementConnection() {
        val ready2 = AtomicBoolean(false)
        RootBridge.bind(app) {}
        assertEquals(1, FakeRootServiceBindShadow.connections.size)
        val conn1 = FakeRootServiceBindShadow.connections[0]
        val failingBackend1 = fakeBackend { throw DeadObjectException("stale_backend_1") }
        conn1.onServiceConnected(ComponentName(app, RootBackendService::class.java), failingBackend1)
        flushWorkQueue()

        RootBridge.bind(app) { ready2.set(true) }
        assertEquals(2, FakeRootServiceBindShadow.connections.size)
        val conn2 = FakeRootServiceBindShadow.connections[1]
        val liveBackend2 = fakeBackend { "export ENV=2" }
        conn2.onServiceConnected(ComponentName(app, RootBackendService::class.java), liveBackend2)
        flushWorkQueue()
        assertTrue(ready2.get())
        val sessionBefore = RootBridge.captureCommandSession()
        assertNotNull(sessionBefore)
        assertTrue(sessionBefore!!.isCurrent)

        // Delayed disconnect callback from the superseded first ServiceConnection arrives now.
        conn1.onServiceDisconnected(ComponentName(app, RootBackendService::class.java))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "stale ServiceConnection disconnect must not wipe newer active RootBridge backend",
            sessionBefore.isCurrent,
        )
        assertNotNull(RootBridge.captureCommandSession())
    }

    @Test
    fun synchronousRootServiceBindFailureResetsBindingFlagForRetry() {
        FakeRootServiceBindShadow.throwOnce = SecurityException("synthetic_bind_error")
        var caught = false
        try {
            RootBridge.bind(app) {}
        } catch (_: SecurityException) {
            caught = true
        }
        assertTrue("expected synchronous bind error to propagate", caught)

        val ready = AtomicBoolean(false)
        RootBridge.bind(app) { ready.set(true) }
        assertEquals(
            "retry bind after synchronous bind failure must invoke RootService.bind again",
            1,
            FakeRootServiceBindShadow.connections.size,
        )
        val binder = fakeBackend { "export ENV=retry" }
        FakeRootServiceBindShadow.connections.first().onServiceConnected(
            ComponentName(app, RootBackendService::class.java),
            binder,
        )
        flushWorkQueue()
        assertTrue(ready.get())
    }

    @Test
    fun staleNativeObserverRemoteExceptionCannotStopReplacementAndClosedIgnoresObserve() {
        ObserverRootBridgeShadow.context = app
        shadowOf(app.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfos(
            SubscriptionInfoBuilder.newBuilder().setId(7).setSimSlotIndex(0).buildSubscriptionInfo(),
        )
        val priorUid = android.os.Process.myUid()
        ShadowProcess.setUid(0)
        val observation = RootNativeObservation(app)
        try {
            val deadCalls = AtomicInteger(0)
            val deadEvents = object : INativeRegistrationEvents.Stub() {
                override fun onEvent(event: String?) {
                    deadCalls.incrementAndGet()
                    throw DeadObjectException("stale_client_dead")
                }
            }
            observation.observe(deadEvents)
            val listA = ReflectionHelpers.getField<List<RegistrationObserver>>(observation, "observers")
            assertEquals(1, listA.size)
            val staleSink = ReflectionHelpers.getField<Consumer<String>>(listA[0], "sink")

            val liveReceived = AtomicInteger(0)
            val liveEvents = object : INativeRegistrationEvents.Stub() {
                override fun onEvent(event: String?) {
                    liveReceived.incrementAndGet()
                }
            }
            observation.observe(liveEvents)
            val listB = ReflectionHelpers.getField<List<RegistrationObserver>>(observation, "observers")
            assertEquals(1, listB.size)

            // Invoke the superseded observer's callback after observe(liveEvents) replaced it.
            staleSink.accept("{\"state\":\"REGISTERED\"}")
            assertEquals(
                "stale observer callback must not invoke dead listener after replacement",
                0,
                deadCalls.get(),
            )
            assertEquals(
                "stale observer RemoteException must not stop the replacement observation's observers",
                1,
                ReflectionHelpers.getField<List<RegistrationObserver>>(observation, "observers").size,
            )

            // Live observer must still deliver events cleanly.
            val liveSink = ReflectionHelpers.getField<Consumer<String>>(listB[0], "sink")
            liveSink.accept("{\"state\":\"REGISTERED\"}")
            assertEquals(1, liveReceived.get())

            // After close(), observe() must not register new observers on the shut-down executor.
            observation.close()
            observation.observe(liveEvents)
            assertEquals(
                "closed RootNativeObservation must ignore subsequent observe() calls",
                0,
                ReflectionHelpers.getField<List<RegistrationObserver>>(observation, "observers").size,
            )
        } finally {
            observation.close()
            ShadowProcess.setUid(priorUid)
        }
    }
}

@Implements(className = "com.topjohnwu.superuser.ipc.RootService", isInAndroidSdk = false)
class FakeRootServiceBindShadow {
    companion object {
        val connections = mutableListOf<ServiceConnection>()
        var throwOnce: RuntimeException? = null

        @JvmStatic
        @Implementation
        fun bind(intent: Intent, conn: ServiceConnection) {
            val err = throwOnce
            if (err != null) {
                throwOnce = null
                throw err
            }
            connections += conn
        }

        fun reset() {
            connections.clear()
            throwOnce = null
        }
    }
}
