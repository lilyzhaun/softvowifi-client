@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*

class EngineCallBackgroundRecoveryTest {
    @Test fun disconnectedIdleRecoversAndReceivesNewIncomingWithoutActivityRefresh() = runTest {
        val wire = BackgroundCallWire()
        val audio = FakeCallAudioController()
        val source = source(wire, audio)
        try {
            runCurrent()
            assertEquals(CallSyncState.Ready, source.ready().sync)
            wire.disconnect(); runCurrent()
            assertEquals(CallSyncState.Recovering, source.ready().sync)
            advanceTimeBy(3_000); runCurrent()
            assertEquals(CallSyncState.Ready, source.ready().sync)
            wire.incoming(); runCurrent()
            assertEquals(CallPhase.Ringing, source.ready().phase)
            assertEquals("next-call", source.ready().binding?.token)
            assertEquals(2, wire.connects)
            assertTrue(wire.commands.isEmpty())
            assertTrue(audio.starts.isEmpty())
        } finally { source.close() }
    }

    @Test fun failedAttemptThenAvailableServerRecoversOnNextBoundedAttempt() = runTest {
        val wire = BackgroundCallWire()
        val source = source(wire)
        try {
            runCurrent()
            wire.available = false
            wire.disconnect(); runCurrent()
            advanceTimeBy(3_000); runCurrent()
            assertEquals(2, wire.connects)
            assertFalse(wire.closed)
            wire.available = true
            advanceTimeBy(3_000); runCurrent()
            assertEquals(CallSyncState.Ready, source.ready().sync)
            wire.incoming(); runCurrent()
            assertEquals(CallPhase.Ringing, source.ready().phase)
            advanceTimeBy(30_000); runCurrent()
            assertEquals(3, wire.connects)
            assertTrue(wire.commands.isEmpty())
        } finally { source.close() }
    }

    @Test fun repeatedDisconnectDoesNotOverlapSnapshotOrCreateAnotherTransport() = runTest {
        val wire = BackgroundCallWire()
        var transports = 0
        val source = source(wire, transportCreated = { transports++ })
        val gate = CompletableDeferred<Unit>()
        try {
            runCurrent()
            wire.gate = gate
            source.refresh(); runCurrent()
            repeat(3) { wire.disconnect(); runCurrent() }
            advanceTimeBy(3_000); runCurrent()
            assertEquals(2, wire.snapshots)
            gate.complete(Unit); runCurrent()
            wire.gate = null
            advanceTimeBy(3_000); runCurrent()
            assertEquals(CallSyncState.Ready, source.ready().sync)
            assertEquals(1, wire.maximumInFlight)
            assertEquals(1, transports)
            assertEquals(1, wire.events.subscriptionCount.value)
            assertTrue(wire.commands.isEmpty())
            val automaticGate = CompletableDeferred<Unit>()
            wire.gate = automaticGate
            wire.disconnect(); runCurrent()
            advanceTimeBy(3_000); runCurrent()
            val snapshots = wire.snapshots
            source.refresh(); source.refresh(); runCurrent()
            assertEquals(snapshots, wire.snapshots)
            automaticGate.complete(Unit); runCurrent()
            assertEquals(CallSyncState.Ready, source.ready().sync)
            assertEquals(1, wire.maximumInFlight)
        } finally { source.close(); gate.complete(Unit) }
    }

