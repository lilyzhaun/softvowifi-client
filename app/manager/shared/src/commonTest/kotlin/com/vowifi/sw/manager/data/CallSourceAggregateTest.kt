@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class CallSourceAggregateTest {
    @Test fun bothStreamsStayAttachedAndIncomingDoesNotChooseOutgoingLine() = runTest(UnconfinedTestDispatcher()) {
        val fixture = CallAggregateFixture(coroutineContext)
        try {
            assertEquals(1, fixture.first.connectCalls)
            assertEquals(1, fixture.second.connectCalls)
            assertNull(fixture.source.lines.value.outgoingBinding)
            fixture.source.selectLine(OWNER_A)
            fixture.second.emitIncoming(CALL_TOKEN, "RINGING")
            val ready = assertIs<CallUiState.Ready>(fixture.source.state.value)
            assertEquals(CallPhase.Ringing, ready.phase)
            assertEquals(CallAggregateFixture.ICCID_B, ready.binding!!.owner)
            assertEquals(OWNER_A, fixture.source.lines.value.selectedOwner)
            fixture.source.act(ready.binding, CallAction.Reject)
            assertEquals(listOf("reject"), fixture.second.requests.map { it.method })
            assertTrue(fixture.first.requests.isEmpty())
        } finally { fixture.source.close() }
    }

    @Test fun explicitDialUsesOriginalBindingAndRemovalNeverFallsBack() = runTest(UnconfinedTestDispatcher()) {
        val fixture = CallAggregateFixture(coroutineContext)
        try {
            fixture.source.selectLine(OWNER_B)
            val binding = assertNotNull(fixture.source.lines.value.outgoingBinding)
            assertSame(binding, (fixture.source.state.value as CallUiState.Ready).binding)
            fixture.source.dial(CallBinding(binding.owner, null, binding.generation, binding.epoch), CALL_NUMBER)
            assertTrue(fixture.second.requests.isEmpty())
            fixture.source.dial(binding, CALL_NUMBER)
            assertEquals("dial", fixture.second.requests.single().method)
            fixture.instances = listOf(callRunning(CallAggregateFixture.ICCID_A))
            fixture.source.refresh()
            assertEquals(OWNER_B, fixture.source.lines.value.selectedOwner)
            assertNull(fixture.source.lines.value.outgoingBinding)
            fixture.source.dial(binding, CALL_NUMBER)
            assertTrue(fixture.first.requests.isEmpty())
        } finally { fixture.source.close() }
    }

    @Test fun automaticChoiceUsesFullRawRosterNotRunningCount() = runTest(UnconfinedTestDispatcher()) {
        for (roster in listOf(null, listOf(CallAggregateFixture.ICCID_A, null),
            listOf(CallAggregateFixture.ICCID_A, CallAggregateFixture.ICCID_B))) {
            val fixture = CallAggregateFixture(coroutineContext, roster, onlyFirst = true)
            try {
                assertNull(fixture.source.lines.value.selectedOwner)
                assertNull(fixture.source.lines.value.outgoingBinding)
            } finally { fixture.source.close() }
        }
        val single = CallAggregateFixture(coroutineContext, listOf(CallAggregateFixture.ICCID_A), onlyFirst = true)
        try { assertNotNull(single.source.lines.value.outgoingBinding) } finally { single.source.close() }
    }
}

internal class CallAggregateFixture(
    context: kotlin.coroutines.CoroutineContext,
    var roster: List<String?>? = listOf(ICCID_A, ICCID_B),
    onlyFirst: Boolean = false,
) {
    var instances = if (onlyFirst) listOf(callRunning(ICCID_A)) else listOf(callRunning(ICCID_A), callRunning(ICCID_B))
    var first = FakeCallTransport()
    val second = FakeCallTransport()
    val repository = FakeCallHistoryRepository()
    val audio = FakeCallAudioController()
    var recoveries = 0
    var firstSnapshotsToFail = 0
    var failedSnapshots = 0
    val source = CallSourceAggregate(EngineMessageRuntime({ instances }, { if (it == ICCID_A) first else second },
        { 1000 }, { roster }), audio,
        CallControlTransport { owner, method, params, timeout ->
            if (owner == ICCID_A && method == "getCallSnapshot" && firstSnapshotsToFail > 0) {
                firstSnapshotsToFail--; failedSnapshots++
                protocolError(2006, "synthetic snapshot unavailable")
            } else (if (owner == ICCID_A) first else second).request(method, params, timeout)
        },
        context, object : CallHistoryRepository by repository {
            override suspend fun interruptOpen(endedAtMs: Long) { recoveries++; repository.interruptOpen(endedAtMs) }
        }, { if (it == ICCID_A) OWNER_A else OWNER_B }, { "synthetic-outgoing" })

    companion object {
        const val ICCID_A = "8900000000000000001"
        const val ICCID_B = "8900000000000000002"
    }
}
