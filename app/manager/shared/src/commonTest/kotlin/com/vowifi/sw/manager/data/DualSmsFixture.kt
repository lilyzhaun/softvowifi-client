@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineProtocolFrame
import com.vowifi.sw.manager.model.EngineRuntimeStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class DualSmsFixture(val scope: TestScope) {
    val repository = FakeMessageRepository()
    val wireA = DualSmsWire()
    val wireB = DualSmsWire()
    var currentA = wireA
    var instances = listOf(running("8986001"), running("8986002"))
    var active: List<String?>? = listOf("8986001", "8986002")
    val snapshots = mutableListOf<Pair<SimOwnerId, String>>()
    val source = EngineMessageSource(
        EngineMessageRuntime({ instances }, { if (it == "8986001") currentA else wireB }, { 100 }, { active }),
        scope.coroutineContext, repository, ::ownerFor, sequentialIds(),
        MessagePersistenceObserver { owner, _, fresh -> if (fresh != null) snapshots += owner to fresh },
    )

    fun start() = scope.runCurrent()
    fun select(owner: SimOwnerId) { source.selectHistoryOwner(owner); scope.runCurrent() }
    fun refresh() { source.refresh(); scope.runCurrent() }
    fun close() { source.close(); scope.runCurrent() }

    private fun running(iccid: String) = EngineInstanceState(iccid, EngineLifecycle.RUNNING, pid = 7,
        runtime = EngineRuntimeStatus("CONNECTED", reconnects = 0, ims = "REGISTERED", call = "IDLE", transport = "UDP", pcscf = emptyList()))
}

internal class DualSmsWire : EngineMessageTransport {
    override val events = MutableSharedFlow<EngineProtocolFrame.Event>(extraBufferCapacity = 16)
    override val messageDisconnects = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sends = mutableListOf<JsonObject>()
    var gate: CompletableDeferred<Unit>? = null
    var connects = 0
    var connectFailure = false
    var ims = "REGISTERED"

    override suspend fun connect() {
        connects++
        if (connectFailure) error("synthetic connection failure")
    }

    override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): EngineProtocolFrame.Response {
        if (method == "getStatus") return EngineProtocolFrame.Response(1, buildJsonObject {
            put("tunnel", "CONNECTED"); put("reconnects", 0); put("ims", ims)
            put("call", "IDLE"); put("transport", "UDP"); put("pcscf", kotlinx.serialization.json.JsonArray(emptyList()))
        }, null)
        check(method == "sendSms")
        sends += params
        gate?.await()
        return EngineMessagePersistenceFixtures.sendOk("same-mo", 202, 1)
    }

    suspend fun incoming(id: String = "same-mt") = events.emit(EngineProtocolFrame.Event("smsReceived", buildJsonObject {
        put("smsId", id); put("number", "155501"); put("text", "synthetic"); put("timestamp", 1)
    }))

    suspend fun delivered() = events.emit(EngineProtocolFrame.Event("smsSent", buildJsonObject {
        put("smsId", "same-mo"); put("status", "delivered")
    }))
}
