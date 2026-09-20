package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallFailureMessages
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CallProtocolTypeSafetyTest {
    @Test
    fun dialResultTokenRejectsNumericAndBooleanCallId() {
        assertNull(CallProtocolCodec.dialResultToken(result(JsonPrimitive(42), inCall)))
        assertNull(CallProtocolCodec.dialResultToken(result(JsonPrimitive(true), inCall)))
        assertNull(CallProtocolCodec.resultCallId(result(JsonPrimitive(42), inCall)))
        assertNull(CallProtocolCodec.resultState(result(JsonPrimitive(CALL_TOKEN), JsonPrimitive(true))))
    }

    @Test
    fun dialResultTokenReturnsNullForObjectAndArrayWithoutThrowing() {
        val obj = result(buildJsonObject { put("id", CALL_TOKEN) }, inCall)
        val arr = result(buildJsonArray { add(CALL_TOKEN) }, inCall)
        assertNull(CallProtocolCodec.dialResultToken(obj))
        assertNull(CallProtocolCodec.dialResultToken(arr))
        assertNull(CallProtocolCodec.resultCallId(obj))
        assertNull(CallProtocolCodec.decodeEvent(incoming(buildJsonObject { put("id", CALL_TOKEN) })))
    }

    @Test
    fun executorMapsNonStringDialResultToInvalidNotNetwork() = runTest {
        val numeric = FakeCallTransport()
        numeric.enqueue(result(JsonPrimitive(42), inCall))
        val numericResult = EngineCallRequestExecutor.execute(
            PlannedCallRequest.Dial(numeric, CALL_NUMBER, OWNER),
        )
        assertEquals(CallFailureMessages.INVALID, numericResult.operationMessage)
        assertEquals(null, numericResult.resultToken)

        val boxed = FakeCallTransport()
        boxed.enqueue(result(buildJsonObject { put("id", CALL_TOKEN) }, inCall))
        val boxedResult = EngineCallRequestExecutor.execute(
            PlannedCallRequest.Dial(boxed, CALL_NUMBER, OWNER),
        )
        assertEquals(CallFailureMessages.INVALID, boxedResult.operationMessage)
        assertEquals(null, boxedResult.resultToken)
    }

    @Test
    fun decodeEventIgnoresNonStringCallIdAndStateWithoutThrowing() {
        assertNull(CallProtocolCodec.decodeEvent(incoming(JsonPrimitive(1))))
        assertNull(CallProtocolCodec.decodeEvent(incoming(JsonPrimitive(true))))
        assertNull(
            CallProtocolCodec.decodeEvent(
                EngineProtocolFrame.Event(
                    name = "incomingCall",
                    data = buildJsonObject {
                        put("callId", CALL_TOKEN)
                        put("state", true)
                    },
                ),
            ),
        )
        assertNull(CallProtocolCodec.decodeEvent(incoming(buildJsonArray { add(CALL_TOKEN) })))
    }

    private fun result(callId: JsonElement, state: JsonPrimitive) =
        EngineProtocolFrame.Response(
            id = 1,
            result = buildJsonObject {
                put("callId", callId)
                put("state", state)
            },
            error = null,
        )

    private fun incoming(callId: JsonElement) = EngineProtocolFrame.Event(
        name = "incomingCall",
        data = buildJsonObject {
            put("callId", callId)
            put("state", "RINGING")
        },
    )
}

private val inCall = JsonPrimitive("IN_CALL")
private val OWNER = CallRequestOwner(generation = 1, operationId = 1)
