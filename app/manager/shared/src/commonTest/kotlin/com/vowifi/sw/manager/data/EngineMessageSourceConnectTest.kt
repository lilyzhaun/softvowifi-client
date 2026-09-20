@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineProtocolFrame
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class EngineMessageSourceConnectTest {
    @Test
    fun attachingSingleRunningEngineConnectsWithoutRequest() = runTest(UnconfinedTestDispatcher()) {
        // Given: exactly one RUNNING instance and a recording transport
        val transport = RecordingTransport()

        // When: EngineMessageSource constructs and attaches
        val source = sourceOf(listOf(running(ICCID)), transport)

        // Then: connect is called once, no request is sent, state is Ready
        assertEquals(1, transport.connectCalls)
        assertTrue(transport.requests.isEmpty())
        assertIs<MessageUiState.Inbox>(source.state.value)
        source.close()
    }

    @Test
    fun zeroRunningInstancesDoNotConnect() = runTest(UnconfinedTestDispatcher()) {
        // Given: zero RUNNING instances
        val transport = RecordingTransport()

        // When: EngineMessageSource constructs
        val source = sourceOf(listOf(stopped(ICCID)), transport)

        // Then: connect is not called and state is Unavailable
        assertEquals(0, transport.connectCalls)
        assertTrue(transport.requests.isEmpty())
        assertIs<MessageUiState.Unavailable>(source.state.value)
        source.close()
    }

    @Test
    fun connectFailureSurfacesFailureState() = runTest {
        // Given: connect throws before events are collected
        val transport = RecordingTransport(connectHandler = { error("socket refused") })

        // When: EngineMessageSource constructs and attach drains
        val source = sourceOf(listOf(running(ICCID)), transport)
        testScheduler.advanceUntilIdle()

        // Then: state is Failure with the generic privacy-safe message and no request
        val failure = assertIs<MessageUiState.Failure>(source.state.value)
        assertEquals("无法连接短信会话", failure.message)
        assertEquals(1, transport.connectCalls)
        assertTrue(transport.requests.isEmpty())
        source.close()
    }

    @Test
    fun omittedAndExplicitRequestTimeoutsAreRecorded() = runTest(UnconfinedTestDispatcher()) {
        val recording = RecordingTransport()
        val transport: EngineMessageTransport = recording

        transport.request("getStatus", buildJsonObject {})
        transport.request("answer", buildJsonObject {}, timeoutMillis = 13_000L)

        assertEquals(listOf(5_000L, 13_000L), recording.requests.map { it.timeoutMillis })
    }

    @Test
    fun eventEmittedDuringConnectIsRetainedAsIncoming() = runTest(UnconfinedTestDispatcher()) {
        // Given: connect emits one smsReceived before returning
        val transport = RecordingTransport(
            connectHandler = { emitReceived(MT_ID, SYNTH_NUMBER, SYNTH_TEXT, MT_TIMESTAMP_SECONDS) },
        )

        // When: EngineMessageSource constructs and attaches
        val source = sourceOf(listOf(running(ICCID)), transport)

        // Then: Ready contains that Incoming record
        val ready = inspectInboxThreads(source)
        assertEquals(1, ready.records.size)
        val incoming = assertIs<MessageRecord.Incoming>(ready.records.single())
        assertEquals("msg-1", incoming.id)
        assertEquals(MT_ID, incoming.engineSmsId)
        assertEquals(SYNTH_NUMBER, incoming.peerLabel)
        assertEquals(SYNTH_TEXT, incoming.preview)
        source.close()
    }

    private suspend fun sourceOf(
        instances: List<EngineInstanceState>,
        transport: RecordingTransport,
    ): EngineMessageSource = EngineMessageSource(
        runtime = EngineMessageRuntime(
            listInstances = { instances },
            transportFor = { transport },
            clock = { NOW_MS },
        ),
        parentContext = kotlin.coroutines.coroutineContext,
        repository = FakeMessageRepository(),
        ownerIdFor = { OWNER_A },
        nextMessageId = sequentialIds(),
    )
}

private class RecordingTransport(
    private val connectHandler: suspend RecordingTransport.() -> Unit = {},
) : EngineMessageTransport {
    var connectCalls = 0
    val requests = mutableListOf<RecordedConnectRequest>()
    private val mutableEvents = MutableSharedFlow<EngineProtocolFrame.Event>(extraBufferCapacity = 16)
    override val events: SharedFlow<EngineProtocolFrame.Event> = mutableEvents.asSharedFlow()

    override suspend fun connect() {
        connectCalls += 1
        connectHandler()
    }

    override suspend fun request(
        method: String,
        params: JsonObject,
        timeoutMillis: Long,
    ): EngineProtocolFrame.Response {
        requests += RecordedConnectRequest(method, timeoutMillis)
        return EngineProtocolFrame.Response(id = 1, result = buildJsonObject {}, error = null)
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

private data class RecordedConnectRequest(
    val method: String,
    val timeoutMillis: Long,
)

private fun running(iccid: String) = EngineInstanceState(iccid, EngineLifecycle.RUNNING, pid = 7)
private fun stopped(iccid: String) = EngineInstanceState(iccid, EngineLifecycle.STOPPED)

private const val ICCID = "8986001"
private const val NOW_MS = 1_700_000_000_000L
private const val MT_ID = "mt-synth-1"
private const val SYNTH_NUMBER = "+155501"
private const val SYNTH_TEXT = "synth-one"
private const val MT_TIMESTAMP_SECONDS = 1_755_000_000L
