@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class EngineCallSyncNoticeTest {
    @Test fun initialUnregisteredNoticeClearsOnlyAfterAuthoritativeIdleRecovery() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport().apply { enqueueSnapshot(protocolError(3002, "synthetic rpc failure")) }
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        try {
            val failed = source.state.value as CallUiState.Ready
            assertEquals(CallSyncState.Unregistered, failed.sync)
            assertNotNull(failed.operationMessage)
            source.refresh()
            val ready = source.state.value as CallUiState.Ready
            assertEquals(CallSyncState.Ready, ready.sync)
            assertEquals(CallPhase.Idle, ready.phase)
            assertNull(ready.operationMessage)
        } finally { source.close() }
    }

    @Test fun sameEpochIdleRecoveryDropsSyncNoticeWithoutReplacingRecoveredBinding() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        try {
            transport.enqueueSnapshot(protocolError(3002, "synthetic rpc failure"))
            source.refresh()
            val failed = source.state.value as CallUiState.Ready
            assertEquals(CallSyncState.Unregistered, failed.sync)
            source.refresh()
            val ready = source.state.value as CallUiState.Ready
            assertEquals(CallSyncState.Ready, ready.sync)
            assertSame(failed.binding, ready.binding)
            assertNull(ready.operationMessage)
        } finally { source.close() }
    }

    @Test fun snapshotUnavailableNoticeClearsAfterAuthoritativeIdleRecovery() = runTest(UnconfinedTestDispatcher()) {
        // A freshly started engine can answer 2006 before IMS registers; the next confirmed IDLE ends that state.
        val transport = FakeCallTransport().apply { enqueueSnapshot(protocolError(2006, "call_snapshot_unavailable")) }
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        try {
            val failed = source.state.value as CallUiState.Ready
            assertEquals(CallSyncState.Unavailable, failed.sync)
            assertEquals(CallFailureMessages.SNAPSHOT, failed.operationMessage)
            source.refresh()
            val ready = source.state.value as CallUiState.Ready
            assertEquals(CallSyncState.Ready, ready.sync)
            assertEquals(CallPhase.Idle, ready.phase)
            assertNull(ready.operationMessage)
        } finally { source.close() }
    }

    @Test fun recoveryListNoticeClearsAfterAuthoritativeIdleRecovery() = runTest(UnconfinedTestDispatcher()) {
        var listFails = false
        val transport = FakeCallTransport()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, listInstances = {
            if (listFails) error("synthetic list failure") else listOf(callRunning(CALL_ICCID_A))
        })
        try {
            listFails = true
            source.refreshAvailability()
            assertEquals(CallFailureMessages.LIST, (source.state.value as CallUiState.Ready).operationMessage)
            listFails = false
            source.refresh()
            val ready = source.state.value as CallUiState.Ready
            assertEquals(CallSyncState.Ready, ready.sync)
            assertNull(ready.operationMessage)
        } finally { source.close() }
    }

    @Test fun repeatedUnregisteredSnapshotKeepsItsNotice() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport(responses = mapOf("getCallSnapshot" to protocolError(3002, "synthetic rpc failure")))
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        try {
            val message = assertNotNull((source.state.value as CallUiState.Ready).operationMessage)
            source.refresh()
            val state = source.state.value as CallUiState.Ready
            assertEquals(CallSyncState.Unregistered, state.sync)
            assertEquals(message, state.operationMessage)
        } finally { source.close() }
    }

    @Test fun dialRpcErrorWithSameWireCodeSurvivesSuccessfulIdlePoll() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport(responses = mapOf("dial" to protocolError(3002, "synthetic rpc failure")))
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        try {
            source.dial(CALL_NUMBER)
            val failed = source.state.value as CallUiState.Ready
            assertEquals(CallSyncState.Ready, failed.sync)
            val message = assertNotNull(failed.operationMessage)
            source.refresh()
            assertEquals(message, (source.state.value as CallUiState.Ready).operationMessage)
        } finally { source.close() }
    }

    @Test fun hangupRpcErrorSurvivesActiveAndTerminalSnapshotPolls() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport(responses = mapOf("hangup" to protocolError(3002, "synthetic rpc failure")))
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        try {
            source.dial(CALL_NUMBER)
            source.hangup()
            val failed = source.state.value as CallUiState.Ready
            assertEquals(CallSyncState.Ready, failed.sync)
            val message = assertNotNull(failed.operationMessage)
            source.refresh()
            assertEquals(message, (source.state.value as CallUiState.Ready).operationMessage)
            transport.enqueueSnapshot(EngineProtocolFrame.Response(1, buildJsonObject {
                put("epoch", "fixture-epoch"); put("revision", 1); put("stage", "IDLE")
                put("confirmedElapsedMs", 0); put("dtmfSupported", false)
            }, null))
            source.refresh()
            val idle = source.state.value as CallUiState.Ready
            assertEquals(CallPhase.Idle, idle.phase)
            assertEquals(message, idle.operationMessage)
        } finally { source.close() }
    }
}
