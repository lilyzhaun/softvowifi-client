@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class EngineCallRecoveryStopTest {
    @Test fun ownerStopDiscardsEpochReadQueuedDuringAutomaticListing() = runTest {
        assertStoppedQueue(permissionDenied = false)
    }

    @Test fun permissionStopDiscardsEpochReadQueuedDuringAutomaticListing() = runTest {
        assertStoppedQueue(permissionDenied = true)
    }

    private suspend fun TestScope.assertStoppedQueue(permissionDenied: Boolean) {
        val wire = BackgroundCallWire().apply { result = snapshot("IN_CALL", revision = 3) }
        val audio = FakeCallAudioController()
        val listing = CompletableDeferred<Unit>()
        var stop = false
        val source = EngineCallSource(
            EngineMessageRuntime({
                if (stop) {
                    listing.await()
                    if (permissionDenied) throw MessagePermissionException("denied")
                }
                listOf(callRunning(if (stop) CALL_ICCID_B else CALL_ICCID_A))
            }, { wire }, { 0 }),
            audio, coroutineContext, control = wire.control,
        )
        try {
            runCurrent()
            assertEquals(CallPhase.InCall, source.ready().phase)
            val starts = audio.starts.size
            stop = true
            wire.disconnect(); runCurrent()
            advanceTimeBy(3_000); runCurrent()
            wire.result = snapshot("IN_CALL", epoch = "new-epoch", revision = 2, token = "new-call")
            wire.events.emit(EngineProtocolFrame.Event("incomingCall", buildJsonObject {
                put("callId", "new-call"); put("state", "RINGING")
                put("epoch", "new-epoch"); put("revision", 2)
            }))
            runCurrent()
            assertEquals(CallSyncState.Recovering, source.ready().sync)
            assertEquals(1, wire.snapshots)
            listing.complete(Unit); runCurrent()
            val expected = if (permissionDenied) CallSyncState.Unavailable else CallSyncState.OwnerChanged
            assertEquals(expected, source.ready().sync)
            assertEquals(CALL_ICCID_A, source.ready().binding?.owner)
            assertEquals("epoch-a", source.ready().binding?.epoch)
            assertIs<CallAudioState.Failed>(source.ready().audio)
            advanceTimeBy(30_000); runCurrent()
            assertEquals(expected, source.ready().sync)
            assertEquals(1, wire.connects)
            assertEquals(1, wire.snapshots)
            assertEquals(starts, audio.starts.size)
            assertTrue(wire.commands.isEmpty())
            stop = false
            wire.result = snapshot("IDLE", epoch = "new-epoch", revision = 3)
            source.refresh(); runCurrent()
            assertEquals(CallSyncState.Ready, source.ready().sync)
            assertEquals(CallPhase.Idle, source.ready().phase)
            assertEquals(2, wire.snapshots)
        } finally { source.close(); listing.complete(Unit) }
    }
}
