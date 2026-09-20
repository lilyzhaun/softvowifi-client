package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ClientCapabilitiesFailure
import com.vowifi.sw.manager.model.ClientCapabilitiesResult
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

fun interface ClientCapabilitiesTransport {
    suspend fun request(method: String, params: JsonObject): EngineProtocolFrame.Response
}

class EngineClientCapabilitiesSource(private val transport: ClientCapabilitiesTransport) {
    suspend fun query(): ClientCapabilitiesResult = withTimeoutOrNull(TIMEOUT_MILLIS) {
        try {
            EngineClientCapabilitiesCodec.decode(transport.request("getClientCapabilities", EngineClientCapabilitiesCodec.params()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Transport)
        }
    } ?: ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Timeout)

    companion object {
        const val TIMEOUT_MILLIS = 250L
    }
}
