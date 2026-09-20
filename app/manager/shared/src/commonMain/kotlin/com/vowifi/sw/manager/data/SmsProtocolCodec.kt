package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineProtocolFrame
import com.vowifi.sw.manager.model.SmsEngineStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class SmsSendResult(
    val smsId: String,
    val statusCode: Int,
    val reference: Int,
)

sealed interface SmsWireEvent {
    data class Received(
        val smsId: String,
        val number: String,
        val text: String,
        val timestampSeconds: Long?,
        val smsc: String? = null,
        val reference: Int? = null,
    ) : SmsWireEvent

    data class Sent(
        val smsId: String,
        val status: SmsEngineStatus,
        val reference: Int? = null,
        val cause: Int? = null,
        val number: String? = null,
        val timestampSeconds: Long? = null,
        val tpStatus: String? = null,
        val reason: String? = null,
    ) : SmsWireEvent
}

object SmsProtocolCodec {
    fun params(number: String, text: String): JsonObject = buildJsonObject {
        put("number", number)
        put("text", text)
    }

    fun isAcceptedStatusCode(statusCode: Int): Boolean = statusCode in 200..299

    fun sendResult(response: EngineProtocolFrame.Response): SmsSendResult {
        val result = requireNotNull(response.result) { "sendSms response has no result" }
        return SmsSendResult(
            smsId = requireNotNull(result.string("smsId")) { "sendSms result missing smsId" },
            statusCode = requireNotNull(result.int("statusCode")) { "sendSms result missing statusCode" },
            reference = requireNotNull(result.int("reference")) { "sendSms result missing reference" },
        )
    }

    fun decodeEvent(event: EngineProtocolFrame.Event): SmsWireEvent? = when (event.name) {
        "smsReceived" -> decodeReceived(event.data)
        "smsSent" -> decodeSent(event.data)
        else -> null
    }

    private fun decodeReceived(data: JsonObject): SmsWireEvent.Received? {
        val smsId = data.string("smsId") ?: return null
        val number = data.string("number") ?: return null
        val text = data.string("text") ?: return null
        return SmsWireEvent.Received(
            smsId = smsId,
            number = number,
            text = text,
            timestampSeconds = data.long("timestamp"),
            smsc = data.string("smsc"),
            reference = data.int("reference"),
        )
    }

    private fun decodeSent(data: JsonObject): SmsWireEvent.Sent? {
        val smsId = data.string("smsId") ?: return null
        val status = statusOf(data.string("status") ?: return null) ?: return null
        return SmsWireEvent.Sent(
            smsId = smsId,
            status = status,
            reference = data.int("reference"),
            cause = data.int("cause"),
            number = data.string("number"),
            timestampSeconds = data.long("timestamp"),
            tpStatus = data.string("tpStatus"),
            reason = data.string("reason"),
        )
    }

    private fun statusOf(wire: String): SmsEngineStatus? = when (wire) {
        "accepted" -> SmsEngineStatus.ACCEPTED
        "pending" -> SmsEngineStatus.PENDING
        "delivered" -> SmsEngineStatus.DELIVERED
        "forwarded" -> SmsEngineStatus.FORWARDED
        "replaced" -> SmsEngineStatus.REPLACED
        "failed" -> SmsEngineStatus.FAILED
        else -> null
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
