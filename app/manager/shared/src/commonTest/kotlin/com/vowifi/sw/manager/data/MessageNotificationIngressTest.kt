@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineProtocolFrame
import com.vowifi.sw.manager.model.MessageUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MessageNotificationIngressTest {
    @Test fun processWatcherAttachesWhenEngineStartsWithoutActivityAndStopsOnClose() = runTest {
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        var instances = emptyList<EngineInstanceState>()
        var lists = 0
        val alerts = mutableListOf<String>()
        val source = EngineMessageSource(
            EngineMessageRuntime({ lists++; instances }, { ImmediateMessageTransport() }, { 1000 }),
            coroutineContext, FakeMessageRepository(), { owner }, { "synthetic" },
            MessagePersistenceObserver { _, _, fresh -> if (fresh != null) alerts += fresh },
        )
        source.watchAttachment()
        runCurrent()
        instances = listOf(EngineInstanceState("8986001", EngineLifecycle.RUNNING, pid = 7))
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(listOf("synthetic"), alerts)
        source.close()
        val beforeClose = lists
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(beforeClose, lists)
    }

    @Test fun connectTimeMtIsPersistedThenNotifiedWithoutActivityOrCollector() = runTest(UnconfinedTestDispatcher()) {
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val repository = FakeMessageRepository()
        val transport = ImmediateMessageTransport()
        val alerts = mutableListOf<Boolean>()
        val policy = MessageNotificationPolicy({ _, _, alert -> alerts += alert }, {})
        val observer = MessagePersistenceObserver { identity, rows, fresh -> policy.snapshot(identity, rows, fresh) }
        var next = 0
        fun source() = EngineMessageSource(
            EngineMessageRuntime({ listOf(EngineInstanceState("8986001", EngineLifecycle.RUNNING, pid = 7)) }, { transport }, { 1000 }),
            coroutineContext, repository, { owner }, { "message-${++next}" }, observer,
        )
        val first = source()
        assertEquals(listOf(true), alerts)
        val ready = assertIs<MessageUiState.Inbox>(first.state.value)
        assertEquals(owner, ready.ownerId)
        first.close()
        val second = source()
        assertEquals(listOf(true), alerts)
        assertEquals(1, repository.listByOwner(owner).size)
        assertFalse(repository.closed)
        second.close()
    }

    @Test fun rapidMtAndObservedReadKeepUnseenArrivalUnread() = runTest(UnconfinedTestDispatcher()) {
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val repository = FakeMessageRepository()
        val transport = FakeEngineMessageTransport()
        val alerts = mutableListOf<Pair<Int, Boolean>>()
        val policy = MessageNotificationPolicy({ _, count, alert -> alerts += count to alert }, {})
        var next = 0
        val source = EngineMessageSource(
            EngineMessageRuntime({ listOf(EngineInstanceState("8986001", EngineLifecycle.RUNNING, pid = 7)) }, { transport }, { 1000 }),
            coroutineContext, repository, { owner }, { "message-${++next}" },
            MessagePersistenceObserver { identity, rows, fresh -> policy.snapshot(identity, rows, fresh) },
        )
        transport.emitReceived("one", "+15550000001", "synthetic", 1)
        transport.emitReceived("two", "+15550000001", "synthetic", 1)
        transport.emitReceived("two", "+15550000001", "synthetic", 1)
        source.markVisibleRead(requireNotNull(ObservedIncomingReadRequest.create(owner, "15550000001", listOf("message-1"))))
        transport.emitReceived("one", "+15550000001", "synthetic", 1)
        transport.emitSent("two", com.vowifi.sw.manager.model.SmsEngineStatus.DELIVERED)
        assertEquals(listOf(1 to true, 2 to true, 1 to false), alerts)
        source.close()
    }

    @Test fun failedInsertNeverPostsNotification() = runTest(UnconfinedTestDispatcher()) {
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val repository = object : MessageRepository by FakeMessageRepository() {
            override suspend fun insertIncomingIfAbsent(
                ownerId: SimOwnerId, messageId: String, engineSmsId: String, peerAddress: String,
                body: String, messageAtMs: Long, nowMs: Long,
            ): IncomingInsertResult = throw MessageStorageException()
        }
        val transport = ImmediateMessageTransport()
        val alerts = mutableListOf<Boolean>()
        val policy = MessageNotificationPolicy({ _, _, alert -> alerts += alert }, {})
        val source = EngineMessageSource(
            EngineMessageRuntime({ listOf(EngineInstanceState("8986001", EngineLifecycle.RUNNING, pid = 7)) }, { transport }, { 1000 }),
            coroutineContext, repository, { owner }, { "synthetic" },
            MessagePersistenceObserver { identity, rows, fresh -> policy.snapshot(identity, rows, fresh) },
        )
        assertEquals(emptyList(), alerts)
        assertFalse(assertIs<MessageUiState.Inbox>(source.state.value).composerEnabled)
        source.close()
    }
}

private class ImmediateMessageTransport : EngineMessageTransport {
    override val events = MutableSharedFlow<EngineProtocolFrame.Event>(extraBufferCapacity = 16)
    override suspend fun connect() {
        events.emit(EngineProtocolFrame.Event("smsReceived", buildJsonObject {
            put("smsId", "connect-mt")
            put("number", "+15550000001")
            put("text", "synthetic")
            put("timestamp", 1)
        }))
    }
    override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): EngineProtocolFrame.Response =
        error("No outgoing request expected")
}
