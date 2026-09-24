@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CallSourceAggregateOwnershipTest {
    @Test fun lifecycleRosterRefreshRechecksRegistrationWithoutReplacingChild() = runTest(UnconfinedTestDispatcher()) {
        val fixture = CallAggregateFixture(coroutineContext, listOf(CallAggregateFixture.ICCID_A), onlyFirst = true)
        try {
            fixture.first.enqueueSnapshot(protocolError(3002, "synthetic unregistered"))
            fixture.source.refresh()
            assertNull(fixture.source.lines.value.outgoingBinding)
            assertEquals(false, fixture.source.lines.value.choices.single().registered)
            fixture.source.refreshRoster()
            assertNotNull(fixture.source.lines.value.outgoingBinding)
            assertEquals(1, fixture.recoveries)
            assertTrue(fixture.audio.starts.isEmpty())
        } finally { fixture.source.close() }
    }

    @Test fun retirementBeforeQueuedDialStartsReleasesPreparationForOtherLine() = runTest {
        val fixture = CallAggregateFixture(coroutineContext)
        try {
            runCurrent()
            fixture.source.selectLine(OWNER_A)
            val binding = fixture.source.lines.value.outgoingBinding!!
            fixture.source.dial(binding, CALL_NUMBER)
            fixture.source.resolve(binding)!!.close()
            fixture.instances = fixture.instances.filter { it.iccid != CallAggregateFixture.ICCID_A }
            fixture.source.refresh()
            runCurrent()
            fixture.source.selectLine(OWNER_B)
            assertNotNull(fixture.source.lines.value.outgoingBinding)
            assertTrue(fixture.first.requests.isEmpty())
        } finally { fixture.source.close() }
    }

    @Test fun failedCrossStreamSnapshotDoesNotCreateAnImmediateRetryLoop() = runTest(UnconfinedTestDispatcher()) {
        val fixture = CallAggregateFixture(coroutineContext)
        try {
            fixture.first.emitIncoming(CALL_TOKEN, "RINGING")
            fixture.firstSnapshotsToFail = 3
            fixture.second.emitIncoming(CALL_OTHER, "RINGING")
            assertEquals(1, fixture.failedSnapshots)
            assertEquals(CallSyncState.Unavailable, (fixture.source.state.value as CallUiState.Ready).sync)
            assertEquals(CallAggregateFixture.ICCID_A, (fixture.source.state.value as CallUiState.Ready).binding!!.owner)
        } finally { fixture.source.close() }
    }

    @Test fun replacementRejectsOldBindingAndIdleOtherChildCannotStopAudio() = runTest(UnconfinedTestDispatcher()) {
        val fixture = CallAggregateFixture(coroutineContext)
        try {
            fixture.source.selectLine(OWNER_A)
            val old = assertNotNull(fixture.source.lines.value.outgoingBinding)
            fixture.first = FakeCallTransport()
            fixture.instances = fixture.instances.map { if (it.iccid == CallAggregateFixture.ICCID_A) it.copy(pid = 8) else it }
            fixture.source.refresh()
            val replacement = assertNotNull(fixture.source.lines.value.outgoingBinding)
            assertNotSame(old, replacement)
            fixture.source.dial(old, CALL_NUMBER)
            assertTrue(fixture.first.requests.isEmpty())
            fixture.source.dial(replacement, CALL_NUMBER)
            assertEquals(CALL_TOKEN, fixture.audio.activeToken)
            val stops = fixture.audio.stopCalls
            fixture.instances = fixture.instances.filter { it.iccid != CallAggregateFixture.ICCID_B }
            fixture.source.refresh()
            assertEquals(CALL_TOKEN, fixture.audio.activeToken)
            assertEquals(stops, fixture.audio.stopCalls)
            assertEquals(1, fixture.recoveries)
        } finally { fixture.source.close() }
    }

    @Test fun reversedTerminalUsesSnapshotBeforePresentingBAndKeepsBothHistories() = runTest(UnconfinedTestDispatcher()) {
        val fixture = CallAggregateFixture(coroutineContext)
        try {
            fixture.source.selectLine(OWNER_A)
            fixture.first.emitIncoming(CALL_TOKEN, "RINGING")
            fixture.first.enqueueSnapshot(protocolError(2006, "synthetic failure"))
            fixture.second.emitIncoming(CALL_OTHER, "RINGING")
            assertEquals(CallAggregateFixture.ICCID_A, (fixture.source.state.value as CallUiState.Ready).binding!!.owner)
            assertEquals(1, fixture.repository.listByOwner(OWNER_A).size)
            assertEquals(1, fixture.repository.listByOwner(OWNER_B).size)
            fixture.first.enqueueSnapshot(EngineProtocolFrame.Response(1, buildJsonObject {
                put("epoch", "fixture-epoch"); put("revision", 3); put("stage", "IDLE")
                put("confirmedElapsedMs", 0); put("dtmfSupported", false)
            }, null))
            fixture.source.refresh()
            val incoming = fixture.source.state.value as CallUiState.Ready
            assertEquals(CallAggregateFixture.ICCID_B, incoming.binding!!.owner)
            assertEquals(CallPhase.Ringing, incoming.phase)
            fixture.first.emitIncoming(CALL_TOKEN, "ENDED", "remote")
            fixture.second.emitIncoming(CALL_OTHER, "ENDED", "timeout")
            assertNotNull(fixture.repository.listByOwner(OWNER_A).single().endedAtMs)
            assertEquals(CallOutcome.MISSED, fixture.repository.listByOwner(OWNER_B).single().outcome)
            assertEquals(OWNER_A, fixture.source.lines.value.selectedOwner)
        } finally { fixture.source.close() }
    }

    @Test fun historySelectionIsIndependentAndGlobalOpenRowBlocksClear() = runTest(UnconfinedTestDispatcher()) {
        val fixture = CallAggregateFixture(coroutineContext)
        try {
            fixture.first.emitIncoming(CALL_TOKEN, "RINGING")
            fixture.first.emitIncoming(CALL_TOKEN, "ENDED", "timeout")
            fixture.second.emitIncoming(CALL_OTHER, "RINGING")
            val choice = fixture.source.historyOwners.value.single { it.owner == OWNER_A }
            fixture.source.selectHistoryOwner(choice)
            fixture.source.selectLine(OWNER_B)
            fixture.source.refresh()
            assertEquals(OWNER_A, fixture.repository.lastOwner())
            fixture.source.clearHistory(fixture.source.history.value as CallHistoryState.Ready)
            assertEquals(1, fixture.repository.listByOwner(OWNER_A).size)
            fixture.instances = fixture.instances.filter { it.iccid != CallAggregateFixture.ICCID_A }
            fixture.source.refresh()
            fixture.second.emitIncoming(CALL_OTHER, "ENDED", "timeout")
            assertEquals(CallOutcome.MISSED, fixture.repository.listByOwner(OWNER_B).single().outcome)
            assertEquals(1, fixture.recoveries)
        } finally { fixture.source.close() }
    }
}
