@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class EngineCallSnapshotRaceTest {
    @Test
    fun eventsBufferedDuringInitialSnapshotCannotResurrectOlderCall() = runTest(UnconfinedTestDispatcher()) {
        val primary = FakeCallTransport(connectHandler = {
            emitVersioned("incomingCall", CALL_TOKEN, "RINGING", "epoch-a", 4)
        })
        val control = CallControlTransport { _, _, _, _ -> snapshot("IDLE", revision = 5) }
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), primary, control = control)
        try { assertEquals(CallPhase.Idle, source.ready().phase) } finally { source.close() }
    }

    @Test
    fun lateSnapshotLosesToNewerTerminalEvent() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        var queries = 0
        val primary = FakeCallTransport()
        val control = CallControlTransport { _, _, _, _ ->
            if (++queries > 1) gate.await()
            snapshot("IN_CALL", revision = 4, dtmf = true)
        }
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), primary, control = control)
        try {
            source.refresh()
            primary.emitVersioned("callEnded", CALL_TOKEN, null, "epoch-a", 5, "remote")
            gate.complete(Unit)
            assertEquals(CallPhase.Idle, source.ready().phase)
            primary.emitVersioned("incomingCall", CALL_TOKEN, "RINGING", "epoch-a", 4)
            assertEquals(CallPhase.Idle, source.ready().phase)
        } finally { gate.complete(Unit); source.close() }
    }

    @Test
    fun unknownEpochRequiresAuthorityAndLateDialResponseCannotCrossGeneration() = runTest(UnconfinedTestDispatcher()) {
        val dialGate = CompletableDeferred<Unit>()
        val primary = FakeCallTransport(requestGate = dialGate, gatedMethods = setOf("dial"))
        var authoritative = snapshot("IDLE", epoch = "epoch-z", revision = 20)
        val controls = CallControlTransport { _, _, _, _ -> authoritative }
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), primary, control = controls)
        try {
            source.dial("+12025550123")
            val oldBinding = source.ready().binding!!
            authoritative = snapshot("IDLE", epoch = "epoch-a", revision = 0)
            primary.emitVersioned("incomingCall", CALL_OTHER, "IN_CALL", "epoch-a", 30)
            assertEquals(CallPhase.Idle, source.ready().phase)
            dialGate.complete(Unit)
            source.act(oldBinding, CallAction.Cancel)
            assertEquals(CallPhase.Idle, source.ready().phase)
            assertEquals(1, primary.requests.size)
        } finally { dialGate.complete(Unit); source.close() }
    }

    @Test
    fun latestSnapshotRequestWinsAndReplacedTokenRejectsOldAction() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        var count = 0
        val control = CallControlTransport { _, _, _, _ ->
            when (++count) {
                1 -> snapshot("IN_CALL", revision = 2)
                2 -> { gate.await(); snapshot("IN_CALL", revision = 3) }
                else -> snapshot("IN_CALL", revision = 5, token = CALL_OTHER)
            }
        }
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), FakeCallTransport(), control = control)
        try {
            val old = source.ready().binding!!
            source.refresh(); source.refresh(); gate.complete(Unit)
            val current = source.ready().binding!!
            assertNotSame(old, current)
            source.act(old, CallAction.Hangup)
            assertEquals(3, count)
            assertEquals(CallPhase.InCall, source.ready().phase)
        } finally { gate.complete(Unit); source.close() }
    }

    @Test
    fun snapshotFailureRetainsCallAndDisablesCommandsUntilConfirmed() = runTest(UnconfinedTestDispatcher()) {
        var result = snapshot("IN_CALL", elapsed = 2500)
        val controls = CallControlTransport { _, _, _, _ -> result }
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), FakeCallTransport(), control = controls)
        try {
            result = protocolError(2006, "call_snapshot_unavailable")
            source.refresh()
            assertEquals(CallPhase.InCall, source.ready().phase)
            assertEquals(CallSyncState.Unavailable, source.ready().sync)
            assertEquals(CallFailureMessages.SNAPSHOT, source.ready().operationMessage)
            result = protocolError(3002, "not registered")
            source.refresh()
            assertEquals(CallSyncState.Unregistered, source.ready().sync)
            assertEquals(CallPhase.InCall, source.ready().phase)
        } finally { source.close() }
    }

    @Test
    fun confirmedClockUsesMonotonicAnchorAndSameRevisionCanRefreshElapsed() = runTest(UnconfinedTestDispatcher()) {
        var now = 100L
        var elapsed = 4000L
        val controls = CallControlTransport { _, _, _, _ -> snapshot("IN_CALL", elapsed = elapsed) }
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), FakeCallTransport(), control = controls, monotonicNow = { now })
        try {
            now += 2000
            assertEquals("00:06", source.ready().clock!!.formatted())
            elapsed = 8000
            source.refresh()
            assertEquals("00:08", source.ready().clock!!.formatted())
            now += 3_600_000
            assertEquals("01:00:08", source.ready().clock!!.formatted())
        } finally { source.close() }
    }
}
