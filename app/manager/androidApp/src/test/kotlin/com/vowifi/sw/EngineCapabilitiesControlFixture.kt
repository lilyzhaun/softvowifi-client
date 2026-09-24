package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidEngineControlSource
import com.vowifi.sw.manager.data.EngineClientCapabilitiesCodec
import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*

internal class EngineCapabilitiesControlFixture(
    firstRunning: Boolean = true,
    capabilityQuery: suspend (String) -> ClientCapabilitiesResult = { metadata(it) },
) : AutoCloseable {
    val dispatcher = HomeControlDispatcher()
    val commands = mutableListOf<Pair<String, String?>>()
    val requests = mutableListOf<Pair<String, String>>()
    val capabilityOwners = mutableListOf<String>()
    val removed = mutableListOf<String>()
    var closes = 0
    private val running = linkedMapOf(A to firstRunning, B to true)
    val source: AndroidEngineControlSource

    init {
        val shell: suspend (String, String?) -> String = { command, owner ->
            commands += command to owner
            when (command) {
                "start" -> { running[requireNotNull(owner)] = true; "{}" }
                "stop" -> { running[requireNotNull(owner)] = false; "{}" }
                "list" -> buildJsonArray {
                    running.forEach { (key, active) -> add(buildJsonObject {
                        put("ok", true); put("iccid", key); put("state", if (active) "running" else "stopped")
                        if (active) put("pid", if (key == A) 101 else 202)
                    }) }
                }.toString()
                else -> error("unexpected command")
            }
        }
        val rpc: suspend (String, String) -> EngineProtocolFrame.Response = { owner, method ->
            requests += owner to method
            EngineProtocolFrame.Response(1, when (method) {
                "getStatus" -> buildJsonObject {
                    put("tunnel", "CONNECTED"); put("reconnects", 0); put("ims", "REGISTERED")
                    put("call", "IDLE"); put("transport", "tcp"); put("pcscf", buildJsonArray {})
                }
                "stop" -> buildJsonObject {}
                else -> error("unexpected RPC")
            }, null)
        }
        val retire: (String) -> Unit = { removed += it }
        val close: () -> Unit = { closes++ }
        val query: suspend (String) -> ClientCapabilitiesResult = { owner ->
            capabilityOwners += owner
            capabilityQuery(owner)
        }
        source = AndroidEngineControlSource::class.java.declaredConstructors.single { it.parameterCount == 6 }
            .apply { isAccessible = true }.newInstance(shell, rpc, retire, close, query, dispatcher) as AndroidEngineControlSource
        dispatcher.runCurrent()
    }

    fun row(owner: String) = (source.state.value as EngineControlUiState.Ready).instances.single { it.iccid == owner }
    override fun close() { source.close(); dispatcher.runCurrent() }

    companion object {
        const val A = "synthetic-a"
        const val B = "synthetic-b"
        fun metadata(owner: String): ClientCapabilitiesResult = EngineClientCapabilitiesCodec.decode(
            EngineProtocolFrame.Response(1, EngineCapabilitiesHostFixture.metadata(
                if (owner == A) EngineCapabilitiesHostFixture.KEY else EngineCapabilitiesHostFixture.OTHER_KEY), null))
    }
}
