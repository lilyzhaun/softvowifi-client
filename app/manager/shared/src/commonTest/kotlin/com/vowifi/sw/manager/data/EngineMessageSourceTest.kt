@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineProtocolError
import com.vowifi.sw.manager.model.EngineProtocolFrame
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.model.SmsEngineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class EngineMessageSourceTest {
    @Test
    fun zeroRunningInstancesStayUnavailable() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeEngineMessageTransport()
        val source = sourceOf(listOf(stopped(ICCID_A)), transport)

        assertIs<MessageUiState.Unavailable>(source.state.value)
        source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        assertTrue(transport.requests.isEmpty())
        assertIs<MessageUiState.Unavailable>(source.state.value)
        source.close()
    }

    @Test
    fun multipleRunningInstancesRequireExplicitOwnerBeforeSending() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeEngineMessageTransport()
        val source = sourceOf(listOf(running(ICCID_A), running(ICCID_B)), transport)

        val unbound = assertIs<MessageUiState.Unavailable>(source.state.value)
        assertEquals(MESSAGE_SELECT_OWNER, unbound.message)
        assertEquals(2, source.historyChoices.value.lines.size)
        source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        assertTrue(transport.requests.isEmpty())
        source.close()
    }

    @Test
    fun sendSmsEncodesParamsAndBindsQueuedResult() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66))
        val source = sourceOf(listOf(running(ICCID_A)), transport)

        val idle = inspectInboxThreads(source)
        assertEquals(MessageOrigin.ENGINE, idle.origin)
        assertEquals(emptyList(), idle.records)
        assertFalse(idle.sendBusy)
        assertTrue(idle.composerEnabled)
        source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)

        val request = transport.requests.single()
        assertEquals("sendSms", request.method)
        assertEquals(SYNTH_NUMBER, request.params.getValue("number").jsonPrimitive.content)
        assertEquals(SYNTH_TEXT, request.params.getValue("text").jsonPrimitive.content)
        assertEquals(5_000L, request.timeoutMillis)

        val ready = inspectInboxThreads(source)
        assertEquals(MessageOrigin.ENGINE, ready.origin)
        assertFalse(ready.sendBusy)
        val outgoing = assertIs<MessageRecord.Outgoing>(ready.records.single())
        assertEquals("msg-1", outgoing.id)
        assertEquals(MO_ID, outgoing.engineSmsId)
        assertEquals(SYNTH_NUMBER, outgoing.peerLabel)
        assertEquals(SYNTH_TEXT, outgoing.preview)
        assertEquals(OutgoingMessageStatus.ACCEPTED, outgoing.status)
        assertEquals(null, outgoing.engineStatus)
        assertFalse(outgoing.isLocalMtReceipt)
        source.close()
    }

    @Test
    fun singleRunningEnginePublishesReadyEmptySession() = runTest(UnconfinedTestDispatcher()) {
        val source = sourceOf(listOf(running(ICCID_A)), FakeEngineMessageTransport())

        val ready = inspectInboxThreads(source)
        assertEquals(MessageOrigin.ENGINE, ready.origin)
        assertEquals(emptyList(), ready.records)
        assertFalse(ready.sendBusy)
        source.close()
    }

    @Test
    fun duplicateSendWhileBusyIsIgnored() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66), requestGate = gate)
        val source = sourceOf(listOf(running(ICCID_A)), transport)

        source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        val busy = inspectInboxThreads(source)
        assertTrue(busy.sendBusy)
        assertEquals(1, transport.requests.size)
        assertEquals(1, busy.records.size)

        source.sendSms(SYNTH_PEER, "synth-two")
        val stillBusy = inspectInboxThreads(source)
        assertTrue(stillBusy.sendBusy)
        assertEquals(1, transport.requests.size)
        assertEquals(1, stillBusy.records.size)

        gate.complete(Unit)
        val ready = inspectInboxThreads(source)
        assertFalse(ready.sendBusy)
        val outgoing = assertIs<MessageRecord.Outgoing>(ready.records.single())
        assertEquals("msg-1", outgoing.id)
        assertEquals(MO_ID, outgoing.engineSmsId)
        assertEquals(OutgoingMessageStatus.ACCEPTED, outgoing.status)
        source.close()
    }

    @Test
    fun acceptedEventBeforeResponseIsReplayedAfterBind() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66), requestGate = gate)
        val source = sourceOf(listOf(running(ICCID_A)), transport)

        source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        transport.emitSent("mo-unknown", SmsEngineStatus.DELIVERED)
        transport.emitSent(MO_ID, SmsEngineStatus.ACCEPTED)
        val beforeBind = assertIs<MessageRecord.Outgoing>(
            inspectInboxThreads(source).records.single(),
        )
        assertEquals(OutgoingMessageStatus.QUEUED, beforeBind.status)
        assertEquals(null, beforeBind.engineStatus)
        assertEquals(null, beforeBind.engineSmsId)
        assertFalse(beforeBind.id == MO_ID)

        gate.complete(Unit)
        val ready = inspectInboxThreads(source)
        assertFalse(ready.sendBusy)
        val outgoing = assertIs<MessageRecord.Outgoing>(ready.records.single())
        assertEquals("msg-1", outgoing.id)
        assertEquals(MO_ID, outgoing.engineSmsId)
        assertEquals(OutgoingMessageStatus.ACCEPTED, outgoing.status)
        assertEquals(SmsEngineStatus.ACCEPTED, outgoing.engineStatus)
        assertFalse(outgoing.isLocalMtReceipt)
        source.close()
    }

    @Test
    fun protocolErrorClearsSendBusyAndKeepsFailedRecord() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeEngineMessageTransport(sendResult = sendError(4001, "sms failed"), requestGate = gate)
        val source = sourceOf(listOf(running(ICCID_A)), transport)

        source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        assertTrue(assertIs<MessageUiState.Inbox>(source.state.value).sendBusy)

        gate.complete(Unit)
        val ready = inspectInboxThreads(source)
        assertFalse(ready.sendBusy)
        val failed = assertIs<MessageRecord.Outgoing>(ready.records.single())
        assertEquals(OutgoingMessageStatus.FAILED, failed.status)
        assertEquals(SmsEngineStatus.FAILED, failed.engineStatus)
        assertFalse(failed.isLocalMtReceipt)
        source.close()
    }

    @Test
    fun smsSentStatusesUpdateMatchingRecordWithoutCreatingIncoming() = runTest(UnconfinedTestDispatcher()) {
        val cases = listOf(
            SmsEngineStatus.ACCEPTED to OutgoingMessageStatus.ACCEPTED,
            SmsEngineStatus.PENDING to OutgoingMessageStatus.ACCEPTED,
            SmsEngineStatus.DELIVERED to OutgoingMessageStatus.DELIVERY_REPORTED,
            SmsEngineStatus.FORWARDED to OutgoingMessageStatus.DELIVERY_REPORTED,
            SmsEngineStatus.REPLACED to OutgoingMessageStatus.DELIVERY_REPORTED,
            SmsEngineStatus.FAILED to OutgoingMessageStatus.FAILED,
        )

        cases.forEach { (engineStatus, uiStatus) ->
            val gate = CompletableDeferred<Unit>()
            val transport = FakeEngineMessageTransport(
                sendResult = sendOk(MO_ID, 202, 66),
                requestGate = gate,
            )
            val source = sourceOf(listOf(running(ICCID_A)), transport)
            source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
            transport.emitSent(MO_ID, engineStatus)
            gate.complete(Unit)

            val ready = inspectInboxThreads(source)
            val outgoing = assertIs<MessageRecord.Outgoing>(ready.records.single())
            assertEquals(uiStatus, outgoing.status)
            assertEquals(engineStatus, outgoing.engineStatus)
            assertFalse(outgoing.isLocalMtReceipt)
            assertTrue(ready.records.none { it is MessageRecord.Incoming })
            source.close()
        }
    }

    @Test
    fun unknownSmsIdDoesNotChangeCurrentSession() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66))
        val source = sourceOf(listOf(running(ICCID_A)), transport)
        source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        val before = source.state.value

        transport.emitSent("mo-unknown", SmsEngineStatus.DELIVERED)

        assertEquals(before, source.state.value)
        source.close()
    }

    @Test
    fun pendingThenFinalStatusKeepsSameRecord() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeEngineMessageTransport(
            sendResult = sendOk(MO_ID, 202, 66),
            requestGate = gate,
        )
        val source = sourceOf(listOf(running(ICCID_A)), transport)
        source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)

        transport.emitSent(MO_ID, SmsEngineStatus.PENDING)
        transport.emitSent(MO_ID, SmsEngineStatus.DELIVERED)
        gate.complete(Unit)
        val ready = inspectInboxThreads(source)
        val delivered = assertIs<MessageRecord.Outgoing>(ready.records.single())
        assertEquals("msg-1", delivered.id)
        assertEquals(MO_ID, delivered.engineSmsId)
        assertEquals(OutgoingMessageStatus.DELIVERY_REPORTED, delivered.status)
        assertEquals(SmsEngineStatus.DELIVERED, delivered.engineStatus)
        assertFalse(delivered.isLocalMtReceipt)
        source.close()
    }

    @Test
    fun smsReceivedIsLocalMtReceiptAndDeliveryReportIsNot() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeEngineMessageTransport(
            sendResult = sendOk(MO_ID, 202, 66),
            requestGate = gate,
        )
        val source = sourceOf(listOf(running(ICCID_A)), transport)
        source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        transport.emitSent(MO_ID, SmsEngineStatus.DELIVERED)
        transport.emitReceived(MT_ID, SYNTH_NUMBER, SYNTH_TEXT, 1_755_000_000L)
        gate.complete(Unit)

        val ready = inspectInboxThreads(source)
        assertEquals(2, ready.records.size)
        val outgoing = assertIs<MessageRecord.Outgoing>(ready.records[0])
        val incoming = assertIs<MessageRecord.Incoming>(ready.records[1])
        assertFalse(outgoing.isLocalMtReceipt)
        assertEquals(OutgoingMessageStatus.DELIVERY_REPORTED, outgoing.status)
        assertTrue(incoming.isLocalMtReceipt)
        assertEquals("msg-2", incoming.id)
        assertEquals(MT_ID, incoming.engineSmsId)
        assertEquals(1_755_000_000_000L, incoming.timestampMs)
        source.close()
    }

    @Test
    fun eventsAccumulateOnCurrentSession() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeEngineMessageTransport(
            sendResults = ArrayDeque(listOf(sendOk("mo-a", 202, 1), sendOk("mo-b", 202, 2))),
            requestGate = gate,
        )
        val source = sourceOf(listOf(running(ICCID_A)), transport)
        transport.emitReceived("mt-a", SYNTH_NUMBER, "synth-in-a", 1_755_000_001L)
        source.sendSms(SYNTH_NUMBER, "synth-out-a")
        transport.emitSent("mo-a", SmsEngineStatus.ACCEPTED)
        gate.complete(Unit)
        source.sendSms(SYNTH_PEER, "synth-out-b")
        transport.emitReceived("mt-b", SYNTH_PEER, "synth-in-b", 1_755_000_002L)

        val ready = inspectInboxThreads(source)
        assertEquals(listOf("msg-2", "msg-3", "msg-1", "msg-4"), ready.records.map { it.id })
        assertEquals(listOf("mo-a", "mo-b", "mt-a", "mt-b"), ready.records.map { it.engineSmsId })
        assertEquals("mo-a", ready.records[0].engineSmsId)
        assertEquals("mt-b", ready.records[3].engineSmsId)
        source.close()
    }

    @Test
    fun sendErrorPreservesExistingRecords() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeEngineMessageTransport(
            sendResults = ArrayDeque(
                listOf(
                    sendOk(MO_ID, 202, 66),
                    sendError(4001, "sms failed"),
                ),
            ),
        )
        val source = sourceOf(listOf(running(ICCID_A)), transport)
        transport.emitReceived(MT_ID, SYNTH_NUMBER, SYNTH_TEXT, 1_755_000_000L)
        source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        source.sendSms(SYNTH_PEER, "synth-two")

        val ready = inspectInboxThreads(source)
        assertEquals(3, ready.records.size)
        assertEquals(listOf("msg-2", "msg-3", "msg-1"), ready.records.map { it.id })
        val accepted = assertIs<MessageRecord.Outgoing>(ready.records[0])
        assertEquals(MO_ID, accepted.engineSmsId)
        val failed = assertIs<MessageRecord.Outgoing>(ready.records[1])
        assertEquals(OutgoingMessageStatus.FAILED, failed.status)
        assertEquals(SmsEngineStatus.FAILED, failed.engineStatus)
        assertFalse(failed.isLocalMtReceipt)
        val incoming = assertIs<MessageRecord.Incoming>(ready.records[2])
        assertEquals(MT_ID, incoming.engineSmsId)
        source.close()
    }

    @Test
    fun closeCancelsFurtherUpdatesAndSends() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66))
        val source = sourceOf(listOf(running(ICCID_A)), transport)
        source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        val before = source.state.value

        source.close()
        transport.emitReceived(MT_ID, SYNTH_NUMBER, SYNTH_TEXT, 1_755_000_000L)
        source.sendSms(SYNTH_PEER, "synth-late")
        source.refresh()

        assertEquals(before, source.state.value)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun refreshReselectsSingleRunningInstance() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeEngineMessageTransport()
        var instances = listOf(stopped(ICCID_A), running(ICCID_B))
        val source = EngineMessageSource(
            runtime = EngineMessageRuntime(
                listInstances = { instances },
                transportFor = { transport },
                clock = { NOW_MS },
                activeIccids = { instances.filter { it.lifecycle == EngineLifecycle.RUNNING }.map { it.iccid } },
            ),
            parentContext = coroutineContext,
            repository = FakeMessageRepository(),
            ownerIdFor = ::ownerFor,
            nextMessageId = sequentialIds(),
        )
        val ready = inspectInboxThreads(source)
        assertEquals(MessageOrigin.ENGINE, ready.origin)
        assertEquals(emptyList(), ready.records)
        assertFalse(ready.sendBusy)

        instances = listOf(stopped(ICCID_A), stopped(ICCID_B))
        source.refresh()
        val idle = inspectInboxThreads(source)
        assertFalse(idle.composerEnabled)
        assertEquals(emptyList(), idle.records)
        assertEquals(ActiveEngineSelector.NONE_MESSAGE, idle.attachmentNotice)

        instances = listOf(running(ICCID_A), running(ICCID_B))
        source.refresh()
        val multiple = assertIs<MessageUiState.Unavailable>(source.state.value)
        assertEquals(MESSAGE_SELECT_OWNER, multiple.message)
        assertEquals(2, source.historyChoices.value.lines.size)
        source.close()
    }

    private suspend fun sourceOf(
        instances: List<EngineInstanceState>,
        transport: FakeEngineMessageTransport,
        repository: MessageRepository = FakeMessageRepository(),
        nextMessageId: () -> String = sequentialIds(),
    ): EngineMessageSource = EngineMessageSource(
        runtime = EngineMessageRuntime(
            listInstances = { instances },
            transportFor = { transport },
            clock = { NOW_MS },
            activeIccids = { instances.filter { it.lifecycle == EngineLifecycle.RUNNING }.map { it.iccid } },
        ),
        parentContext = kotlin.coroutines.coroutineContext,
        repository = repository,
        ownerIdFor = ::ownerFor,
        nextMessageId = nextMessageId,
    )
}

