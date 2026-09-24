@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class EngineCallPendingSnapshotTest {
    @Test fun equalIdleDuringPendingDialKeepsBindingUntilVersionedCallAndTerminal() = runTest(UnconfinedTestDispatcher()) {
        val primary = FakeCallTransport()
        val gate = primary.enqueueGate()
        val audio = FakeCallAudioController()
        var current = snapshot("IDLE", revision = 7)
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), primary, audio,
            control = CallControlTransport { _, _, _, _ -> current })
        try {
            source.dial(CALL_NUMBER)
            val optimistic = assertNotNull(source.ready().binding)
            assertEquals(listOf("dial"), primary.requests.map { it.method })
            assertNull(optimistic.token)
            source.refresh()
            assertEquals(CallPhase.Dialing, source.ready().phase)
            assertSame(optimistic, source.ready().binding)
            assertTrue(source.ready().dialBusy)
            source.dial(CALL_NUMBER)
            assertEquals(1, primary.requests.size)

            current = snapshot("DIALING", revision = 8)
            primary.emitVersioned("callState", CALL_TOKEN, "DIALING", "epoch-a", 8)
            val confirmed = assertNotNull(source.ready().binding)
            assertTrue(optimistic.sameCall(confirmed))
            assertEquals(CALL_TOKEN, confirmed.token)
            current = snapshot("IN_CALL", revision = 9)
            primary.emitVersioned("callState", CALL_TOKEN, "IN_CALL", "epoch-a", 9)
            gate.complete(Unit)
            assertEquals(CallPhase.InCall, source.ready().phase)
            assertSame(confirmed, source.ready().binding)
            assertFalse(source.ready().dialBusy)
            assertEquals(1, audio.starts.size)

            current = snapshot("IDLE", revision = 10)
            source.refresh()
            assertEquals(CallPhase.Idle, source.ready().phase)
            assertEquals(CallSyncState.Ready, source.ready().sync)
            assertFalse(source.ready().busy)
        } finally { source.close(); gate.complete(Unit) }
    }

    @Test fun equalIdleKeepsTokenlessCancelUntilDialTokenArrives() = runTest(UnconfinedTestDispatcher()) {
        val primary = FakeCallTransport()
        val gate = primary.enqueueGate()
        val methods = mutableListOf<String>()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), primary,
            control = CallControlTransport { _, method, _, _ ->
                methods += method
                if (method == "cancelCall") ok("accepted") else snapshot("IDLE", revision = 7)
            })
        try {
            source.dial(CALL_NUMBER)
            val binding = assertNotNull(source.ready().binding)
            source.act(binding, CallAction.Cancel)
            source.refresh()
            assertEquals(CallPhase.Canceling, source.ready().phase)
            assertTrue(source.ready().cancelPending)
            assertSame(binding, source.ready().binding)
            primary.emitVersioned("callState", CALL_TOKEN, "DIALING", "epoch-a", 8)
            assertEquals(1, methods.count { it == "cancelCall" })
        } finally { source.close(); gate.complete(Unit) }
    }

    @Test fun failedDialAfterEqualIdleAllowsSecondDialWithoutRestart() = runTest(UnconfinedTestDispatcher()) {
        val primary = FakeCallTransport()
        val first = primary.enqueueGate()
        primary.enqueue(protocolError(2002, "busy"))
        val second = primary.enqueueGate()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), primary,
            control = CallControlTransport { _, _, _, _ -> snapshot("IDLE", revision = 7) })
        try {
            source.dial(CALL_NUMBER)
            source.refresh()
            assertEquals(CallPhase.Dialing, source.ready().phase)
            first.complete(Unit)
            assertEquals(CallPhase.Idle, source.ready().phase)
            assertFalse(source.ready().dialBusy)
            assertEquals(CallSyncState.Ready, source.ready().sync)
            assertFalse(source.ready().busy)
            source.dial(CALL_NUMBER)
            assertEquals(CallPhase.Dialing, source.ready().phase)
            assertTrue(source.ready().dialBusy)
            assertEquals(listOf("dial", "dial"), primary.requests.map { it.method })
        } finally { source.close(); first.complete(Unit); second.complete(Unit) }
    }

    @Test fun newerIdleRetiresFirstDialAndLateCompletionCannotReplaceSecond() = runTest(UnconfinedTestDispatcher()) {
        val primary = FakeCallTransport()
        val first = primary.enqueueGate()
        val second = primary.enqueueGate()
        var current = snapshot("IDLE", revision = 7)
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), primary,
            control = CallControlTransport { _, _, _, _ -> current })
        try {
            source.dial(CALL_NUMBER)
            val retired = assertNotNull(source.ready().binding)
            current = snapshot("IDLE", revision = 9)
            source.refresh()
            assertEquals(CallPhase.Idle, source.ready().phase)
            assertEquals(CallSyncState.Ready, source.ready().sync)
            assertFalse(source.ready().busy)
            source.dial(CALL_NUMBER)
            val active = assertNotNull(source.ready().binding)
            assertFalse(retired.sameCall(active))
            first.complete(Unit)
            assertEquals(CallPhase.Dialing, source.ready().phase)
            assertTrue(source.ready().dialBusy)
            assertSame(active, source.ready().binding)
            assertNull(active.token)
            assertEquals(2, primary.requests.size)
        } finally { source.close(); first.complete(Unit); second.complete(Unit) }
    }

    @Test fun canceledRpcAcceptsEqualIdleAfterPendingOperationCompletes() = runTest(UnconfinedTestDispatcher()) {
        val primary = FakeCallTransport()
        val gate = primary.enqueueGate()
        primary.enqueue(protocolError(2005, "canceled"))
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), primary,
            control = CallControlTransport { _, _, _, _ -> snapshot("IDLE", revision = 7) })
        try {
            source.dial(CALL_NUMBER)
            source.cancel()
            gate.complete(Unit)
            assertEquals(CallPhase.Idle, source.ready().phase)
            assertFalse(source.ready().dialBusy)
            assertEquals(CallSyncState.Ready, source.ready().sync)
            assertFalse(source.ready().busy)
        } finally { source.close(); gate.complete(Unit) }
    }
}
