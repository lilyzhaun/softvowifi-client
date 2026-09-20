@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class EngineCallConnectingHangupTest {
    @Test fun acceptedHangupRetainsBindingUntilAuthoritativeEndAndWritesHistoryOnce() = runTest(UnconfinedTestDispatcher()) {
        val primary = FakeCallTransport()
        val history = FakeCallHistoryRepository()
        var current = snapshot("RINGING", direction = "INCOMING")
        val methods = mutableListOf<String>()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), primary, history = history,
            control = CallControlTransport { _, method, params, _ ->
                methods += method
                if (method == "getCallSnapshot") current else {
                    assertEquals("hangup", method)
                    assertEquals(CallProtocolCodec.params(CALL_TOKEN), params)
                    current = snapshot("ENDING", revision = 2, direction = "INCOMING")
                    ok("accepted")
                }
            })
        try {
            current = snapshot("CONNECTING", revision = 1, direction = "INCOMING")
            source.refresh()
            val binding = source.ready().binding!!
            source.act(binding, CallAction.Hangup)
            assertEquals(listOf("getCallSnapshot", "getCallSnapshot", "hangup", "getCallSnapshot"), methods)
            assertEquals(CallPhase.Ending, source.ready().phase)
            assertSame(binding, source.ready().binding)
            assertNull(history.listByOwner(OWNER_A).single().endedAtMs)
            source.act(binding, CallAction.Hangup)
            assertEquals(1, methods.count { it == "hangup" })
            primary.emitVersioned("incomingCall", CALL_TOKEN, "ENDED", "epoch-a", 3, "hangup")
            assertEquals(CallPhase.Idle, source.ready().phase)
            assertNotNull(history.listByOwner(OWNER_A).single().endedAtMs)
            val writes = history.upsertCount
            primary.emitVersioned("incomingCall", CALL_TOKEN, "ENDED", "epoch-a", 3, "hangup")
            assertEquals(writes, history.upsertCount)
            source.act(binding, CallAction.Hangup)
            assertEquals(1, methods.count { it == "hangup" })
        } finally { source.close() }
    }

    @Test fun endingEventPreservesIdentityAndClockButDisablesDtmf() = runTest(UnconfinedTestDispatcher()) {
        val primary = FakeCallTransport()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), primary,
            control = CallControlTransport { _, _, _, _ -> snapshot("IN_CALL", elapsed = 9000, dtmf = true) })
        try {
            val before = source.ready()
            primary.emitVersioned("callState", CALL_TOKEN, "ENDING", "epoch-a", 1)
            val ending = source.ready()
            assertEquals(CallPhase.Ending, ending.phase)
            assertSame(before.binding, ending.binding)
            assertSame(before.clock, ending.clock)
            assertEquals(before.peer, ending.peer)
            assertFalse(ending.dtmfSupported)
        } finally { source.close() }
    }

    @Test fun acceptedThenSnapshotFailureNeverInventsIdle() = runTest(UnconfinedTestDispatcher()) {
        var queries = 0
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), FakeCallTransport(),
            control = CallControlTransport { _, method, _, _ ->
                if (method != "getCallSnapshot") ok("accepted") else if (++queries == 1)
                    snapshot("CONNECTING", direction = "INCOMING") else protocolError(2006, "unavailable")
            })
        try {
            source.act(source.ready().binding!!, CallAction.Hangup)
            assertEquals(CallPhase.Ending, source.ready().phase)
            assertEquals(CallSyncState.Unavailable, source.ready().sync)
        } finally { source.close() }
    }

    @Test fun confirmedRaceCanReturnCompletedIdleFromConnecting() = runTest(UnconfinedTestDispatcher()) {
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), FakeCallTransport(),
            control = CallControlTransport { _, method, _, _ ->
                if (method == "getCallSnapshot") snapshot("CONNECTING", direction = "INCOMING")
                else EngineProtocolFrame.Response(1, buildJsonObject { put("state", "IDLE") }, null)
            })
        try {
            source.act(source.ready().binding!!, CallAction.Hangup)
            assertEquals(CallPhase.Idle, source.ready().phase)
        } finally { source.close() }
    }

    @Test fun malformedAcceptedResponseCannotCompleteOrFabricateInCall() = runTest(UnconfinedTestDispatcher()) {
        val malformed = listOf(
            buildJsonObject { put("accepted", true); put("callId", CALL_OTHER) },
            buildJsonObject { put("accepted", "true"); put("callId", CALL_TOKEN) },
            buildJsonObject { put("accepted", true); put("callId", CALL_TOKEN); put("state", "IDLE") },
        )
        for (result in malformed) {
            var controls = 0
            val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), FakeCallTransport(),
                control = CallControlTransport { _, method, _, _ ->
                    if (method == "getCallSnapshot") snapshot("CONNECTING", direction = "INCOMING")
                    else { controls++; EngineProtocolFrame.Response(1, result, null) }
                })
            try {
                source.act(source.ready().binding!!, CallAction.Hangup)
                assertEquals(1, controls)
                assertEquals(CallPhase.Connecting, source.ready().phase)
                assertEquals(CallFailureMessages.INVALID, source.ready().operationMessage)
            } finally { source.close() }
        }
    }

    @Test fun newerEndingEventBeforeAcceptedResponseStillQueriesWithoutLosingSession() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val primary = FakeCallTransport()
        var queries = 0
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), primary,
            control = CallControlTransport { _, method, _, _ ->
                if (method == "getCallSnapshot") snapshot(if (++queries == 1) "CONNECTING" else "ENDING",
                    revision = if (queries == 1) 0 else 1, direction = "INCOMING")
                else { gate.await(); ok("accepted") }
            })
        try {
            val binding = source.ready().binding!!
            source.act(binding, CallAction.Hangup)
            primary.emitVersioned("callState", CALL_TOKEN, "ENDING", "epoch-a", 1)
            gate.complete(Unit)
            assertEquals(2, queries)
            assertEquals(CallPhase.Ending, source.ready().phase)
            assertSame(binding, source.ready().binding)
        } finally { gate.complete(Unit); source.close() }
    }
}