internal fun ownerFor(iccid: String): SimOwnerId? = when (iccid) {
    ICCID_A -> OWNER_A
    ICCID_B -> OWNER_B
    else -> null
}

internal fun sequentialIds(prefix: String = "msg"): () -> String {
    var next = 1
    return { "$prefix-${next++}" }
}

internal class FakeEngineMessageTransport(
    sendResults: ArrayDeque<EngineProtocolFrame.Response> = ArrayDeque(),
    sendResult: EngineProtocolFrame.Response? = null,
    private val requestGate: CompletableDeferred<Unit>? = null,
    private val requestError: Throwable? = null,
    private val timeoutRequests: Boolean = false,
) : EngineMessageTransport {
    val requests = mutableListOf<RecordedRequest>()
    var statusRequests = 0
    private val remaining = sendResults.also { queue ->
        if (sendResult != null) queue.add(sendResult)
    }
    private val mutableEvents = MutableSharedFlow<EngineProtocolFrame.Event>(extraBufferCapacity = 16)
    override val events: SharedFlow<EngineProtocolFrame.Event> = mutableEvents.asSharedFlow()

    override suspend fun request(
        method: String,
        params: JsonObject,
        timeoutMillis: Long,
    ): EngineProtocolFrame.Response {
        if (method == "getStatus") {
            statusRequests++
            return messageStatusResponse()
        }
        requests += RecordedRequest(method, params, timeoutMillis)
        requestGate?.await()
        if (timeoutRequests) {
            withTimeout(1) { delay(50) }
        }
        requestError?.let { throw it }
        return remaining.removeFirst()
    }

    suspend fun emitSent(smsId: String, status: SmsEngineStatus) {
        mutableEvents.emit(
            EngineProtocolFrame.Event(
                name = "smsSent",
                data = buildJsonObject {
                    put("smsId", smsId)
                    put("status", status.wireName())
                    put("reference", 66)
                },
            ),
        )
    }

    suspend fun emitReceived(smsId: String, number: String, text: String, timestampSeconds: Long) {
        mutableEvents.emit(
            EngineProtocolFrame.Event(
                name = "smsReceived",
                data = buildJsonObject {
                    put("smsId", smsId)
                    put("number", number)
                    put("text", text)
                    put("timestamp", timestampSeconds)
                },
            ),
        )
    }
}

