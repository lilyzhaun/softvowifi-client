package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidUnderlayObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RegisteredAccessPublisherHostTest {
    @Test fun actualPublisherReadsRootOnIoAndMissingSuccessorDoesNotReplayAccessFacts() {
        val root = AtomicReference<JSONObject?>(JSONObject().put("schema", 1).put("observedMs", 19998).put("cards", JSONArray()))
        val queue = LinkedBlockingQueue<JSONObject>()
        val rootThread = AtomicReference<String>()
        val observer = AndroidUnderlayObserver(RuntimeEnvironment.getApplication(), publish = {
            queue.add(JSONObject(it)); true
        }, now = { 20000 }, registeredAccess = { rootThread.set(Thread.currentThread().name); root.get() })
        try {
            observer.start(); emit(observer)
            val first = await(queue) { !it.isNull("registeredAccess") }
            assertEquals(20000L, first.getLong("observedMs"))
            assertEquals(19998L, first.getJSONObject("registeredAccess").getLong("observedMs"))
            assertEquals(2, first.getInt("version"))
            assertFalse(rootThread.get().contains("vowifi-underlay"))
            assertFalse(rootThread.get() == Thread.currentThread().name)
            root.set(null); emit(observer)
            val lost = await(queue) { it.has("registeredAccess") && it.isNull("registeredAccess") }
            assertEquals(first.getJSONArray("networks").toString(), lost.getJSONArray("networks").toString())
            assertEquals(first.getLong("accessGeneration"), lost.getLong("accessGeneration"))
        } finally { observer.stop() }
    }

    @Test fun stoppedObserverCannotPublishAfterAnUninterruptibleRootReadReturns() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        val observer = AndroidUnderlayObserver(RuntimeEnvironment.getApplication(), publish = { writes.incrementAndGet(); true },
            registeredAccess = {
                entered.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (release.count != 0L && System.nanoTime() < deadline) {
                    try { release.await((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) }
                    catch (_: InterruptedException) { /* Model a bounded Binder call that ignores interruption. */ }
                }
                JSONObject().put("schema", 1).put("observedMs", 1).put("cards", JSONArray())
            })
        try {
            observer.start(); emit(observer)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val scope = ReflectionHelpers.getField<CoroutineScope>(observer, "scope")
            observer.stop(); release.countDown()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            assertEquals(0, writes.get())
        } finally { release.countDown(); observer.stop() }
    }

    private fun emit(observer: AndroidUnderlayObserver) = ReflectionHelpers.callInstanceMethod<Unit>(observer, "emit")
    private fun await(queue: LinkedBlockingQueue<JSONObject>, predicate: (JSONObject) -> Boolean): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            val value = queue.poll((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) ?: break
            if (predicate(value)) return value
        }
        throw AssertionError("actual publication was not observed within its bound")
    }
}
