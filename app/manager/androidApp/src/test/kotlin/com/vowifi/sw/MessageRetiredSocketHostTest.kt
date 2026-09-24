package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidCallEventTransport
import com.vowifi.sw.manager.data.EngineMessageRuntime
import com.vowifi.sw.manager.data.EngineMessageSource
import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessageRetiredSocketHostTest {
    @Test fun currentConnectionWritesOnceAndPersistsAcceptedResponse() = runBlocking {
        SmsRoomBoundaryFixture().use { room ->
            SmsSocketBoundaryFixture().use { wire ->
                val repository = room.open()
                val source = source(room, wire, repository)
                try {
                    source.awaitReady()
                    source.sendSms("155501", "synthetic accepted")
                    withTimeout(5000) { source.state.first {
                        it is MessageUiState.Inbox && it.sendAttempt?.status == OutgoingMessageStatus.ACCEPTED
                    } }
                    assertEquals(1, wire.sockets.size)
                    assertEquals(1, wire.writes.count { it.second == "sendSms" })
                    assertEquals(OutgoingMessageStatus.ACCEPTED, (repository.listByOwner(room.ownerA).single() as MessageRecord.Outgoing).status)
                } finally { source.close(); repository.close() }
            }
        }
    }

    @Test fun retiredTransportRequestAfterEofDoesNotConnectOrWrite() = runBlocking {
        SmsSocketBoundaryFixture().use { wire ->
            val transport = wire.transport()
            transport.connect()
            val disconnected = async(start = CoroutineStart.UNDISPATCHED) { transport.messageDisconnects.first() }
            wire.sockets.single().endInput()
            withTimeout(5000) { disconnected.await() }
            val failure = runCatching { transport.request("sendSms", buildJsonObject {
                put("number", "155501"); put("text", "synthetic retired")
            }) }.exceptionOrNull()
            assertTrue(failure is java.io.EOFException)
            assertEquals(1, wire.sockets.size)
            assertEquals(0, wire.writes.count { it.second == "sendSms" })
        }
    }

    @Test fun explicitlyRetiredTransportCannotWriteEvenIfOriginalSocketRemainsConnected() = runBlocking {
        SmsSocketBoundaryFixture().use { wire ->
            val transport = wire.transport()
            transport.connect()
            val gate = SmsWriterGate(wire.writeLock)
            val request = async(Dispatchers.Default) {
                runCatching { transport.request("sendSms", buildJsonObject {
                    put("number", "155501"); put("text", "synthetic retired")
                }) }.exceptionOrNull()
            }
            try {
                wire.awaitBlockedWriters(1)
                transport.retireMessages()
            } finally { gate.close() }
            assertTrue(withTimeout(5000) { request.await() } is java.io.EOFException)
            assertEquals(1, wire.sockets.size)
            assertEquals(0, wire.writes.count { it.second == "sendSms" })
        }
    }

    @Test fun oldQueuedSendBlockedAtProductionWriterCannotFirstWriteOnReplacementSocket() = runBlocking {
        SmsRoomBoundaryFixture().use { room ->
            SmsSocketBoundaryFixture().use { wire ->
                val repository = room.open()
                val source = source(room, wire, repository)
                var gate: SmsWriterGate? = null
                try {
                    source.awaitReady()
                    val oldSocket = withTimeout(5000) { wire.created.receive() }
                    val scope = source.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(source) as CoroutineScope
                    val before = scope.coroutineContext[Job]!!.children.toSet()
                    gate = SmsWriterGate(wire.writeLock)
                    source.sendSms("155501", "synthetic queued")
                    wire.awaitBlockedWriters(1)
                    val submitted = scope.coroutineContext[Job]!!.children.filterNot { it in before }.toList()
                    assertTrue(submitted.isNotEmpty())
                    assertEquals(0, wire.writes.count { it.second == "sendSms" })
                    oldSocket.endInput()
                    source.awaitUnknown()
                    source.refresh()
                    val replacement = withTimeout(5000) { wire.created.receive() }
                    wire.awaitBlockedWriters(2)
                    gate.close(); gate = null
                    withTimeout(5000) { submitted.forEach { it.join() } }
                    source.awaitReady()
                    assertEquals("retired send must not reach the replacement wire", 0,
                        wire.writes.count { it.first === replacement && it.second == "sendSms" })
                    assertEquals(0, wire.writes.count { it.second == "sendSms" })
                    assertEquals(OutgoingMessageStatus.UNKNOWN, (repository.listByOwner(room.ownerA).single() as MessageRecord.Outgoing).status)
                } finally { gate?.close(); source.close(); repository.close() }
            }
        }
    }

    @Test fun alreadyWrittenSendRemainsAmbiguousOnEofAndIsNeverResent() = runBlocking {
        SmsRoomBoundaryFixture().use { room ->
            SmsSocketBoundaryFixture().use { wire ->
                wire.deferSms = true
                val repository = room.open()
                val source = source(room, wire, repository)
                try {
                    source.awaitReady()
                    val old = withTimeout(5000) { wire.created.receive() }
                    source.sendSms("155501", "synthetic written")
                    withTimeout(5000) { wire.sent.receive() }
                    old.endInput(); source.awaitUnknown()
                    source.refresh(); source.awaitReady()
                    assertEquals(1, wire.writes.count { it.second == "sendSms" })
                    assertEquals(0, wire.writes.count { it.first !== old && it.second == "sendSms" })
                    assertEquals(OutgoingMessageStatus.UNKNOWN, (repository.listByOwner(room.ownerA).single() as MessageRecord.Outgoing).status)
                } finally { source.close(); repository.close() }
            }
        }
    }

    @Test fun generalRequestStillAutomaticallyReconnectsAfterEof() = runBlocking {
        SmsSocketBoundaryFixture().use { wire ->
            val general = AndroidCallEventTransport("synthetic", wire::socket)
            try {
                general.request("getStatus")
                val disconnected = async(start = CoroutineStart.UNDISPATCHED) { general.disconnected.first() }
                wire.sockets.single().endInput()
                withTimeout(5000) { disconnected.await() }
                general.request("getStatus")
                assertEquals(2, wire.sockets.size)
                assertEquals(2, wire.writes.count { it.second == "hello" })
                assertEquals(2, wire.writes.count { it.second == "getStatus" })
            } finally { general.close() }
        }
    }

    private fun source(room: SmsRoomBoundaryFixture, wire: SmsSocketBoundaryFixture, repository: com.vowifi.sw.manager.data.MessageRepository) =
        EngineMessageSource(EngineMessageRuntime({ listOf(EngineInstanceState("a", EngineLifecycle.RUNNING, 1)) },
            { wire.transport() }, { 10 }, { listOf("a") }), Dispatchers.Default, repository, { room.ownerA }, { "queued" })

    private suspend fun EngineMessageSource.awaitReady() = withTimeout(5000) {
        state.first { it is MessageUiState.Inbox && it.composerEnabled }
    }
    private suspend fun EngineMessageSource.awaitUnknown() = withTimeout(5000) {
        state.first { it is MessageUiState.Inbox && it.sendAttempt?.status == OutgoingMessageStatus.UNKNOWN }
    }
}
