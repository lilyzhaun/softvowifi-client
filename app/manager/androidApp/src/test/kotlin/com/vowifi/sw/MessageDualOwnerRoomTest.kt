package com.vowifi.sw

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.EngineMessageRuntime
import com.vowifi.sw.manager.data.EngineMessageSource
import com.vowifi.sw.manager.data.EngineMessageTransport
import com.vowifi.sw.manager.data.MessagePersistenceObserver
import com.vowifi.sw.manager.data.MessageRepository
import com.vowifi.sw.manager.data.ObservedIncomingReadRequest
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineProtocolFrame
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.MessageUnreadSummary
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessageDualOwnerRoomTest {
    @Test fun bothLiveSourcesPersistSameEngineIdsIndependentlyAndReopenWithoutCrossReadOrStatus() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val name = "dual-owner-${UUID.randomUUID()}.db"
        val ownerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val ownerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
        val databaseClass = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase").asSubclass(RoomDatabase::class.java)
        fun open(): MessageRepository {
            val database = Room.databaseBuilder(app, databaseClass, name).setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1)).build()
            return Class.forName("com.vowifi.sw.manager.data.AndroidMessageRepository").getConstructor(databaseClass)
                .newInstance(database) as MessageRepository
        }
        val repository = open()
        val wireA = RoomSmsWire()
        val wireB = RoomSmsWire()
        val next = AtomicInteger()
        val fresh = AtomicInteger()
        val source = EngineMessageSource(
            EngineMessageRuntime(
                { listOf(EngineInstanceState("synthetic-a", EngineLifecycle.RUNNING, 1), EngineInstanceState("synthetic-b", EngineLifecycle.RUNNING, 2)) },
                { if (it == "synthetic-a") wireA else wireB }, { 100 }, { listOf("synthetic-a", "synthetic-b") },
            ), Dispatchers.Unconfined, repository, { if (it == "synthetic-a") ownerA else ownerB }, { "app-${next.incrementAndGet()}" },
            MessagePersistenceObserver { _, _, id -> if (id != null) fresh.incrementAndGet() },
        )
        try {
            withTimeout(5_000) { source.historyChoices.first { it.lines.size == 2 && it.lines.all { line -> line.sendable } } }
            source.selectHistoryOwner(ownerA)
            wireA.incoming(); wireB.incoming()
            withTimeout(5_000) { source.unreadSummary.first { it is MessageUnreadSummary.Ready && it.total == 2L } }
            wireA.incoming(); wireB.incoming()
            source.sendFromDisplayedOwner(ownerA, "155501", "synthetic A")
            withTimeout(5_000) { source.state.first { it is MessageUiState.Inbox && it.sendAttempt?.status == OutgoingMessageStatus.ACCEPTED } }
            source.selectHistoryOwner(ownerB)
            withTimeout(5_000) { source.state.first { it is MessageUiState.Inbox && it.ownerId == ownerB && it.composerEnabled } }
            source.sendFromDisplayedOwner(ownerB, "155501", "synthetic B")
            withTimeout(5_000) { source.state.first { it is MessageUiState.Inbox && it.ownerId == ownerB && it.sendAttempt?.status == OutgoingMessageStatus.ACCEPTED } }
            wireB.delivered()
            withTimeout(5_000) { source.state.first { it is MessageUiState.Inbox && it.sendAttempt?.status == OutgoingMessageStatus.DELIVERY_REPORTED } }
            source.selectHistoryOwner(ownerA)
            val incomingA = repository.listByOwner(ownerA).filterIsInstance<MessageRecord.Incoming>().single()
            source.markVisibleRead(requireNotNull(ObservedIncomingReadRequest.create(ownerA, "155501", listOf(incomingA.id))))
            withTimeout(5_000) { source.unreadSummary.first { it is MessageUnreadSummary.Ready && it.total == 1L } }
            assertEquals(2, fresh.get())
            assertEquals(1, wireA.sends)
            assertEquals(1, wireB.sends)
        } finally { source.close(); repository.close() }
        val reopened = open()
        try {
            val recordsA = reopened.listByOwner(ownerA)
            val recordsB = reopened.listByOwner(ownerB)
            assertEquals(2, recordsA.size)
            assertEquals(2, recordsB.size)
            assertTrue(recordsA.filterIsInstance<MessageRecord.Incoming>().single().isRead)
            assertFalse(recordsB.filterIsInstance<MessageRecord.Incoming>().single().isRead)
            assertEquals(OutgoingMessageStatus.ACCEPTED, recordsA.filterIsInstance<MessageRecord.Outgoing>().single().status)
            assertEquals(OutgoingMessageStatus.DELIVERY_REPORTED, recordsB.filterIsInstance<MessageRecord.Outgoing>().single().status)
            assertEquals("same-mo", recordsA.filterIsInstance<MessageRecord.Outgoing>().single().engineSmsId)
            assertEquals("same-mo", recordsB.filterIsInstance<MessageRecord.Outgoing>().single().engineSmsId)
            assertEquals(2, reopened.listConversations().size)
        } finally { reopened.close(); app.deleteDatabase(name) }
    }
}

private class RoomSmsWire : EngineMessageTransport {
    override val events = MutableSharedFlow<EngineProtocolFrame.Event>(extraBufferCapacity = 16)
    var sends = 0
    override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): EngineProtocolFrame.Response {
        val result = if (method == "getStatus") buildJsonObject {
            put("tunnel", "CONNECTED"); put("reconnects", 0); put("ims", "REGISTERED")
            put("call", "IDLE"); put("transport", "UDP"); put("pcscf", JsonArray(emptyList()))
        } else {
            check(method == "sendSms"); sends++
            buildJsonObject { put("smsId", "same-mo"); put("statusCode", 202); put("reference", 1) }
        }
        return EngineProtocolFrame.Response(1, result, null)
    }
    suspend fun incoming() = events.emit(EngineProtocolFrame.Event("smsReceived", buildJsonObject {
        put("smsId", "same-mt"); put("number", "155501"); put("text", "synthetic"); put("timestamp", 1)
    }))
    suspend fun delivered() = events.emit(EngineProtocolFrame.Event("smsSent", buildJsonObject {
        put("smsId", "same-mo"); put("status", "delivered")
    }))
}
