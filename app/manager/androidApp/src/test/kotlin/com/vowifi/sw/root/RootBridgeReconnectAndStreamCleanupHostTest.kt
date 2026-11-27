package com.vowifi.sw.root

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Binder
import android.os.DeadObjectException
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.telephony.SubscriptionInfo
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowParcelFileDescriptor
import org.robolectric.shadows.ShadowProcess
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder
import org.robolectric.util.ReflectionHelpers

@Implements(ParcelFileDescriptor::class)
class FailingSocketPairShadow : ShadowParcelFileDescriptor() {
    companion object {
        val nextPair = AtomicReference<Array<ParcelFileDescriptor>?>(null)

        @JvmStatic
        @Resetter
        fun resetPair() {
            nextPair.set(null)
        }

        @JvmStatic
        @Implementation
        fun createSocketPair(): Array<ParcelFileDescriptor> =
            nextPair.getAndSet(null) ?: throw UnsupportedOperationException("no synthetic socket pair")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = Application::class,
    manifest = Config.NONE,
    shadows = [
        FakeRootServiceBindShadow::class,
        RootCallerPackageManagerShadow::class,
        FailingSocketPairShadow::class,
    ],
)
class RootBridgeReconnectAndStreamCleanupHostTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val watcher = Runnable {}

    @Before
    fun setUp() {
        FakeRootServiceBindShadow.reset()
        FailingSocketPairShadow.resetPair()
        RootCallerPackageManagerShadow.signerMatches = true
        RootCallerPackageManagerShadow.checkedUid = -1
        RootCallerPackageManagerShadow.checkedDigest = null
        resetRootBridgeState()
    }

