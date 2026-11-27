@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Handler
import android.os.HandlerThread
import com.vowifi.sw.manager.data.AndroidUnderlayObserver
import com.vowifi.sw.manager.data.DiagnosticEvent
import com.vowifi.sw.manager.data.DiagnosticReason
import com.vowifi.sw.manager.data.DiagnosticResult
import com.vowifi.sw.manager.data.DiagnosticStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.json.JSONObject
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
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.util.ReflectionHelpers

@Implements(ConnectivityManager::class)
class FailingConnectivityManagerShadow : ShadowConnectivityManager() {
    companion object {
        val failNextDefaultRegister = AtomicReference<(() -> RuntimeException)?>(null)
        val failNextUnregister = AtomicReference<(() -> RuntimeException)?>(null)
        val unregisterAttempts = AtomicInteger(0)

        @JvmStatic
        @Resetter
        fun resetHooks() {
            failNextDefaultRegister.set(null)
            failNextUnregister.set(null)
            unregisterAttempts.set(0)
        }
    }

    @Implementation
    public override fun registerDefaultNetworkCallback(
        networkCallback: ConnectivityManager.NetworkCallback,
        handler: Handler,
    ) {
        failNextDefaultRegister.getAndSet(null)?.let { throw it() }
        super.registerDefaultNetworkCallback(networkCallback, handler)
    }

    @Implementation
    public override fun unregisterNetworkCallback(networkCallback: ConnectivityManager.NetworkCallback) {
        unregisterAttempts.incrementAndGet()
        failNextUnregister.getAndSet(null)?.let { throw it() }
        super.unregisterNetworkCallback(networkCallback)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [FailingConnectivityManagerShadow::class])
class UnderlayObserverLifecycleCleanupHostTest {

    @Before
    fun setUp() {
        FailingConnectivityManagerShadow.resetHooks()
    }

    @After
    fun tearDown() {
        FailingConnectivityManagerShadow.resetHooks()
    }

    private fun awaitPublication(queue: LinkedBlockingQueue<JSONObject>): JSONObject {
        val item = queue.poll(2, TimeUnit.SECONDS)
        assertNotNull("expected underlay publication after clean restart", item)
        return item!!
    }

    @Test
    fun startCleansUpAndAllowsRetryWhenRegisterReceiverThrowsRuntimeException() {
        val base = RuntimeEnvironment.getApplication()
        val failOnce = AtomicBoolean(true)
        val wrapper = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun registerReceiver(
                receiver: BroadcastReceiver?,
                filter: IntentFilter?,
                broadcastPermission: String?,
                scheduler: Handler?,
                flags: Int,
            ): Intent? {
                if (failOnce.compareAndSet(true, false)) {
                    throw IllegalStateException("synthetic receiver registration failed")
                }
                return super.registerReceiver(receiver, filter, broadcastPermission, scheduler, flags)
            }
        }
        val diagnostics = CopyOnWriteArrayList<DiagnosticEvent>()
        val published = LinkedBlockingQueue<JSONObject>()
        val observer = AndroidUnderlayObserver(
            wrapper,
            publish = { published.add(JSONObject(it)); true },
            now = { 25_000L },
            diagnostic = { diagnostics += it },
            registeredAccess = { null },
        )
        try {
            observer.start()

            assertNull("handler leaked after failed registerReceiver", ReflectionHelpers.getField(observer, "handler"))
            assertNull("thread leaked after failed registerReceiver", ReflectionHelpers.getField(observer, "thread"))
            assertNull("scope leaked after failed registerReceiver", ReflectionHelpers.getField(observer, "scope"))
            val cm = wrapper.getSystemService(ConnectivityManager::class.java)
            assertTrue(
                "network callbacks leaked after failed registerReceiver",
                shadowOf(cm).networkCallbacks.isEmpty(),
            )
            assertTrue(
                "expected LISTENER_FAILED diagnostic",
                diagnostics.any {
                    it.stage == DiagnosticStage.LISTENER &&
                        it.result == DiagnosticResult.FAILURE &&
                        it.reason == DiagnosticReason.LISTENER_FAILED
                },
            )

            observer.start()
            assertNotNull("retry start() stayed wedged", ReflectionHelpers.getField<Handler?>(observer, "handler"))
            val state = awaitPublication(published)
            assertEquals(25_000L, state.getLong("observedMs"))
        } finally {
            observer.stop()
        }
    }

