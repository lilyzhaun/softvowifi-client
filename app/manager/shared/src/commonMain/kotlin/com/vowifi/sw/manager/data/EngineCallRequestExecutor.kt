package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallFailureMessages
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlin.coroutines.cancellation.CancellationException

internal object EngineCallRequestExecutor {
    suspend fun execute(request: PlannedCallRequest): CallRequestResult = try {
        when (request) {
            is PlannedCallRequest.Control -> {
                val response = request.transport.request(
                    request.action.method,
                    CallProtocolCodec.params(request.token),
                    request.action.timeoutMillis,
                )
                CallRequestResult(operationMessage = controlMessage(request, response))
            }
            is PlannedCallRequest.Dial -> {
                val response = request.transport.request(
                    "dial",
                    CallProtocolCodec.dialParams(request.number),
                    CallProtocolCodec.DIAL_TIMEOUT_MILLIS,
                )
                when {
                    response.error != null ->
                        CallRequestResult(operationMessage = CallProtocolCodec.messageOf(response.error), errorCode = response.error.code)
                    else -> {
                        val token = CallProtocolCodec.dialResultToken(response)
                        if (token == null) {
                            CallRequestResult(operationMessage = CallFailureMessages.INVALID)
                        } else {
                            CallRequestResult(resultToken = token)
                        }
                    }
                }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        CallRequestResult(operationMessage = CallFailureMessages.NETWORK)
    }

    private fun controlMessage(
        request: PlannedCallRequest.Control,
        response: EngineProtocolFrame.Response,
    ): String? {
        response.error?.let { error -> return CallProtocolCodec.messageOf(error) }
        if (response.result == null) return CallFailureMessages.INVALID
        val ok = when (request.action) {
            CallControlAction.Answer ->
                CallProtocolCodec.resultCallId(response) == request.token &&
                    CallProtocolCodec.resultState(response) == "RINGING"
            CallControlAction.Reject, CallControlAction.Hangup ->
                CallProtocolCodec.resultState(response) == "IDLE"
        }
        return if (ok) null else CallFailureMessages.INVALID
    }
}