    @After
    fun tearDown() {
        RootBridge.unwatchSubscriptions(watcher)
        resetRootBridgeState()
        FakeRootServiceBindShadow.reset()
        FailingSocketPairShadow.resetPair()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun resetRootBridgeState() {
        val lock = ReflectionHelpers.getStaticField<Any>(RootBridge::class.java, "LOCK")
        synchronized(lock) {
            ReflectionHelpers.setStaticField(RootBridge::class.java, "backend", null)
            ReflectionHelpers.setStaticField(RootBridge::class.java, "environment", null)
            ReflectionHelpers.setStaticField(RootBridge::class.java, "binding", false)
            ReflectionHelpers.setStaticField(RootBridge::class.java, "subscriptionKey", null)
            ReflectionHelpers.setStaticField(RootBridge::class.java, "nativeEvents", null)
            runCatching {
                ReflectionHelpers.getStaticField<MutableList<Runnable>>(RootBridge::class.java, "PENDING_READY").clear()
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

    private fun sim(slot: Int = 0): SubscriptionInfo = SubscriptionInfoBuilder.newBuilder()
        .setId(10 + slot)
        .setSimSlotIndex(slot)
        .setIccId("890123456789012345$slot")
        .buildSubscriptionInfo()

    private fun fakeBackend(
        env: String,
        subscriptions: () -> List<SubscriptionInfo> = { listOf(sim(0)) },
        onObserve: (INativeRegistrationEvents?) -> Unit = {},
        onAttachStream: (String?, Boolean, ParcelFileDescriptor?) -> Unit = { _, _, _ -> },
    ): IRootBackend.Stub = object : IRootBackend.Stub() {
        override fun runReadOnlyChecks(): String = "{}"
        override fun attachProbeStream(descriptor: ParcelFileDescriptor?) = Unit
        override fun getProbeStreamStatus(): String = "ready"
        override fun activeSubscriptions(): MutableList<SubscriptionInfo> = subscriptions().toMutableList()
        override fun subscriptionExtras(subId: Int): String = "{}"
        override fun nativeStatus(): String = "[]"
        override fun nativeCallsIdle(): Boolean = true
        override fun observeNativeRegistration(events: INativeRegistrationEvents?) = onObserve(events)
        override fun prepareRuntime(): String = env
        override fun attachEngineStream(iccid: String?, audio: Boolean, descriptor: ParcelFileDescriptor?) =
            onAttachStream(iccid, audio, descriptor)
        override fun registeredAccess(): String? = null
    }

    @Test
    fun reconnectReattachesNativeRegistrationObserverWithoutWaitingForSimChange() {
        val backend1Observes = AtomicInteger(0)
        val backend2Observes = AtomicInteger(0)
        val cards = listOf(sim(0))
        val backend1 = fakeBackend("export ENV=1", { cards }, onObserve = { if (it != null) backend1Observes.incrementAndGet() })
        val backend2 = fakeBackend("export ENV=2", { cards }, onObserve = { if (it != null) backend2Observes.incrementAndGet() })

        RootBridge.bind(app) {}
        val conn1 = FakeRootServiceBindShadow.connections.last()
        conn1.onServiceConnected(ComponentName(app, RootBackendService::class.java), backend1)
        flushWorkQueue()

        RootBridge.observeNativeRegistration { }
        assertEquals(1, backend1Observes.get())
        RootBridge.watchSubscriptions(watcher)
        RootBridge.checkSubscriptions(backend1)
        shadowOf(Looper.getMainLooper()).idle()

        conn1.onServiceDisconnected(ComponentName(app, RootBackendService::class.java))
        RootBridge.bind(app) {}
        val conn2 = FakeRootServiceBindShadow.connections.last()
        conn2.onServiceConnected(ComponentName(app, RootBackendService::class.java), backend2)
        flushWorkQueue()

        assertTrue(
            "reconnected RootBackendService did not reattach native registration observer",
            backend2Observes.get() >= 1,
        )
    }

    @Test
    fun reconnectWithTransientObserveFailureRetriesOnNextSubscriptionCheckForSameSim() {
        val backend2Attempts = AtomicInteger(0)
        val cards = listOf(sim(0))
        val backend1 = fakeBackend("export ENV=1", { cards })
        val backend2 = fakeBackend("export ENV=2", { cards }, onObserve = { events ->
            if (events != null && backend2Attempts.incrementAndGet() == 1) {
                throw IllegalStateException("native_observer_unavailable")
            }
        })

        RootBridge.bind(app) {}
        val conn1 = FakeRootServiceBindShadow.connections.last()
        conn1.onServiceConnected(ComponentName(app, RootBackendService::class.java), backend1)
        flushWorkQueue()

        RootBridge.observeNativeRegistration { }
        RootBridge.watchSubscriptions(watcher)
        RootBridge.checkSubscriptions(backend1)
        shadowOf(Looper.getMainLooper()).idle()

        conn1.onServiceDisconnected(ComponentName(app, RootBackendService::class.java))
        RootBridge.bind(app) {}
        val conn2 = FakeRootServiceBindShadow.connections.last()
        conn2.onServiceConnected(ComponentName(app, RootBackendService::class.java), backend2)
        flushWorkQueue()

        assertNotNull("transient observeNativeRegistration error must not tear down reconnected RootBridge", RootBridge.captureCommandSession())
        assertEquals(1, backend2Attempts.get())

        // Same SIMs: subscriptionKey must have been cleared on disconnect so checkSubscriptions retries observeNativeRegistration.
        RootBridge.checkSubscriptions(backend2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "checkSubscriptions skipped retrying observeNativeRegistration because stale subscriptionKey was kept across disconnect",
            2,
            backend2Attempts.get(),
        )
    }

    @Test
    fun openSocketPreservesChannelUnavailableErrorWhenCloseThrowsDuringRollback() {
        val backend = fakeBackend("export ENV=1", onAttachStream = { _, _, _ -> throw DeadObjectException("synthetic_dead_backend") })
        RootBridge.bind(app) {}
        FakeRootServiceBindShadow.connections.last()
            .onServiceConnected(ComponentName(app, RootBackendService::class.java), backend)
        flushWorkQueue()

        val p0Closed = AtomicBoolean(false)
        val p1Closed = AtomicBoolean(false)
        val p0 = object : ParcelFileDescriptor(null) {
            private val fd = FileDescriptor()
            override fun getFileDescriptor(): FileDescriptor = fd
            override fun close() {
                p0Closed.set(true)
                throw IOException("synthetic p0 close failure")
            }
        }
        val p1 = object : ParcelFileDescriptor(null) {
            private val fd = FileDescriptor()
            override fun getFileDescriptor(): FileDescriptor = fd
            override fun close() {
                p1Closed.set(true)
                throw IOException("synthetic p1 close failure")
            }
        }
        FailingSocketPairShadow.nextPair.set(arrayOf(p0, p1))

        var caught: IOException? = null
        try {
            RootBridge.openSocket("/data/local/tmp/softvowifi/8901234567890123456.sock")
            fail("expected IOException")
        } catch (error: IOException) {
            caught = error
        }

        assertEquals("cleanup close failure masked Root channel error", "Root 引擎通道不可用", caught?.message)
        assertTrue("pair[0] was not closed on rollback", p0Closed.get())
        assertTrue("pair[1] was not closed in finally", p1Closed.get())
    }

    @Test
    fun attachEngineStreamRejectsAfterServiceOnDestroyWithoutInspectingDescriptor() {
        val ownerUid = 10123
        val priorUid = android.os.Process.myUid()
        val installed = PackageInfo().apply {
            packageName = "com.vowifi.sw"
            applicationInfo = ApplicationInfo().apply { packageName = "com.vowifi.sw"; uid = ownerUid }
            signatures = arrayOf(Signature("synthetic-signer".toByteArray()))
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(signatures) }
        }
        shadowOf(app.packageManager).installPackage(installed)
        val context = object : ContextWrapper(app) {
            override fun getPackageName(): String = "com.vowifi.sw"
            override fun enforceCallingPermission(permission: String, message: String?) = Unit
        }
        val service = RootBackendService()
        ReflectionHelpers.setField(service, "mBase", context)
        val binder = IRootBackend.Stub.asInterface(
            ReflectionHelpers.callInstanceMethod(
                service,
                "onBind",
                ReflectionHelpers.ClassParameter.from(Intent::class.java, Intent()),
            ),
        )
        ShadowProcess.setUid(0)
        ShadowBinder.setCallingUid(ownerUid)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onDestroy")
            val inspected = AtomicBoolean(false)
            val closed = AtomicBoolean(false)
            val descriptor = object : ParcelFileDescriptor(null) {
                override fun getFileDescriptor(): FileDescriptor {
                    inspected.set(true)
                    return FileDescriptor()
                }
                override fun close() {
                    closed.set(true)
                }
            }
            try {
                binder.attachEngineStream("8901234567890123456", false, descriptor)
                fail("expected IllegalStateException after onDestroy")
            } catch (error: IllegalStateException) {
                assertEquals("engine_stream_unavailable", error.message)
            }
            assertFalse("attachEngineStream constructed RootStream after onDestroy", inspected.get())
            assertTrue("descriptor was not closed in finally after destroyed rejection", closed.get())
        } finally {
            ShadowProcess.setUid(priorUid)
        }
    }
}
