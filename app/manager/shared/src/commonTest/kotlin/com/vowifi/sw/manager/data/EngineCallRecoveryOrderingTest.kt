@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*

class EngineCallRecoveryOrderingTest {
    @Test fun newEpochDuringFinalAttemptGetsOneRequiredSnapshotAfterOldResponse() = runTest {
        val wire = BackgroundCallWire()
        val audio = FakeCallAudioController()
        val source = EngineCallSource(
            EngineMessageRuntime({ listOf(callRunning(CALL_ICCID_A)) }, { wire }, { 0 }),
            audio, coroutineContext,
            control = CallControlTransport { owner, method, params, timeout ->
                val captured = wire.result
                wire.control.request(owner, method, params, timeout)
                captured
            },
        )
        val barrier = CompletableDeferred<Unit>()
        try {
            runCurrent()
            wire.available = false
            wire.disconnect(); runCurrent()
            advanceTimeBy(6_000); runCurrent()
            assertEquals(3, wire.connects)
            wire.available = true
            wire.gate = barrier
            advanceTimeBy(3_000); runCurrent()
            assertEquals(4, wire.connects)
            assertEquals(1, wire.inFlight)
            wire.result = snapshot("IN_CALL", epoch = "new-epoch", revision = 2, token = "new-call")
            repeat(2) { wire.incoming("new-call", "new-epoch", 2); runCurrent() }
            assertEquals(CallSyncState.Recovering, source.ready().sync)
            assertEquals(2, wire.snapshots)
            barrier.complete(Unit); runCurrent()
            assertEquals(CallSyncState.Ready, source.ready().sync)
            assertEquals(CallPhase.InCall, source.ready().phase)
            assertEquals("new-epoch", source.ready().binding?.epoch)
            assertEquals(3, wire.snapshots)
            assertEquals(1, wire.maximumInFlight)
            assertTrue(audio.starts.isEmpty())
            assertTrue(wire.commands.isEmpty())
            advanceTimeBy(30_000); runCurrent()
            assertEquals(5, wire.connects)
            assertRestorable(source, wire, audio)
        } finally { source.close(); barrier.complete(Unit) }
    }

    @Test fun idleOwnerReplacementInitialSubscriptionSurvivesCanceledRecovery() = runTest(UnconfinedTestDispatcher()) {
        val first = BackgroundCallWire()
        val second = BackgroundCallWire().apply {
            result = snapshot("IN_CALL", epoch = "owner-b-epoch", token = "owner-b-call")
        }
        val audio = FakeCallAudioController()
        val listing = CompletableDeferred<Unit>()
        val cleanupHeld = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var replace = false
        lateinit var source: EngineCallSource
        source = EngineCallSource(
            EngineMessageRuntime({
                if (replace) listing.await()
                listOf(callRunning(if (replace) CALL_ICCID_B else CALL_ICCID_A))
            }, {
                if (it == CALL_ICCID_A) first else {
                    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        source.mutex.withLock { cleanupHeld.complete(Unit); releaseCleanup.await() }
                    }
                    second
                }
            }, { 0 }),
            audio, coroutineContext,
            control = CallControlTransport { owner, method, params, timeout ->
                (if (owner == CALL_ICCID_A) first else second).control.request(owner, method, params, timeout)
            },
        )
        try {
            assertEquals(CallSyncState.Ready, source.ready().sync)
            first.disconnect()
            replace = true
            advanceTimeBy(3_000); runCurrent()
            assertEquals(0, second.connects)
            listing.complete(Unit); runCurrent()
            assertTrue(cleanupHeld.isCompleted)
            releaseCleanup.complete(Unit); runCurrent()
            assertTrue(first.closed)
            assertEquals(1, second.events.subscriptionCount.value)
            assertEquals(1, second.connects)
            assertEquals(CallSyncState.Ready, source.ready().sync)
            assertEquals(CALL_ICCID_B, source.ready().binding?.owner)
            assertEquals(CallPhase.InCall, source.ready().phase)
            assertTrue(audio.starts.isEmpty())
            assertTrue(first.commands.isEmpty() && second.commands.isEmpty())
            advanceTimeBy(30_000); runCurrent()
            assertEquals(1, second.connects)
            assertRestorable(source, second, audio)
        } finally { releaseCleanup.complete(Unit); runCurrent(); source.close(); listing.complete(Unit) }
    }

    private suspend fun TestScope.assertRestorable(
        source: EngineCallSource, wire: BackgroundCallWire, audio: FakeCallAudioController,
    ) {
        assertEquals(CallFailureMessages.AUDIO_INTERRUPTED, assertIs<CallAudioState.Failed>(source.ready().audio).message)
        assertTrue(audio.starts.isEmpty())
        source.fetchSnapshot(wire, recovering = true)
        assertIs<CallAudioState.Failed>(source.ready().audio)
        val binding = assertNotNull(source.ready().binding)
        var grants = 0
        val grant: (String, Long) -> Boolean = { owner, _ ->
            assertEquals(binding.owner, owner)
            grants++
            true
        }
        source.resumePreparedAudio(binding, grant); runCurrent()
        assertEquals(CallAudioState.Active, source.ready().audio)
        source.resumePreparedAudio(binding, grant); runCurrent()
        source.fetchSnapshot(wire, recovering = true)
        assertEquals(CallAudioState.Active, source.ready().audio)
        assertEquals(1, grants)
        assertEquals(1, audio.starts.size)
        wire.result = snapshot("IN_CALL", epoch = "replacement-epoch", token = "replacement-call")
        source.fetchSnapshot(wire, recovering = true)
        assertIs<CallAudioState.Failed>(source.ready().audio)
        assertEquals(1, audio.starts.size)
        assertFalse(binding.sameCall(assertNotNull(source.ready().binding)))
        assertTrue(wire.commands.isEmpty())
    }

    @Test fun closeDiscardsRequiredSnapshotQueuedBehindAutomaticRead() = runTest {
        val wire = BackgroundCallWire()
        val source = EngineCallSource(
            EngineMessageRuntime({ listOf(callRunning(CALL_ICCID_A)) }, { wire }, { 0 }),
            FakeCallAudioController(), coroutineContext, control = wire.control,
        )
        val barrier = CompletableDeferred<Unit>()
        try {
            runCurrent()
            wire.disconnect(); runCurrent()
            wire.gate = barrier
            advanceTimeBy(3_000); runCurrent()
            wire.incoming("new-call", "new-epoch", 2); runCurrent()
            val state = source.state.value
            source.close()
            barrier.complete(Unit); runCurrent()
            advanceTimeBy(30_000); runCurrent()
            assertEquals(2, wire.connects)
            assertEquals(2, wire.snapshots)
            assertEquals(state, source.state.value)
            assertTrue(wire.closed)
        } finally { source.close(); barrier.complete(Unit) }
    }
}