internal data class RecordedRequest(
    val method: String,
    val params: JsonObject,
    val timeoutMillis: Long,
)

private fun sendOk(smsId: String, statusCode: Int, reference: Int): EngineProtocolFrame.Response =
    EngineProtocolFrame.Response(
        id = 20,
        result = buildJsonObject {
            put("smsId", smsId)
            put("statusCode", statusCode)
            put("reference", reference)
        },
        error = null,
    )

private fun sendError(code: Int, message: String): EngineProtocolFrame.Response =
    EngineProtocolFrame.Response(
        id = 21,
        result = null,
        error = EngineProtocolError(code, message),
    )

private fun running(iccid: String) = EngineInstanceState(iccid, EngineLifecycle.RUNNING, pid = 7)
private fun stopped(iccid: String) = EngineInstanceState(iccid, EngineLifecycle.STOPPED)

private fun SmsEngineStatus.wireName(): String = when (this) {
    SmsEngineStatus.ACCEPTED -> "accepted"
    SmsEngineStatus.PENDING -> "pending"
    SmsEngineStatus.DELIVERED -> "delivered"
    SmsEngineStatus.FORWARDED -> "forwarded"
    SmsEngineStatus.REPLACED -> "replaced"
    SmsEngineStatus.FAILED -> "failed"
}

private const val SYNTH_NUMBER = "+155501"
private const val SYNTH_PEER = "+155502"
private const val SYNTH_TEXT = "synth-one"
private const val MO_ID = "mo-synth-1"
private const val MT_ID = "mt-synth-1"
private const val ICCID_A = "8986001"
private const val ICCID_B = "8986002"
private const val NOW_MS = 1_700_000_000_000L
