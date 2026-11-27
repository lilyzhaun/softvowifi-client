@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallAction
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallSyncState
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class EngineCallActionCancellationCleanupTest {
    @Test
    fun cancelledPerformDtmfReleasesDtmfBusyAndAllowsSubsequentDigit() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        var dtmfCalls = 0
        val controls = CallControlTransport { _, method, _, _ ->
            when (method) {
                "getCallSnapshot" -> snapshot("IN_CALL", elapsed = 1000, dtmf = true)
                "sendDtmf" -> {
                    dtmfCalls += 1
                    if (dtmfCalls == 1) gate.await()
                    ok("sent")
                }
                else -> error("unexpected method: $method")
            }
        }
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), FakeCallTransport(), control = controls)
        try {
            val binding = source.ready().binding!!
            val job = launch { source.performDtmf(binding, '5') }
            assertEquals(1, dtmfCalls)
            assertTrue(source.ready().dtmfBusy)

            job.cancelAndJoin()

            assertFalse(source.ready().dtmfBusy, "cancelled performDtmf left dtmfBusy stuck true")
            assertNull(source.dtmfOperation, "cancelled performDtmf leaked dtmfOperation")

            source.sendDtmf(binding, '6')
            assertEquals(2, dtmfCalls, "subsequent DTMF was blocked after cancelled performDtmf")
            assertFalse(source.ready().dtmfBusy)
        } finally {
            gate.complete(Unit)
            source.close()
        }
    }

    @Test
    fun cancelledPerformDialReleasesDialOperationAndMarksRecovering() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val primary = FakeCallTransport(
            requestGate = gate,
            gatedMethods = setOf("dial"),
        )
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), primary)
        try {
            val binding = source.ready().binding!!
            val job = launch { source.performDial(binding, CALL_NUMBER) }
            assertEquals(1, primary.requests.count { it.method == "dial" })
            assertTrue(source.ready().busy)

            job.cancelAndJoin()

            assertNull(source.dialOperation, "cancelled performDial leaked dialOperation")
            assertFalse(source.ready().busy, "cancelled performDial left busy stuck true")
            assertEquals(CallSyncState.Recovering, source.ready().sync)
        } finally {
            gate.complete(Unit)
            source.close()
        }
    }

    @Test
    fun cancelledExecuteControlReleasesControlOperationRestoresPhaseAndMarksRecovering() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            var hangupCalls = 0
            val controls = CallControlTransport { _, method, _, _ ->
                when (method) {
                    "getCallSnapshot" -> snapshot("IN_CALL", elapsed = 2000, dtmf = true)
                    "hangup" -> {
                        hangupCalls += 1
                        if (hangupCalls == 1) gate.await()
                        EngineProtocolFrame.Response(1, buildJsonObject { put("state", "IDLE") }, null)
                    }
                    else -> error("unexpected method: $method")
                }
            }
            val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), FakeCallTransport(), control = controls)
            try {
                val binding = source.ready().binding!!
                val job = launch { source.performControl(binding, CallAction.Hangup) }
                assertEquals(1, hangupCalls)
                assertTrue(source.ready().busy)
                assertEquals(CallPhase.Ending, source.ready().phase)

                job.cancelAndJoin()

                assertNull(source.controlOperation, "cancelled executeControl leaked controlOperation")
                assertNull(source.pendingAction, "cancelled executeControl leaked pendingAction")
                assertFalse(source.ready().busy, "cancelled executeControl left busy stuck true")
                assertEquals(CallPhase.InCall, source.ready().phase, "cancelled hangup left phase stuck in Ending")
                assertEquals(CallSyncState.Recovering, source.ready().sync)
            } finally {
                gate.complete(Unit)
                source.close()
            }
        }

    @Test
    fun cancelledWhileWaitingCommitLockInPerformDtmfReleasesDtmfBusyAndAllowsSubsequentDigit() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            var dtmfCalls = 0
            val controls = CallControlTransport { _, method, _, _ ->
                when (method) {
                    "getCallSnapshot" -> snapshot("IN_CALL", elapsed = 1000, dtmf = true)
                    "sendDtmf" -> {
                        dtmfCalls += 1
                        if (dtmfCalls == 1) gate.await()
                        ok("sent")
                    }
                    else -> error("unexpected method: $method")
                }
            }
            val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), FakeCallTransport(), control = controls)
            var locked = false
            try {
                val binding = source.ready().binding!!
                val job = launch { source.performDtmf(binding, '5') }
                assertEquals(1, dtmfCalls)
                assertTrue(source.ready().dtmfBusy)

                source.mutex.lock()
                locked = true
                gate.complete(Unit)
                job.cancel()
                source.mutex.unlock()
                locked = false
                job.join()

                assertFalse(source.ready().dtmfBusy, "commit-lock-cancelled performDtmf left dtmfBusy stuck true")
                assertNull(source.dtmfOperation, "commit-lock-cancelled performDtmf leaked dtmfOperation")

                source.sendDtmf(binding, '6')
                assertEquals(2, dtmfCalls, "subsequent DTMF was blocked after commit-lock-cancelled performDtmf")
                assertFalse(source.ready().dtmfBusy)
            } finally {
                gate.complete(Unit)
                if (locked) source.mutex.unlock()
                source.close()
            }
        }

    @Test
    fun cancelledWhileWaitingCommitLockInPerformDialReleasesDialOperationAndMarksRecovering() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val primary = FakeCallTransport(
                requestGate = gate,
                gatedMethods = setOf("dial"),
            )
            val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), primary)
            var locked = false
            try {
                val binding = source.ready().binding!!
                val job = launch { source.performDial(binding, CALL_NUMBER) }
                assertEquals(1, primary.requests.count { it.method == "dial" })
                assertTrue(source.ready().busy)

                source.mutex.lock()
                locked = true
                gate.complete(Unit)
                job.cancel()
                source.mutex.unlock()
                locked = false
                job.join()

                assertNull(source.dialOperation, "commit-lock-cancelled performDial leaked dialOperation")
                assertFalse(source.ready().busy, "commit-lock-cancelled performDial left busy stuck true")
                assertEquals(CallSyncState.Recovering, source.ready().sync)
            } finally {
                gate.complete(Unit)
                if (locked) source.mutex.unlock()
                source.close()
            }
        }

    @Test
    fun cancelledWhileWaitingCommitLockInExecuteControlReleasesControlOperationRestoresPhaseAndMarksRecovering() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            var hangupCalls = 0
            val controls = CallControlTransport { _, method, _, _ ->
                when (method) {
                    "getCallSnapshot" -> snapshot("IN_CALL", elapsed = 2000, dtmf = true)
                    "hangup" -> {
                        hangupCalls += 1
                        if (hangupCalls == 1) gate.await()
                        EngineProtocolFrame.Response(1, buildJsonObject { put("state", "IDLE") }, null)
                    }
                    else -> error("unexpected method: $method")
                }
            }
            val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), FakeCallTransport(), control = controls)
            var locked = false
            try {
                val binding = source.ready().binding!!
                val job = launch { source.performControl(binding, CallAction.Hangup) }
                assertEquals(1, hangupCalls)
                assertTrue(source.ready().busy)
                assertEquals(CallPhase.Ending, source.ready().phase)

                source.mutex.lock()
                locked = true
                gate.complete(Unit)
                job.cancel()
                source.mutex.unlock()
                locked = false
                job.join()

                assertNull(source.controlOperation, "commit-lock-cancelled executeControl leaked controlOperation")
                assertNull(source.pendingAction, "commit-lock-cancelled executeControl leaked pendingAction")
                assertFalse(source.ready().busy, "commit-lock-cancelled executeControl left busy stuck true")
                assertEquals(CallPhase.InCall, source.ready().phase, "commit-lock-cancelled hangup left phase stuck in Ending")
                assertEquals(CallSyncState.Recovering, source.ready().sync)
            } finally {
                gate.complete(Unit)
                if (locked) source.mutex.unlock()
                source.close()
            }
        }
}