    @Test
    fun startCleansUpAndAllowsRetryWhenDefaultNetworkCallbackThrowsTooManyRequests() {
        val app = RuntimeEnvironment.getApplication()
        val diagnostics = CopyOnWriteArrayList<DiagnosticEvent>()
        val published = LinkedBlockingQueue<JSONObject>()
        val observer = AndroidUnderlayObserver(
            app,
            publish = { published.add(JSONObject(it)); true },
            now = { 35_000L },
            diagnostic = { diagnostics += it },
            registeredAccess = { null },
        )
        FailingConnectivityManagerShadow.failNextDefaultRegister.set {
            IllegalStateException("synthetic too many network requests")
        }
        try {
            observer.start()

            assertNull("handler leaked after TooManyRequestsException", ReflectionHelpers.getField(observer, "handler"))
            assertNull("thread leaked after TooManyRequestsException", ReflectionHelpers.getField(observer, "thread"))
            assertNull("scope leaked after TooManyRequestsException", ReflectionHelpers.getField(observer, "scope"))
            val cm = app.getSystemService(ConnectivityManager::class.java)
            assertTrue(
                "first network callback leaked after TooManyRequestsException",
                shadowOf(cm).networkCallbacks.isEmpty(),
            )
            assertTrue(
                "expected LISTENER_FAILED diagnostic",
                diagnostics.any {
                    it.stage == DiagnosticStage.LISTENER &&
                        it.result == DiagnosticResult.FAILURE &&
                        it.reason == DiagnosticReason.LISTENER_FAILED
                },
            )

            observer.start()
            val state = awaitPublication(published)
            assertEquals(35_000L, state.getLong("observedMs"))
        } finally {
            observer.stop()
        }
    }

    @Test
    fun stopCompletesFullTeardownWhenUnregisterCallbackThrowsRuntimeException() {
        val base = RuntimeEnvironment.getApplication()
        val receiverUnregistered = AtomicBoolean(false)
        val wrapper = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun unregisterReceiver(receiver: BroadcastReceiver?) {
                receiverUnregistered.set(true)
                super.unregisterReceiver(receiver)
            }
        }
        val observer = AndroidUnderlayObserver(
            wrapper,
            publish = { true },
            now = { 45_000L },
            registeredAccess = { null },
        )
        observer.start()
        val worker = ReflectionHelpers.getField<HandlerThread>(observer, "thread")
        val scope = ReflectionHelpers.getField<CoroutineScope>(observer, "scope")
        assertNotNull(worker)
        assertNotNull(scope)

        FailingConnectivityManagerShadow.unregisterAttempts.set(0)
        FailingConnectivityManagerShadow.failNextUnregister.set {
            IllegalStateException("synthetic binder dead during unregister")
        }
        try {
            observer.stop()
            assertEquals(
                "second unregisterNetworkCallback was skipped after first threw RuntimeException",
                2,
                FailingConnectivityManagerShadow.unregisterAttempts.get(),
            )
            assertTrue("accessReceiver.unregisterReceiver was skipped", receiverUnregistered.get())
            assertNull("thread field was not cleared", ReflectionHelpers.getField(observer, "thread"))
            assertNull("scope field was not cleared", ReflectionHelpers.getField(observer, "scope"))
            assertNull("updates field was not cleared", ReflectionHelpers.getField(observer, "updates"))
            assertTrue("scope was not cancelled", scope.coroutineContext[Job]!!.isCancelled)
            assertFalse("HandlerThread remained alive", worker.isAlive)
        } finally {
            worker.quitSafely()
            worker.join(1_000)
        }
    }
}
