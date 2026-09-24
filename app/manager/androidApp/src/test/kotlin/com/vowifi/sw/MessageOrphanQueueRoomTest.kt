package com.vowifi.sw

import com.vowifi.sw.manager.data.EngineMessageRuntime
import com.vowifi.sw.manager.data.EngineMessageSource
import com.vowifi.sw.manager.data.EngineMessageTransport
import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineProtocolFrame
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessageOrphanQueueRoomTest {
    @Test fun bothPendingOwnersWithoutLastOwnerBecomeUnknownOnOfflineSourceReopen() = runBlocking {
        SmsRoomBoundaryFixture().use { room ->
            val wireA = PendingRoomSmsWire()
            val wireB = PendingRoomSmsWire()
            val ids = AtomicInteger()
            val originalStore = room.open()
            val original = EngineMessageSource(
                EngineMessageRuntime({ listOf(EngineInstanceState("a", EngineLifecycle.RUNNING, 1), EngineInstanceState("b", EngineLifecycle.RUNNING, 2)) },
                    { if (it == "a") wireA else wireB }, { 10 }, { listOf("a", "b") }),
                Dispatchers.Unconfined, originalStore, { if (it == "a") room.ownerA else room.ownerB }, { "pending-${ids.incrementAndGet()}" },
            )
            try {
                withTimeout(5000) { original.historyChoices.first { it.lines.size == 2 && it.lines.all { line -> line.sendable } } }
                original.selectHistoryOwner(room.ownerA)
                original.sendSms("155501", "synthetic A")
                withTimeout(5000) { wireA.entered.await() }
                original.selectHistoryOwner(room.ownerB)
                original.sendSms("155501", "synthetic B")
                withTimeout(5000) { wireB.entered.await() }
                assertNull(originalStore.lastOwner())
                assertEquals(OutgoingMessageStatus.QUEUED, (originalStore.listByOwner(room.ownerA).single() as MessageRecord.Outgoing).status)
                assertEquals(OutgoingMessageStatus.QUEUED, (originalStore.listByOwner(room.ownerB).single() as MessageRecord.Outgoing).status)
            } finally { original.close(); originalStore.close() }
            val reopenedStore = room.open()
            val offlineListed = CompletableDeferred<Unit>()
            val reopened = EngineMessageSource(
                EngineMessageRuntime({ offlineListed.complete(Unit); emptyList() }, { error("offline reopen must never attach") }, { 20 }, { emptyList() }),
                Dispatchers.Unconfined, reopenedStore, { null }, { "must-not-send" },
            )
            try {
                withTimeout(5000) { offlineListed.await() }
                withTimeout(5000) { reopened.state.first { it is MessageUiState.Inbox && !it.page.loading } }
                assertEquals(OutgoingMessageStatus.UNKNOWN, (reopenedStore.listByOwner(room.ownerA).single() as MessageRecord.Outgoing).status)
                assertEquals(OutgoingMessageStatus.UNKNOWN, (reopenedStore.listByOwner(room.ownerB).single() as MessageRecord.Outgoing).status)
                assertEquals(1, wireA.sends.get()); assertEquals(1, wireB.sends.get())
            } finally { reopened.close(); reopenedStore.close() }
        }
    }
}

private class PendingRoomSmsWire : EngineMessageTransport {
    override val events = MutableSharedFlow<EngineProtocolFrame.Event>()
    val entered = CompletableDeferred<Unit>()
    val sends = AtomicInteger()
    private val response = CompletableDeferred<EngineProtocolFrame.Response>()
    override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): EngineProtocolFrame.Response {
        if (method == "getStatus") return EngineProtocolFrame.Response(1, buildJsonObject {
            put("tunnel", "CONNECTED"); put("reconnects", 0); put("ims", "REGISTERED")
            put("call", "IDLE"); put("transport", "UDP"); put("pcscf", JsonArray(emptyList()))
        }, null)
        check(method == "sendSms")
        sends.incrementAndGet(); entered.complete(Unit)
        return response.await()
    }
}
