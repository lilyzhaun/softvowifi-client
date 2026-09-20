package com.vowifi.sw

import android.os.Looper
import com.vowifi.sw.manager.data.AndroidConfigurationSubmitter
import com.vowifi.sw.manager.data.ConfigurationSource
import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ConfigurationSubmissionThreadTest {
    @Test fun blockingCommandRunsOffMainAndSubmitReturnsBeforeItFinishes() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val held = AtomicBoolean(false)
        val released = CountDownLatch(1)
        val started = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        var commandThread: Thread? = null
        var callbacks = 0
        val submitter = AndroidConfigurationSubmitter(scope, { false }, { held.compareAndSet(false, true) }, {
            held.set(false); released.countDown()
        })
        try {
            assertEquals(ConfigurationApplyAcceptance.Accepted, submitter.submit({ true }, {
                withContext(Dispatchers.IO) {
                    commandThread = Thread.currentThread()
                    started.countDown()
                    unblock.await(400, TimeUnit.MILLISECONDS)
                    finished.set(true)
                    ConfigurationApplyOutcome.Failed
                }
            }) {
                assertSame(Looper.getMainLooper(), Looper.myLooper())
                assertFalse(held.get())
                callbacks++
            })
            assertFalse("submit blocked until command completed", finished.get())
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertNotSame(Looper.getMainLooper().thread, commandThread)
            assertEquals(ConfigurationApplyAcceptance.Busy, submitter.submit({ true }, {
                fail("busy command ran"); ConfigurationApplyOutcome.Failed
            }) { fail("busy submission completed") })
            unblock.countDown()
            assertTrue(released.await(2, TimeUnit.SECONDS))
            repeat(20) {
                shadowOf(Looper.getMainLooper()).idle()
                if (callbacks == 0) Thread.sleep(10)
            }
            assertEquals(1, callbacks)
        } finally { unblock.countDown(); scope.cancel(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun closeBeforeExecutionCompletesUnconfirmedOnceWithoutRunningCommand() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var closed = false
        var held = false
        var runs = 0
        var callbacks = 0
        val submitter = AndroidConfigurationSubmitter(scope, { closed }, { if (held) false else { held = true; true } }, { held = false })
        submitter.submit({ true }, { runs++; ConfigurationApplyOutcome.Failed }) {
            assertEquals(ConfigurationApplyOutcome.Unconfirmed, it)
            assertFalse(held)
            callbacks++
        }
        closed = true
        scope.cancel()
        dispatcher.drain()
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, runs)
        assertEquals(1, callbacks)
        assertEquals(ConfigurationApplyAcceptance.Unavailable, submitter.submit({ true }, {
            fail("closed command ran"); ConfigurationApplyOutcome.Failed
        }) { fail("rejected submission completed") })
    }

    @Test fun onlyOneMandatoryTypedSaveEntryExists() {
        assertEquals(0, ConfigurationSource::class.java.declaredMethods.count { it.name == "apply" })
    }

    @Test fun closeAfterCommandBeforeMainDeliveryCannotReportConfirmed() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var closed = false
        var callbacks = 0
        val submitter = AndroidConfigurationSubmitter(scope, { closed }, { true }, {})
        val row = com.vowifi.sw.manager.model.ConfigurationInstance(
            com.vowifi.sw.manager.model.ConfigurationInstanceId("sim-1"), "实例 ••••",
            com.vowifi.sw.manager.model.ConfigurationLifecycle.STOPPED,
            com.vowifi.sw.manager.model.ConfigurationTransport.WIFI,
            "", "", "", "", "", "", "001", "01",
        )
        submitter.submit({ true }, { ConfigurationApplyOutcome.Confirmed(row) }) {
            assertEquals(ConfigurationApplyOutcome.Unconfirmed, it)
            callbacks++
        }
        dispatcher.drain()
        closed = true
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, callbacks)
    }

    @Test fun completionCanImmediatelyRetryBecauseGateWasReleased() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var held = false
        var runs = 0
        val submitter = AndroidConfigurationSubmitter(scope, { false }, { if (held) false else { held = true; true } }, { held = false })
        submitter.submit({ true }, { runs++; ConfigurationApplyOutcome.Failed }) {
            assertEquals(ConfigurationApplyAcceptance.Accepted,
                submitter.submit({ true }, { runs++; ConfigurationApplyOutcome.Failed }) {})
        }
        dispatcher.drain()
        shadowOf(Looper.getMainLooper()).idle()
        dispatcher.drain()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, runs)
        assertFalse(held)
        scope.cancel()
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = java.util.ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { repeat(10) { tasks.poll()?.run() } }
    }
}
