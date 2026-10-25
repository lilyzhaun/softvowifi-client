package com.vowifi.sw

import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CallControlSocketHostTest {
    @Test
    fun blockedDialCanCancelOnIndependentSocketWithoutClosingEventReader() = runBlocking {
        val server = CallSocketTestServer()
        val primary = AndroidCallEventTransport("synthetic-owner", server::socket)
        val source = EngineCallSource.connect(
            EngineMessageRuntime({ listOf(EngineInstanceState("synthetic-owner", EngineLifecycle.RUNNING, 1)) }, { primary }, { 0 }),
            silentAudio(), AndroidCallControlTransport(server::socket), Dispatchers.Default,
        )
        try {
            withTimeout(8000) { source.state.first { it is CallUiState.Ready && it.sync == CallSyncState.Ready } }
            source.dial("+12025550123")
            withTimeout(5000) { while (!server.dialPending) delay(10) }
            source.cancel()
            withTimeout(5000) { while (server.cancels.get() == 0) delay(10) }
            assertEquals(CallPhase.Canceling, (source.state.value as CallUiState.Ready).phase)
            assertFalse(server.primaryClosed)
            assertTrue(server.connections.size >= 3)
            // Make the late snapshot/Idle interleaving deterministic: Idle is not RPC quiescence.
            server.holdSnapshot = true
            server.finishCanceledReply()
            withTimeout(5000) { while (server.heldSnapshot == null) delay(10) }
            val snapshotSocket = checkNotNull(server.heldSnapshot).first
            assertNotSame(server.primary, snapshotSocket)
            server.sendCanceledEvent()
            withTimeout(5000) { source.state.first { it is CallUiState.Ready && it.phase == CallPhase.Idle } }
            assertEquals(1, server.cancels.get())
            assertEquals(1, server.dials.get())
            assertFalse(server.primaryClosed)
            assertFalse("Idle不能把仍在途的权威快照冒充已完成", snapshotSocket.closed)
            withTimeout(5000) { while (server.connections.filter { "cancelCall" in it.methods }.any { !it.closed }) delay(10) }
            assertEquals("初始及取消后快照都必须真实执行", 2, server.connections.count { "getCallSnapshot" in it.methods })
            server.releaseSnapshot()
            withTimeout(5000) { while (server.connections.filterNot { it === server.primary }.any { !it.closed }) delay(10) }
            assertTrue("独立连接状态：${server.connections.map { "primary=${it === server.primary},closed=${it.closed},methods=${it.methods}" }}",
                server.connections.filterNot { it === server.primary }.all { it.closed })
            val stillReadable = primary.request("getCallSnapshot", buildJsonObject {}, 2000)
            assertEquals("IDLE", stillReadable.result!!.getValue("stage").jsonPrimitive.content)
        } finally {
            println("CALL_CONTROL_SOCKETS|" + server.connections.joinToString { "primary=${it === server.primary},closed=${it.closed},methods=${it.methods}" })
            source.close(); server.close()
        }
        assertTrue(server.primaryClosed)
    }

    @Test
    fun primaryEofKeepsActualSessionUntilExplicitSnapshotRecovery() = runBlocking {
        val server = CallSocketTestServer().apply { stage = "IN_CALL"; revision = 2 }
        val primary = AndroidCallEventTransport("synthetic-owner", server::socket)
        val source = EngineCallSource.connect(
            EngineMessageRuntime({ listOf(EngineInstanceState("synthetic-owner", EngineLifecycle.RUNNING, 1)) }, { primary }, { 0 }),
            silentAudio(), AndroidCallControlTransport(server::socket), Dispatchers.Default,
        )
        try {
            withTimeout(8000) { source.state.first { it is CallUiState.Ready && it.phase == CallPhase.InCall } }
            server.connections.first().endInput()
            val lost = withTimeout(5000) { source.state.first { it is CallUiState.Ready && it.sync == CallSyncState.Recovering } } as CallUiState.Ready
            assertEquals(CallPhase.InCall, lost.phase)
            assertTrue(lost.audio is CallAudioState.Failed)
            assertFalse(lost.dtmfSupported)
            server.stage = "IDLE"; server.revision = 3
            source.refresh()
            val restored = withTimeout(5000) { source.state.first { it is CallUiState.Ready && it.sync == CallSyncState.Ready } } as CallUiState.Ready
            assertEquals(CallPhase.Idle, restored.phase)
            assertNull(restored.clock)
        } finally { source.close(); server.close() }
    }

    private fun silentAudio() = object : CallAudioController {
        override val events = MutableSharedFlow<CallAudioEvent>()
        override suspend fun start(session: CallAudioSession) = Unit
        override fun stop() = Unit
    }

    @Test
    fun blockedDtmfDoesNotQueueHangupAndOwnedSocketsCloseOnSourceShutdown() = runBlocking {
        val server = CallSocketTestServer().apply { stage = "IN_CALL"; revision = 2 }
        val primary = AndroidCallEventTransport("synthetic-owner", server::socket)
        val source = EngineCallSource.connect(
            EngineMessageRuntime({ listOf(EngineInstanceState("synthetic-owner", EngineLifecycle.RUNNING, 1)) }, { primary }, { 0 }),
            silentAudio(), AndroidCallControlTransport(server::socket), Dispatchers.Default,
        )
        try {
            val active = withTimeout(8000) { source.state.first { it is CallUiState.Ready && it.dtmfSupported } } as CallUiState.Ready
            source.sendDtmf(active.binding!!, '5')
            withTimeout(5000) { while (server.digits.get() == 0) delay(10) }
            source.act(active.binding!!, CallAction.Hangup)
            withTimeout(5000) { source.state.first { it is CallUiState.Ready && it.phase == CallPhase.Idle } }
            assertEquals(1, server.hangups.get())
            assertEquals(1, server.digits.get())
            assertFalse(server.connections.first().closed)
            source.close()
            withTimeout(5000) { while (server.connections.any { !it.closed }) delay(10) }
        } finally { source.close(); server.close() }
    }
}
