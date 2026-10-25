package com.vowifi.sw

import android.os.Handler
import android.os.Looper
import android.os.Message
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = SoftVoWiFiApplication::class,
    shadows = [
        CallEntryApplicationShadow::class,
        CallEntryShellShadow::class,
        CallEntryHmacShadow::class,
        CallEntrySocketShadow::class,
        CallEntryAudioShadow::class,
    ],
)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallEntryFixtureReadinessHostTest {

    @Test
    fun readyWaitsForWrapperProjectionInsteadOfReturningPrematurelyWhenDelegateUpdatesFirst() = runBlocking {
        val fixture = CallEntryFixture()
        var repostJob: Job? = null
        try {
            fixture.connected()
            fixture.source.selectLine(fixture.ownerB)
            fixture.second.snapshotError = 3002
            fixture.source.refresh()
            val failed = fixture.ready { it.sync == CallSyncState.Unregistered }
            assertEquals(CallFailureMessages.IMS, failed.operationMessage)
            assertEquals(CallFailureMessages.IMS, (fixture.source.state.value as CallUiState.Ready).operationMessage)

            recoverDelegate(fixture)

            // Preconditions: legitimate delegate update has queued the native MainLooper projection tasks,
            // while the public wrapper state still holds the stale Unregistered snapshot.
            assertEquals(CallSyncState.Ready, (fixture.delegate.state.value as CallUiState.Ready).sync)
            assertNull((fixture.delegate.state.value as CallUiState.Ready).operationMessage)
            val staleWrapper = fixture.source.state.value as CallUiState.Ready
            assertEquals(CallSyncState.Unregistered, staleWrapper.sync)
            assertEquals(CallFailureMessages.IMS, staleWrapper.operationMessage)

            val pendingTasks = mutableListOf<Pair<Handler, Runnable>>()
            var currentMsg = ReflectionHelpers.getField<Message?>(Looper.getMainLooper().queue, "mMessages")
            while (currentMsg != null) {
                val target = currentMsg.target
                val callback = currentMsg.callback
                if (target != null && callback != null) {
                    pendingTasks += target to callback
                }
                currentMsg = ReflectionHelpers.getField<Message?>(currentMsg, "next")
            }
            assertTrue(pendingTasks.isNotEmpty())
            pendingTasks.forEach { (handler, callback) -> handler.removeCallbacks(callback) }
            assertNull(ReflectionHelpers.getField<Message?>(Looper.getMainLooper().queue, "mMessages"))

            repostJob = launch(Dispatchers.IO) {
                delay(30)
                pendingTasks.forEach { (handler, callback) -> handler.post(callback) }
            }

            val ready = fixture.ready { it.sync == CallSyncState.Ready }
            val publicState = fixture.source.state.value as CallUiState.Ready
            assertNull(publicState.operationMessage)
            assertEquals(CallSyncState.Ready, publicState.sync)
            assertEquals(CallSyncState.Ready, ready.sync)
            assertNull(ready.operationMessage)
            assertSame(failed.binding, ready.binding)
            assertSame(failed.binding, publicState.binding)
        } finally {
            repostJob?.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun recoveryStaysReadyWhenTheAggregateRepublishesItsSelectedChild() = runBlocking {
        val fixture = CallEntryFixture()
        try {
            fixture.connected()
            fixture.source.selectLine(fixture.ownerB)
            fixture.second.snapshotError = 3002
            fixture.source.refresh()
            fixture.ready { it.sync == CallSyncState.Unregistered }
            recoverDelegate(fixture)
            val recovered = fixture.delegate.state.value as CallUiState.Ready
            assertEquals(CallSyncState.Ready, recovered.sync)
            fixture.source.selectLine(fixture.ownerB)
            assertEquals("恢复必须来自实际子源，不能只写聚合投影后被下一次publish覆盖",
                CallSyncState.Ready, (fixture.delegate.state.value as CallUiState.Ready).sync)
            val projected = fixture.ready { it.sync == CallSyncState.Ready }
            assertNull(projected.operationMessage)
            assertSame(recovered.binding, projected.binding)
        } finally { fixture.close() }
    }

    private suspend fun recoverDelegate(fixture: CallEntryFixture) {
        fixture.second.snapshotError = null
        fixture.source.refresh()
        withTimeout(3000) {
            fixture.delegate.state.first {
                it is CallUiState.Ready && it.sync == CallSyncState.Ready && it.operationMessage == null
            }
        }
    }

    @Test
    fun closeAwaitsOwnedChildInFlightControlCoroutineFinallyBeforeClosingPeerAndReturning() = runBlocking {
        val fixture = CallEntryFixture()
        val enteredRequest = CountDownLatch(1)
        val cancelledInFinally = CountDownLatch(1)
        val releaseFinallyGate = CountDownLatch(1)
        val jobFinishedFinally = AtomicBoolean(false)
        val peerClosedBeforeJobFinally = AtomicBoolean(false)
        var helper: Thread? = null
        try {
            fixture.connected()
            val children = ReflectionHelpers.getField<List<*>>(fixture.delegate, "children")
            val childB = children.map { ReflectionHelpers.getField<EngineCallSource>(it!!, "source") }
                .single { ReflectionHelpers.getField<String>(it, "activeIccid") == CallDualBackendFixture.ICCID_B }
            val childScope = ReflectionHelpers.getField<CoroutineScope>(childB, "scope")

            val ownedJob = childScope.launch(Dispatchers.IO) {
                val client = AndroidCallEventTransport(CallDualBackendFixture.ICCID_B)
                try {
                    client.connect()
                    enteredRequest.countDown()
                    awaitCancellation()
                } finally {
                    cancelledInFinally.countDown()
                    releaseFinallyGate.await(2, TimeUnit.SECONDS)
                    client.close()
                    jobFinishedFinally.set(true)
                }
            }

            assertTrue(enteredRequest.await(2, TimeUnit.SECONDS))
            val inFlightSocket = fixture.second.sockets.last()
            assertFalse(inFlightSocket.closed)

            helper = thread(start = true) {
                if (cancelledInFinally.await(2, TimeUnit.SECONDS)) {
                    Thread.sleep(30)
                    peerClosedBeforeJobFinally.set(inFlightSocket.closed || ownedJob.isCompleted)
                    releaseFinallyGate.countDown()
                }
            }

            fixture.close()
            assertTrue(jobFinishedFinally.get())
            assertTrue(ownedJob.isCompleted)
            assertFalse(peerClosedBeforeJobFinally.get())
            assertTrue(fixture.first.sockets.all { it.closed })
            assertTrue(fixture.second.sockets.all { it.closed })
        } finally {
            releaseFinallyGate.countDown()
            helper?.join(2000)
            fixture.close()
        }
    }
}
