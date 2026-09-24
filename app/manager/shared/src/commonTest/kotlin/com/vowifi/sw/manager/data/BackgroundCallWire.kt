package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class BackgroundCallWire : CallEventTransport {
    override val events = MutableSharedFlow<EngineProtocolFrame.Event>()
    override val disconnected = MutableSharedFlow<Unit>()
    var connected = false
    var available = true
    var closed = false
    var connects = 0
    var snapshots = 0
    var listings = 0
    var listingFailure: Exception? = null
    var inFlight = 0
    var maximumInFlight = 0
    var result = snapshot("IDLE", revision = 1)
    var gate: CompletableDeferred<Unit>? = null
    val commands = mutableListOf<String>()
    override suspend fun connect() {
        connects++
        check(!closed && available)
        connected = true
    }
    override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): EngineProtocolFrame.Response {
        commands += method
        return protocolError(2003, "network")
    }
    val control = CallControlTransport { _, method, _, _ ->
        check(method == "getCallSnapshot")
        check(connected && available && !closed)
        snapshots++
        inFlight++
        maximumInFlight = maxOf(maximumInFlight, inFlight)
        try { gate?.await(); result } finally { inFlight-- }
    }
    suspend fun disconnect() {
        connected = false
        disconnected.emit(Unit)
    }
    suspend fun incoming(token: String = "next-call", epoch: String = "epoch-a", revision: Long = 2) {
        if (!connected || closed) return
        events.emit(EngineProtocolFrame.Event("incomingCall", buildJsonObject {
            put("callId", token); put("state", "RINGING")
            put("epoch", epoch); put("revision", revision); put("peer", "+12025550123")
        }))
    }
    override fun close() { closed = true; connected = false }
}
