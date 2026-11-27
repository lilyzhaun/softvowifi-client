package com.vowifi.sw.root

import android.app.Application
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercise the production wait before a Root service exists; never launch su. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class RootCommandStartupHostTest {
    @Test fun capturedCommandsCannotLaunchAgainstAReplacementService() {
        fun backend() = java.lang.reflect.Proxy.newProxyInstance(IRootBackend::class.java.classLoader,
            arrayOf(IRootBackend::class.java)) { _,_,_ -> error("not a Binder call") } as IRootBackend
        val type = RootBridge::class.java
        ReflectionHelpers.setStaticField(type, "backend", backend()); ReflectionHelpers.setStaticField(type, "environment", null)
        try {
            assertNull(RootBridge.captureCommandSession())
            ReflectionHelpers.setStaticField(type, "environment", "prepared")
            val original = requireNotNull(RootBridge.captureCommandSession()); assertTrue(original.isCurrent)
            ReflectionHelpers.setStaticField(type, "backend", backend())
            assertFalse(original.isCurrent)
            val error = assertThrows(IOException::class.java) { original.start("exit 0") }
            assertEquals("Root 连接已变化", error.message)
        } finally {
            ReflectionHelpers.setStaticField(type, "environment", null); ReflectionHelpers.setStaticField(type, "backend", null)
        }
    }

    @Test fun initialReadWaitsForTheFirstObserverBindingInsteadOfPublishingPermanentFailure() {
        assertWaitCanBeCancelled(bindingAlreadyStarted = false)
    }

    @Test fun cancellationWhileAnExistingBindingIsPendingKeepsItsInterruptedFailure() {
        assertWaitCanBeCancelled(bindingAlreadyStarted = true)
    }

    private fun assertWaitCanBeCancelled(bindingAlreadyStarted: Boolean) {
        ReflectionHelpers.setStaticField(RootBridge::class.java, "backend", null)
        ReflectionHelpers.setStaticField(RootBridge::class.java, "environment", null)
        ReflectionHelpers.setStaticField(RootBridge::class.java, "binding", bindingAlreadyStarted)
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val interrupted = AtomicBoolean()
        val processStarted = AtomicBoolean()
        val caller = Thread({
            started.countDown()
            try {
                RootBridge.startCommand("exit 0").also {
                    processStarted.set(true)
                    it.destroyForcibly()
                }
            } catch (error: Throwable) {
                failure.set(error)
                interrupted.set(Thread.currentThread().isInterrupted)
            } finally {
                finished.countDown()
            }
        }, "root-command-startup-test")
        try {
            caller.start()
            assertTrue("command caller did not start", started.await(2, TimeUnit.SECONDS))
            assertFalse("read failed before the Observer could start its first Root bind",
                finished.await(200, TimeUnit.MILLISECONDS))
            caller.interrupt()
            assertTrue("cancelled command kept waiting", finished.await(2, TimeUnit.SECONDS))
            assertTrue(failure.get() is IOException)
            assertEquals("Root 连接已取消", failure.get()?.message)
            assertTrue("cancellation must retain the thread interrupt", interrupted.get())
            assertFalse("an unavailable backend must not start su", processStarted.get())
        } finally {
            caller.interrupt()
            caller.join(2_000)
            assertFalse("command waiter leaked", caller.isAlive)
            ReflectionHelpers.setStaticField(RootBridge::class.java, "binding", false)
        }
    }
}
