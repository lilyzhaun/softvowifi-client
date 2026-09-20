@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class EngineCallSnapshotTest {
    @Test
    fun subscribesThenRestoresCurrentEngineCallWithoutHistory() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport(responses = mapOf("getCallSnapshot" to liveSnapshot()))
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        try {
            assertEquals(CallPhase.InCall, assertIs<CallUiState.Ready>(source.state.value).phase)
            assertEquals("getCallSnapshot", transport.snapshotRequests.first().method)
            assertTrue(transport.connectCalls > 0)
        } finally { source.close() }
    }

    @Test
    fun refreshQueriesEngineInsteadOfAssumingCachedIdle() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        try {
            transport.enqueueSnapshot(liveSnapshot())
            source.refresh()
            assertEquals(CallPhase.InCall, assertIs<CallUiState.Ready>(source.state.value).phase)
        } finally { source.close() }
    }
}

internal fun liveSnapshot() = EngineProtocolFrame.Response(1, buildJsonObject {
    put("epoch", "synthetic-epoch")
    put("revision", 4)
    put("stage", "IN_CALL")
    put("callId", CALL_TOKEN)
    put("direction", "OUTGOING")
    put("peer", "+12025550123")
    put("confirmedElapsedMs", 12000)
    put("dtmfSupported", true)
}, null)
