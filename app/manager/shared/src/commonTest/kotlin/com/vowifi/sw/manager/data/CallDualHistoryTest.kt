@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class CallDualHistoryTest {
    @Test fun reversedTerminalKeepsBothOwners() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeCallHistoryRepository()
        val recorder = CallHistoryRecorder(CallHistoryBinding(repository,
            { if (it == "a") OWNER_A else OWNER_B }, { "synthetic-correlation" }), { 1000 }, coroutineContext)
        val ringing = CallSession(token = "shared-token", phase = CallPhase.Ringing)
        try {
            recorder.observe("a", 1, CallSession(), ringing, CallHistoryCause.Session)?.let(recorder::enqueue)
            recorder.observe("b", 1, CallSession(), ringing, CallHistoryCause.Session)?.let(recorder::enqueue)
            recorder.observe("a", 1, ringing, CallSession(), CallHistoryCause.Reject)?.let(recorder::enqueue)
            recorder.observe("b", 1, ringing, CallSession(), CallHistoryCause.Reject)?.let(recorder::enqueue)
            assertEquals(CallOutcome.REJECTED, repository.listByOwner(OWNER_A).single().outcome)
            assertEquals(CallOutcome.REJECTED, repository.listByOwner(OWNER_B).single().outcome)
        } finally { recorder.close() }
    }

    @Test fun collidingOwnerCannotConnectOrCloseAnotherRow() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeCallHistoryRepository()
        val recorder = CallHistoryRecorder(CallHistoryBinding(repository,
            { if (it == "a") OWNER_A else OWNER_B }, { "synthetic-correlation" }), { 1000 }, coroutineContext)
        val ringing = CallSession(token = "shared-token", phase = CallPhase.Ringing)
        try {
            recorder.observe("a", 1, CallSession(), ringing, CallHistoryCause.Session)?.let(recorder::enqueue)
            recorder.observe("b", 1, ringing, ringing.copy(phase = CallPhase.InCall), CallHistoryCause.Session)?.let(recorder::enqueue)
            recorder.observe("b", 1, ringing, CallSession(), CallHistoryCause.Reject)?.let(recorder::enqueue)
            assertEquals(CallOutcome.STARTED, repository.listByOwner(OWNER_A).single().outcome)
        } finally { recorder.close() }
    }

    @Test fun differentTokenCannotCloseSameOwnerGeneration() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeCallHistoryRepository()
        val recorder = CallHistoryRecorder(CallHistoryBinding(repository, { OWNER_A }, { "synthetic-correlation" }),
            { 1000 }, coroutineContext)
        val ringing = CallSession(token = "original", phase = CallPhase.Ringing)
        try {
            recorder.observe("a", 1, CallSession(), ringing, CallHistoryCause.Session)?.let(recorder::enqueue)
            recorder.observe("a", 1, ringing.copy(token = "other"), CallSession(), CallHistoryCause.Reject)?.let(recorder::enqueue)
            assertEquals(CallOutcome.STARTED, repository.listByOwner(OWNER_A).single().outcome)
        } finally { recorder.close() }
    }

    @Test fun copiedBindingCannotDial() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        try {
            val binding = (source.state.value as CallUiState.Ready).binding!!
            source.dial(CallBinding(binding.owner, binding.token, binding.generation, binding.epoch), CALL_NUMBER)
            assertTrue(transport.requests.isEmpty())
        } finally { source.close() }
    }
}
