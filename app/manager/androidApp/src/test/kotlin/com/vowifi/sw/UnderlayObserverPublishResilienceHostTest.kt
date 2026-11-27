@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidUnderlayObserver
import com.vowifi.sw.manager.data.DiagnosticEvent
import com.vowifi.sw.manager.data.DiagnosticReason
import com.vowifi.sw.manager.data.DiagnosticResult
import com.vowifi.sw.manager.data.DiagnosticStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnderlayObserverPublishResilienceHostTest {

    @Test
    fun transientRegisteredAccessExceptionPublishesNullAccessAndRecoversOnNextEmit() {
        val failNext = AtomicBoolean(true)
        val queue = LinkedBlockingQueue<JSONObject>()
        val observer = AndroidUnderlayObserver(
            RuntimeEnvironment.getApplication(),
            publish = {
                queue.add(JSONObject(it))
                true
            },
            now = { 20_000L },
            registeredAccess = {
                if (failNext.compareAndSet(true, false)) {
                    throw IllegalStateException("synthetic transient binder failure")
                }
                JSONObject().put("schema", 1).put("observedMs", 20_001L).put("cards", JSONArray())
            },
        )
        try {
            observer.start()
            emit(observer)
            val fallback = await(queue) { it.has("registeredAccess") && it.isNull("registeredAccess") }
            assertEquals(20_000L, fallback.getLong("observedMs"))

            emit(observer)
            val recovered = await(queue) { !it.isNull("registeredAccess") }
            assertEquals(20_001L, recovered.getJSONObject("registeredAccess").getLong("observedMs"))
        } finally {
            observer.stop()
        }
    }

    @Test
    fun transientPublishExceptionEmitsPublishFailedDiagnosticAndRecoversOnNextEmit() {
        val failNext = AtomicBoolean(true)
        val queue = LinkedBlockingQueue<JSONObject>()
        val diagnostics = CopyOnWriteArrayList<DiagnosticEvent>()
        val observer = AndroidUnderlayObserver(
            RuntimeEnvironment.getApplication(),
            publish = {
                if (failNext.compareAndSet(true, false)) {
                    throw IllegalStateException("synthetic transient publish failure")
                }
                queue.add(JSONObject(it))
                true
            },
            now = { 30_000L },
            diagnostic = { diagnostics += it },
            registeredAccess = { null },
        )
        try {
            observer.start()
            emit(observer)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline && diagnostics.none {
                    it.stage == DiagnosticStage.PUBLISH &&
                        it.result == DiagnosticResult.FAILURE &&
                        it.reason == DiagnosticReason.PUBLISH_FAILED
                }
            ) {
                Thread.sleep(5)
            }
            assertTrue(
                "transient publish exception did not emit PUBLISH_FAILED diagnostic",
                diagnostics.any {
                    it.stage == DiagnosticStage.PUBLISH &&
                        it.result == DiagnosticResult.FAILURE &&
                        it.reason == DiagnosticReason.PUBLISH_FAILED
                },
            )

            emit(observer)
            val recovered = await(queue) { it.getLong("observedMs") == 30_000L }
            assertEquals(2, recovered.getInt("version"))
        } finally {
            observer.stop()
        }
    }

    @Test
    fun stopDuringThrowingRegisteredAccessDoesNotPublishAfterStop() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger(0)
        val observer = AndroidUnderlayObserver(
            RuntimeEnvironment.getApplication(),
            publish = {
                writes.incrementAndGet()
                true
            },
            now = { 40_000L },
            registeredAccess = {
                entered.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (release.count != 0L && System.nanoTime() < deadline) {
                    try {
                        release.await((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
                    } catch (_: InterruptedException) {
                    }
                }
                throw IllegalStateException("late binder error after stop")
            },
        )
        try {
            observer.start()
            emit(observer)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val scope = ReflectionHelpers.getField<CoroutineScope>(observer, "scope")
            observer.stop()
            release.countDown()
            withTimeout(2_000) { scope.coroutineContext[Job]!!.join() }
            assertEquals(0, writes.get())
        } finally {
            release.countDown()
            observer.stop()
        }
    }

    private fun emit(observer: AndroidUnderlayObserver) = ReflectionHelpers.callInstanceMethod<Unit>(observer, "emit")

    private fun await(queue: LinkedBlockingQueue<JSONObject>, predicate: (JSONObject) -> Boolean): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            val value = queue.poll((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) ?: break
            if (predicate(value)) return value
        }
        throw AssertionError("expected underlay publication was not observed within timeout")
    }
}
