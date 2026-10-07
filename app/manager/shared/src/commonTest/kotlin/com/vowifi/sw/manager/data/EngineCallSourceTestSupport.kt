package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallAudioState
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineProtocolError
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal suspend fun sourceOf(
    instances: List<EngineInstanceState>,
    transport: FakeCallTransport,
    audio: FakeCallAudioController = FakeCallAudioController(),
    history: CallHistoryRepository? = null,
    ownerIdFor: (String) -> SimOwnerId? = { OWNER_A },
    clock: () -> Long = { 0L },
    nextCorrelation: () -> String = { "out-synth-1" },
    listInstances: (suspend () -> List<EngineInstanceState>)? = null,
    control: CallControlTransport = CallControlTransport { _, method, params, timeout -> transport.request(method, params, timeout) },
    monotonicNow: () -> Long = CallSessionClock.monotonic(),
): EngineCallSource = EngineCallSource(
    runtime = EngineMessageRuntime(
        listInstances = listInstances ?: { instances },
        transportFor = { transport },
        clock = clock,
    ),
    audio = audio,
    control = control,
    monotonicNow = monotonicNow,
    parentContext = kotlin.coroutines.coroutineContext,
    history = history?.let { repository ->
        CallHistoryBinding(repository, ownerIdFor, nextCorrelation)
    },
)

