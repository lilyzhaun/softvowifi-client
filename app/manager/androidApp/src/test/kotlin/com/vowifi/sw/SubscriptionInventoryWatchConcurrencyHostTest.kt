@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidSubscriptionInventory
import com.vowifi.sw.manager.data.DiagnosticEvent
import com.vowifi.sw.root.RootBridge
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter

@Implements(value = RootBridge::class, isInAndroidSdk = false)
class WatchRaceRootBridgeShadow {
    companion object {
        val registered = CopyOnWriteArrayList<Runnable>()
        val inPlatformCall = AtomicInteger(0)
        val concurrentOverlaps = AtomicInteger(0)
        val watchCalls = AtomicInteger(0)
        val unwatchCalls = AtomicInteger(0)
        val watchHook = AtomicReference<((Int, Runnable) -> Unit)?>(null)
        val unwatchHook = AtomicReference<((Int, Runnable) -> Unit)?>(null)

        @JvmStatic
        @Resetter
        fun reset() {
            registered.clear()
            inPlatformCall.set(0)
            concurrentOverlaps.set(0)
            watchCalls.set(0)
            unwatchCalls.set(0)
            watchHook.set(null)
            unwatchHook.set(null)
        }

        @JvmStatic
        @Implementation
        fun watchSubscriptions(watcher: Runnable) {
            if (inPlatformCall.incrementAndGet() > 1) concurrentOverlaps.incrementAndGet()
            try {
                val n = watchCalls.incrementAndGet()
                watchHook.get()?.invoke(n, watcher)
                registered.add(watcher)
            } finally {
                inPlatformCall.decrementAndGet()
            }
        }

        @JvmStatic
        @Implementation
        fun unwatchSubscriptions(watcher: Runnable) {
            if (inPlatformCall.incrementAndGet() > 1) concurrentOverlaps.incrementAndGet()
            try {
                val n = unwatchCalls.incrementAndGet()
                unwatchHook.get()?.invoke(n, watcher)
                registered.remove(watcher)
            } finally {
                inPlatformCall.decrementAndGet()
            }
        }

        fun fireChange() {
            registered.forEach(Runnable::run)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [WatchRaceRootBridgeShadow::class])
class SubscriptionInventoryWatchConcurrencyHostTest {

    @Before
    fun setUp() {
        WatchRaceRootBridgeShadow.reset()
    }

    @After
    fun tearDown() {
        WatchRaceRootBridgeShadow.reset()
    }

    @Test(timeout = 5000)
    fun concurrentStopAndStartWatchingNeverUnregistersReplacementListener() {
        val inventory = AndroidSubscriptionInventory(RuntimeEnvironment.getApplication()) {}
        val first = inventory.startWatching {}
        assertEquals(1, WatchRaceRootBridgeShadow.registered.size)

        val unwatchEntered = CountDownLatch(1)
        val releaseUnwatch = CountDownLatch(1)
        WatchRaceRootBridgeShadow.unwatchHook.set { n, _ ->
            if (n == 1) {
                unwatchEntered.countDown()
                assertTrue(releaseUnwatch.await(3, TimeUnit.SECONDS))
            }
        }

        val stopper = Thread { first.stop() }.apply { start() }
        assertTrue("first.stop() did not enter unwatchSubscriptions", unwatchEntered.await(2, TimeUnit.SECONDS))

        val secondDelivered = AtomicInteger(0)
        val second = inventory.startWatching { secondDelivered.incrementAndGet() }

        releaseUnwatch.countDown()
        stopper.join(3_000)
        assertFalse("stopper thread hung", stopper.isAlive)

        assertEquals(
            "platform listener was removed by stale stop() despite active replacement watcher",
            1,
            WatchRaceRootBridgeShadow.registered.size,
        )
        assertEquals(
            "watchSubscriptions and unwatchSubscriptions overlapped concurrently",
            0,
            WatchRaceRootBridgeShadow.concurrentOverlaps.get(),
        )
        WatchRaceRootBridgeShadow.fireChange()
        assertEquals("replacement watcher did not receive subscription change", 1, secondDelivered.get())

        second.stop()
        assertEquals(0, WatchRaceRootBridgeShadow.registered.size)
    }

    @Test(timeout = 5000)
    fun concurrentStartWhileRegistrationFailsRetriesForNewerCaller() {
        val events = CopyOnWriteArrayList<DiagnosticEvent>()
        val inventory = AndroidSubscriptionInventory(RuntimeEnvironment.getApplication(), events::add)

        val firstWatchEntered = CountDownLatch(1)
        val releaseFirstWatch = CountDownLatch(1)
        WatchRaceRootBridgeShadow.watchHook.set { n, _ ->
            if (n == 1) {
                firstWatchEntered.countDown()
                assertTrue(releaseFirstWatch.await(3, TimeUnit.SECONDS))
                throw SecurityException("synthetic initial root bind not ready")
            }
        }

        val firstWatchRef = AtomicReference<com.vowifi.sw.manager.data.SubscriptionWatch>()
        val starter = Thread { firstWatchRef.set(inventory.startWatching {}) }.apply { start() }
        assertTrue("first startWatching did not enter watchSubscriptions", firstWatchEntered.await(2, TimeUnit.SECONDS))

        val secondDelivered = AtomicInteger(0)
        val second = inventory.startWatching { secondDelivered.incrementAndGet() }

        releaseFirstWatch.countDown()
        starter.join(3_000)
        assertFalse("starter thread hung", starter.isAlive)

        assertEquals(
            "second caller piggybacked on failing registration and left platform listener unregistered",
            1,
            WatchRaceRootBridgeShadow.registered.size,
        )
        assertEquals(2, WatchRaceRootBridgeShadow.watchCalls.get())
        WatchRaceRootBridgeShadow.fireChange()
        assertEquals("second watcher did not receive change after retry", 1, secondDelivered.get())

        firstWatchRef.get().stop()
        assertEquals(1, WatchRaceRootBridgeShadow.registered.size)
        second.stop()
        assertEquals(0, WatchRaceRootBridgeShadow.registered.size)
    }

    @Test(timeout = 5000)
    fun concurrentStopDuringReRegisterLeavesZeroLeakedPlatformListeners() {
        val inventory = AndroidSubscriptionInventory(RuntimeEnvironment.getApplication()) {}
        val first = inventory.startWatching {}
        assertEquals(1, WatchRaceRootBridgeShadow.registered.size)

        val unwatchEntered = CountDownLatch(1)
        val releaseUnwatch = CountDownLatch(1)
        val secondStoppedBeforeUnwatchFinished = AtomicBoolean(false)
        WatchRaceRootBridgeShadow.unwatchHook.set { n, _ ->
            if (n == 1) {
                unwatchEntered.countDown()
                assertTrue(releaseUnwatch.await(3, TimeUnit.SECONDS))
            }
        }

        val stopper = Thread { first.stop() }.apply { start() }
        assertTrue(unwatchEntered.await(2, TimeUnit.SECONDS))

        val second = inventory.startWatching {}
        second.stop()
        secondStoppedBeforeUnwatchFinished.set(true)

        releaseUnwatch.countDown()
        stopper.join(3_000)
        assertFalse("stopper thread hung", stopper.isAlive)
        assertTrue(secondStoppedBeforeUnwatchFinished.get())

        assertEquals(
            "platform listener leaked after second watcher started and stopped during unwatch",
            0,
            WatchRaceRootBridgeShadow.registered.size,
        )
        assertEquals(
            "unnecessary watchSubscriptions ran concurrently with unwatchSubscriptions",
            0,
            WatchRaceRootBridgeShadow.concurrentOverlaps.get(),
        )
    }
}
