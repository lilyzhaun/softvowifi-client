@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CallSourceAggregateRefreshCoalescingTest {

    @Test
    fun refreshDuringInFlightRosterPollRerunsAndObservesRemovedSim() = runTest(StandardTestDispatcher()) {
        val first = FakeCallTransport()
        val second = FakeCallTransport()
        val instances = listOf(callRunning(CallAggregateFixture.ICCID_A), callRunning(CallAggregateFixture.ICCID_B))
        var activeRoster: List<String?> = listOf(CallAggregateFixture.ICCID_A, CallAggregateFixture.ICCID_B)
        var gate: CompletableDeferred<Unit>? = null
        var rosterReads = 0

        val source = CallSourceAggregate(
            EngineMessageRuntime(
                listInstances = { instances },
                transportFor = { if (it == CallAggregateFixture.ICCID_A) first else second },
                clock = { 1000 },
                activeIccids = {
                    rosterReads += 1
                    val snapshot = activeRoster
                    gate?.let { current ->
                        gate = null
                        current.await()
                    }
                    snapshot
                },
            ),
            FakeCallAudioController(),
            CallControlTransport { owner, method, params, timeout ->
                (if (owner == CallAggregateFixture.ICCID_A) first else second).request(method, params, timeout)
            },
            coroutineContext,
            FakeCallHistoryRepository(),
            { if (it == CallAggregateFixture.ICCID_A) OWNER_A else OWNER_B },
            { "synthetic-outgoing" },
        )
        try {
            runCurrent()
            source.selectLine(OWNER_A)
            assertTrue(source.lines.value.choices.single { it.owner == OWNER_A }.active)
            assertNotNull(source.lines.value.outgoingBinding)
            val readsBeforePoll = rosterReads

            // Start an in-flight background roster poll that suspends in activeIccids() with stale [A, B].
            val release = CompletableDeferred<Unit>()
            gate = release
            source.refreshRoster()
            runCurrent()
            assertEquals(readsBeforePoll + 1, rosterReads)

            // SIM A is removed while the poll is suspended, and subscriptionWatch invokes refresh().
            activeRoster = listOf(CallAggregateFixture.ICCID_B)
            source.refresh()
            runCurrent()

            // Release the stale in-flight poll; coalesced follow-up must rerun and observe [B].
            release.complete(Unit)
            runCurrent()

            assertEquals(readsBeforePoll + 2, rosterReads, "concurrent refresh() during in-flight poll was dropped")
            assertFalse(
                source.lines.value.choices.single { it.owner == OWNER_A }.active,
                "stale poll result remained active after SIM A removal",
            )
            assertNull(source.lines.value.outgoingBinding, "removed SIM A kept outgoingBinding")
        } finally {
            source.close()
        }
    }

    @Test
    fun multipleConcurrentRefreshesCoalesceToOneFollowUpAndPreserveRefreshChildren() = runTest(StandardTestDispatcher()) {
        val first = FakeCallTransport()
        val instances = listOf(callRunning(CallAggregateFixture.ICCID_A))
        var gate: CompletableDeferred<Unit>? = null
        var rosterReads = 0
        var snapshotReads = 0

        val source = CallSourceAggregate(
            EngineMessageRuntime(
                listInstances = { instances },
                transportFor = { first },
                clock = { 1000 },
                activeIccids = {
                    rosterReads += 1
                    gate?.let { current ->
                        gate = null
                        current.await()
                    }
                    listOf(CallAggregateFixture.ICCID_A)
                },
            ),
            FakeCallAudioController(),
            CallControlTransport { _, method, params, timeout ->
                if (method == "getCallSnapshot") snapshotReads += 1
                first.request(method, params, timeout)
            },
            coroutineContext,
            FakeCallHistoryRepository(),
            { OWNER_A },
            { "synthetic-outgoing" },
        )
        try {
            runCurrent()
            first.emitIncoming(CALL_TOKEN, "RINGING")
            runCurrent()
            assertEquals(CallPhase.Ringing, (source.state.value as CallUiState.Ready).phase)
            val readsBefore = rosterReads
            val snapshotsBefore = snapshotReads

            // Prepare a fresh snapshot that only refresh() (refreshChildren = true) will fetch while RINGING/Ready.
            first.enqueueSnapshot(
                EngineProtocolFrame.Response(
                    1,
                    buildJsonObject {
                        put("epoch", "fixture-epoch")
                        put("revision", 5)
                        put("stage", "IDLE")
                        put("confirmedElapsedMs", 0)
                        put("dtmfSupported", false)
                    },
                    null,
                ),
            )

            val release = CompletableDeferred<Unit>()
            gate = release
            source.refreshRoster()
            runCurrent()
            assertEquals(readsBefore + 1, rosterReads)

            // Queue refresh() (refreshChildren=true) followed by two refreshRoster() (refreshChildren=false).
            source.refresh()
            source.refreshRoster()
            source.refreshRoster()
            runCurrent()

            release.complete(Unit)
            runCurrent()

            assertEquals(readsBefore + 2, rosterReads, "expected exactly one coalesced follow-up roster read")
            assertTrue(
                snapshotReads > snapshotsBefore,
                "coalesced follow-up lost refreshChildren=true and skipped active child snapshot refresh",
            )
            assertEquals(CallPhase.Idle, (source.state.value as CallUiState.Ready).phase)
        } finally {
            source.close()
        }
    }

    @Test
    fun closeDuringInFlightRosterReadSkipsPendingFollowUpAndLeavesNoChildren() = runTest(StandardTestDispatcher()) {
        val first = FakeCallTransport()
        val instances = listOf(callRunning(CallAggregateFixture.ICCID_A))
        var gate: CompletableDeferred<Unit>? = null
        var rosterReads = 0

        val source = CallSourceAggregate(
            EngineMessageRuntime(
                listInstances = { instances },
                transportFor = { first },
                clock = { 1000 },
                activeIccids = {
                    rosterReads += 1
                    gate?.let { current ->
                        gate = null
                        current.await()
                    }
                    listOf(CallAggregateFixture.ICCID_A)
                },
            ),
            FakeCallAudioController(),
            CallControlTransport { _, method, params, timeout -> first.request(method, params, timeout) },
            coroutineContext,
            FakeCallHistoryRepository(),
            { OWNER_A },
            { "synthetic-outgoing" },
        )
        try {
            runCurrent()
            val readsBefore = rosterReads
            val release = CompletableDeferred<Unit>()
            gate = release
            source.refreshRoster()
            runCurrent()
            source.refresh()
            runCurrent()
            source.close()
            release.complete(Unit)
            runCurrent()
            assertEquals(readsBefore + 1, rosterReads, "closed aggregate must not execute pending rerun")
            assertTrue(source.children.isEmpty())
        } finally {
            source.close()
        }
    }
}