internal class FakeCallTransport(
    private val responses: Map<String, EngineProtocolFrame.Response> = emptyMap(),
    private val requestGate: CompletableDeferred<Unit>? = null,
    private val gatedMethods: Set<String>? = null,
    private val connectHandler: suspend FakeCallTransport.() -> Unit = {},
    private val timeoutMethods: Set<String> = emptySet(),
    var throwOnRequest: Boolean = false,
    var cancelRequest: Boolean = false,
) : EngineMessageTransport {
    val requests = mutableListOf<RecordedCallRequest>()
    val snapshotRequests = mutableListOf<RecordedCallRequest>()
    private var snapshotStage = "IDLE"
    private var snapshotToken = CALL_TOKEN
    private var snapshotDirection = "OUTGOING"
    private val snapshots = ArrayDeque<EngineProtocolFrame.Response>()
    fun enqueueSnapshot(response: EngineProtocolFrame.Response) { snapshots.add(response) }
    var connectCalls = 0
    private val queued = ArrayDeque<EngineProtocolFrame.Response>()
    private val requestGates = ArrayDeque<CompletableDeferred<Unit>>()
    private val mutableEvents = MutableSharedFlow<EngineProtocolFrame.Event>(extraBufferCapacity = 16)
    override val events: SharedFlow<EngineProtocolFrame.Event> = mutableEvents.asSharedFlow()

    fun enqueue(response: EngineProtocolFrame.Response) {
        queued.add(response)
    }

    fun enqueueGate(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { gate -> requestGates.addLast(gate) }

    override suspend fun connect() {
        connectCalls += 1
        connectHandler()
    }

    override suspend fun request(
        method: String,
        params: JsonObject,
        timeoutMillis: Long,
    ): EngineProtocolFrame.Response {
        if (method == "getCallSnapshot") {
            snapshotRequests += RecordedCallRequest(method, params, timeoutMillis)
            if (gatedMethods?.contains(method) == true) requestGate?.await()
            if (snapshots.isNotEmpty()) return snapshots.removeFirst()
            return responses[method] ?: EngineProtocolFrame.Response(1, buildJsonObject {
                put("epoch", "fixture-epoch")
                put("revision", 0)
                put("stage", snapshotStage)
                put("confirmedElapsedMs", 0)
                put("dtmfSupported", false)
                if (snapshotStage != "IDLE") {
                    put("callId", snapshotToken)
                    put("direction", snapshotDirection)
                }
            }, null)
        }
        requests += RecordedCallRequest(method, params, timeoutMillis)
        when {
            requestGates.isNotEmpty() -> requestGates.removeFirst().await()
            requestGate != null && (gatedMethods == null || method in gatedMethods) -> requestGate.await()
        }
        if (method in timeoutMethods) withEngineRequestTimeout(1) { delay(50) }
        if (cancelRequest) throw CancellationException("parent cancelled")
        if (throwOnRequest) error("socket reset sip:bob@example.com")
        val response = if (queued.isNotEmpty()) queued.removeFirst() else responses[method] ?: defaultResponse(method)
        if (response.error == null) {
            when (method) {
                "dial" -> if (CallProtocolCodec.dialResultToken(response) != null) {
                    snapshotStage = "IN_CALL"
                    snapshotToken = CallProtocolCodec.dialResultToken(response)!!
                    snapshotDirection = "OUTGOING"
                }
                "hangup", "reject" -> snapshotStage = "IDLE"
            }
        }
        return response
    }

    suspend fun emitIncoming(
        token: String,
        state: String,
        reason: String? = null,
        peer: String? = null,
        durationSec: Int? = null,
    ) {
        snapshotToken = token
        snapshotDirection = "INCOMING"
        snapshotStage = if (state == "ENDED") "IDLE" else state
        emit("incomingCall", token) {
            put("state", state)
            if (reason != null) put("reason", reason)
            if (peer != null) put("peer", peer)
            if (durationSec != null) put("durationSec", durationSec)
        }
    }

    suspend fun emitCallState(token: String, state: String) {
        snapshotToken = token
        snapshotStage = state
        snapshotDirection = "OUTGOING"
        emit("callState", token) { put("state", state) }
    }

    suspend fun emitProgress(token: String, ringback: Boolean, earlyMediaRevision: Long = 0) {
        snapshotToken = token
        snapshotStage = "DIALING"
        snapshotDirection = "OUTGOING"
        emit("callState", token) {
            put("state", "DIALING")
            put("ringback", ringback)
            put("earlyMediaRevision", earlyMediaRevision)
        }
    }

    suspend fun emitCallEnded(token: String, reason: String, durationSec: Int? = null) {
        snapshotStage = "IDLE"
        emit("callEnded", token) {
            put("reason", reason)
            if (durationSec != null) put("durationSec", durationSec)
        }
    }

    suspend fun emitSms() {
        mutableEvents.emit(
            EngineProtocolFrame.Event(
                name = "smsReceived",
                data = buildJsonObject { put("smsId", "mt-1") },
            ),
        )
    }

    suspend fun emitVersioned(name: String, token: String, state: String?, epoch: String, revision: Long, reason: String? = null) {
        emit(name, token) {
            if (state != null) put("state", state)
            put("epoch", epoch)
            put("revision", revision)
            if (reason != null) put("reason", reason)
        }
    }

    private suspend fun emit(name: String, token: String, extra: JsonObjectBuilder.() -> Unit) {
        mutableEvents.emit(
            EngineProtocolFrame.Event(
                name = name,
                data = buildJsonObject {
                    put("callId", token)
                    extra()
                },
            ),
        )
    }
}

internal class FakeCallAudioController(
    private val startHandler: suspend () -> Unit = {},
    private val emitEndedOnStop: Boolean = false,
) : CallAudioController {
    data class Start(
        val iccid: String,
        val token: String,
        val generation: Long,
        val transport: EngineMessageTransport,
        val mode: CallAudioMode = CallAudioMode.Conversation,
        val earlyMediaRevision: Long = 0,
        val ringback: Boolean = false,
    )

    val starts = mutableListOf<Start>()
    val endTones = mutableListOf<CallAudioSession>()
    override fun callEnded(session: CallAudioSession) { endTones.add(session); stopIf(session) }
    val operations = mutableListOf<String>()
    var activeToken: String? = null
    var lastEndedToken: String? = null
    var stopCalls = 0
    var phaseAtStop: CallPhase? = null
    var stateReader: (() -> CallUiState)? = null
    private val startGates = ArrayDeque<CompletableDeferred<Unit>>()
    private val mutableEvents = MutableSharedFlow<CallAudioEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<CallAudioEvent> = mutableEvents.asSharedFlow()

    fun enqueueStartGate(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { gate -> startGates.addLast(gate) }

    override suspend fun start(session: CallAudioSession) {
        starts += Start(session.iccid, session.token, session.generation, session.transport, session.mode, session.earlyMediaRevision, session.ringback)
        operations += "start:${session.token}"
        if (startGates.isNotEmpty()) startGates.removeFirst().await()
        startHandler()
        activeToken = session.token
        operations += "active:${session.token}"
    }

    override fun stop() {
        val endedOwner = activeToken?.let { activeSession(it) }
        operations += "stop"
        activeToken = null
        stopCalls += 1
        phaseAtStop = (stateReader?.invoke() as? CallUiState.Ready)?.phase
        if (emitEndedOnStop && endedOwner != null) mutableEvents.tryEmit(CallAudioEvent.Ended(endedOwner))
    }

    suspend fun emitEnded(token: String? = null) {
        lastEndedToken = token
        mutableEvents.emit(CallAudioEvent.Ended(activeSession(token ?: activeToken)))
    }

    private fun activeSession(token: String? = activeToken): CallAudioSession =
        starts.lastOrNull { it.token == token }?.let { start ->
            CallAudioSession(start.iccid, start.token, start.generation, start.transport, start.mode, start.earlyMediaRevision, start.ringback)
        } ?: CallAudioSession(CALL_ICCID_A, token ?: CALL_TOKEN, 0L, starts.lastOrNull()?.transport ?: error("no audio"))
}

internal data class RecordedCallRequest(
    val method: String,
    val params: JsonObject,
    val timeoutMillis: Long,
)

internal fun protocolError(code: Int, message: String): EngineProtocolFrame.Response =
    EngineProtocolFrame.Response(id = 21, result = null, error = EngineProtocolError(code, message))

internal fun dialOk(token: String): EngineProtocolFrame.Response =
    EngineProtocolFrame.Response(
        id = 20,
        result = buildJsonObject {
            put("callId", token)
            put("state", "IN_CALL")
        },
        error = null,
    )

internal fun emptySuccess(): EngineProtocolFrame.Response =
    EngineProtocolFrame.Response(id = 20, result = null, error = null)

internal fun callRunning(iccid: String) = EngineInstanceState(iccid, EngineLifecycle.RUNNING, pid = 7)
internal fun callStopped(iccid: String) = EngineInstanceState(iccid, EngineLifecycle.STOPPED)

internal fun assertNoGetStatus(transport: FakeCallTransport) {
    assertTrue(transport.requests.none { request -> request.method == "getStatus" })
}

internal fun assertCallPrivacy(state: CallUiState, vararg forbidden: String) {
    visibleCallStrings(state).forEach { text ->
        forbidden.forEach { item ->
            assertFalse(text.contains(item), "leaked $item in $text")
        }
        assertFalse(phoneLike.containsMatchIn(text), "leaked phone-like digits in $text")
        assertFalse(identityUri.containsMatchIn(text), "leaked SIP/TEL URI in $text")
        assertFalse(text.contains("callId"), "leaked callId in $text")
    }
}

internal fun visibleCallStrings(state: CallUiState): List<String> = when (state) {
    CallUiState.Loading -> emptyList()
    is CallUiState.Ready -> buildList {
        add(state.phase.name)
        add(state.origin.name)
        add(state.busy.toString())
        when (val audio = state.audio) {
            is CallAudioState.Failed -> add(audio.message)
            CallAudioState.Active -> add("Active")
            CallAudioState.Idle -> add("Idle")
        }
        state.operationMessage?.let { message -> add(message) }
        add(state.peer?.toString() ?: "peerAbsent")
    }
    is CallUiState.Failure -> listOf(state.message)
    is CallUiState.PermissionDenied -> listOf(state.message)
    is CallUiState.Unavailable -> listOf(state.message)
}

private fun defaultResponse(method: String): EngineProtocolFrame.Response {
    val result = when (method) {
        "answer" -> buildJsonObject {
            put("callId", CALL_TOKEN)
            put("state", "RINGING")
        }
        "dial" -> buildJsonObject {
            put("callId", CALL_TOKEN)
            put("state", "IN_CALL")
        }
        else -> buildJsonObject { put("state", "IDLE") }
    }
    return EngineProtocolFrame.Response(id = 20, result = result, error = null)
}

internal const val CALL_TOKEN = "tok-synth-1"
internal const val CALL_OTHER = "tok-other-2"
internal const val CALL_ICCID_A = "8986001"
internal const val CALL_ICCID_B = "8986002"
internal const val CALL_NUMBER = "+15550101"
private val phoneLike = Regex("""\+?\d{8,}""")
private val identityUri = Regex("""(?i)\b(?:sip|tel):""")