    @Test fun closeCancelsFutureAttemptsAndPermanentFailureHasFiniteBudget() = runTest {
        val wire = BackgroundCallWire()
        val source = source(wire)
        runCurrent()
        wire.disconnect(); runCurrent()
        source.close()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, wire.connects)
        assertTrue(wire.closed)
        val unavailable = BackgroundCallWire()
        val retrying = source(unavailable)
        try {
            runCurrent()
            unavailable.available = false
            unavailable.disconnect(); runCurrent()
            advanceTimeBy(30_000); runCurrent()
            assertEquals(4, unavailable.connects)
            assertTrue(unavailable.commands.isEmpty())
        } finally { retrying.close() }
    }

    @Test fun activeCallEpochRecoveryRejectsRetiredTokenAndOwnerChangeStopsAttempts() = runTest {
        val wire = BackgroundCallWire()
        val audio = FakeCallAudioController()
        var owner = CALL_ICCID_A
        val source = source(wire, audio, owner = { owner },
            history = CallHistoryBinding(FakeCallHistoryRepository(), { OWNER_A }, { "synthetic-recovered" }))
        try {
            runCurrent()
            wire.incoming(CALL_TOKEN); runCurrent()
            wire.result = snapshot("IN_CALL", revision = 3, direction = "INCOMING")
            source.refresh(); runCurrent()
            val retired = assertNotNull(source.ready().binding)
            val starts = audio.starts.size
            assertEquals(1, historyRecords(source).size)
            wire.disconnect(); runCurrent()
            assertIs<CallAudioState.Failed>(source.ready().audio)
            advanceTimeBy(3_000); runCurrent()
            assertEquals(CallPhase.InCall, source.ready().phase)
            assertEquals(starts, audio.starts.size)
            assertEquals(1, historyRecords(source).size)
            wire.result = snapshot("IDLE", epoch = "new-epoch", revision = 0)
            wire.disconnect(); runCurrent()
            advanceTimeBy(3_000); runCurrent()
            wire.incoming(CALL_TOKEN, "epoch-a", 4); runCurrent()
            assertEquals(CallPhase.Idle, source.ready().phase)
            assertEquals(1, historyRecords(source).size)
            source.act(retired, CallAction.Hangup); runCurrent()
            assertTrue(wire.commands.isEmpty())
            wire.incoming("new-call", "new-epoch", 1); runCurrent()
            assertEquals(CallPhase.Ringing, source.ready().phase)
            owner = CALL_ICCID_B
            wire.disconnect(); runCurrent()
            advanceTimeBy(3_000); runCurrent()
            assertEquals(CallSyncState.OwnerChanged, source.ready().sync)
            val connects = wire.connects
            advanceTimeBy(30_000); runCurrent()
            assertEquals(connects, wire.connects)
            assertEquals(CALL_ICCID_A, source.ready().binding?.owner)
            assertTrue(wire.commands.isEmpty())
        } finally { source.close() }
    }

    @Test fun transientListingFailureRetriesButPermissionDenialStopsWithoutOwnerSwitch() = runTest {
        val wire = BackgroundCallWire()
        val source = source(wire)
        try {
            runCurrent()
            wire.listingFailure = IllegalStateException("list failed")
            wire.disconnect(); runCurrent()
            advanceTimeBy(3_000); runCurrent()
            assertEquals(2, wire.listings)
            assertFalse(wire.closed)
            wire.listingFailure = null
            advanceTimeBy(3_000); runCurrent()
            assertEquals(CallSyncState.Ready, source.ready().sync)
            wire.listingFailure = MessagePermissionException("denied")
            wire.disconnect(); runCurrent()
            advanceTimeBy(3_000); runCurrent()
            assertIs<CallUiState.PermissionDenied>(source.state.value)
            val listings = wire.listings
            advanceTimeBy(30_000); runCurrent()
            assertEquals(listings, wire.listings)
            assertTrue(wire.closed)
            assertTrue(wire.commands.isEmpty())
        } finally { source.close() }
    }

    private fun TestScope.source(wire: BackgroundCallWire, audio: FakeCallAudioController = FakeCallAudioController(),
                                 owner: () -> String = { CALL_ICCID_A }, transportCreated: () -> Unit = {},
                                 history: CallHistoryBinding? = null) =
        EngineCallSource(EngineMessageRuntime({
            wire.listings++
            wire.listingFailure?.let { throw it }
            listOf(callRunning(owner()))
        }, { transportCreated(); wire }, { 0 }),
            audio, coroutineContext, history = history, control = wire.control)
}
