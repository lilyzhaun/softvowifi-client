package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallFailureMessages
import com.vowifi.sw.manager.model.CallPeer
import com.vowifi.sw.manager.model.EngineProtocolError
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal sealed interface CallWireEvent {
    val token: String
    data class Ringing(
        override val token: String,
        val peer: CallPeer? = null,
    ) : CallWireEvent
    data class Dialing(override val token: String, val ringback: Boolean = false, val earlyMediaRevision: Long = 0) : CallWireEvent
    data class Ending(override val token: String) : CallWireEvent
    data class InCall(
        override val token: String,
        val peer: CallPeer? = null,
    ) : CallWireEvent
    data class Ended(
        override val token: String,
        val reason: CallWireReason,
        val durationSec: Int? = null,
        val peer: CallPeer? = null,
    ) : CallWireEvent
}

internal enum class CallHangupResult { Completed, Pending }

internal enum class CallWireReason {
    REJECTED,
    CANCELED,
    TIMEOUT,
    HANGUP,
    REMOTE,
    FAILED,
}

object CallProtocolCodec {
    const val ANSWER_TIMEOUT_MILLIS = 13_000L
    const val CONTROL_TIMEOUT_MILLIS = 5_000L
    const val AUDIO_START_TIMEOUT_MILLIS = 5_000L
    // Engine: 120 s call setup + bounded cancellation; leave RPC delivery margin.
    const val DIAL_TIMEOUT_MILLIS = 130_000L
    const val MAX_DURATION_SEC = 359_999

    private val DIAL_NUMBER = Regex("""\+?\d{1,20}""")

    fun params(token: String): JsonObject = buildJsonObject {
        put("callId", token)
    }

    fun audioStartParams(token: String): JsonObject = buildJsonObject {
        put("callId", token)
        put("sampleRate", PcmFrameCodec.SAMPLE_RATE_HZ)
        put("channels", PcmFrameCodec.CHANNEL_COUNT)
    }

    fun isValidDialNumber(number: String): Boolean = DIAL_NUMBER.matches(number)

    fun dialParams(number: String): JsonObject = buildJsonObject {
        put("recipient", "tel:$number")
    }

    fun dialResultToken(response: EngineProtocolFrame.Response): String? {
        if (response.error != null) return null
        val result = response.result ?: return null
        if (result.string("state") != "IN_CALL") return null
        val token = result.string("callId") ?: return null
        return token.takeIf(String::isNotEmpty)
    }

    internal fun decodeEvent(event: EngineProtocolFrame.Event): CallWireEvent? {
        val token = event.data.string("callId") ?: return null
        if (token.isEmpty()) return null
        return when (event.name) {
            "incomingCall" -> incoming(token, event.data)
            "callState" -> callState(token, event.data)
            "callEnded" -> callEnded(token, event.data)
            else -> null
        }
    }

    fun messageOf(error: EngineProtocolError?): String = when (error?.code) {
        1001 -> CallFailureMessages.INVALID
        2001 -> CallFailureMessages.BUSY
        2002 -> CallFailureMessages.NO_ANSWER
        2004 -> CallFailureMessages.DTMF_UNSUPPORTED
        2005 -> CallFailureMessages.CANCELED
        2006 -> CallFailureMessages.SNAPSHOT
        3002 -> CallFailureMessages.IMS
        5001 -> CallFailureMessages.AUDIO
        else -> CallFailureMessages.NETWORK
    }

    fun resultCallId(response: EngineProtocolFrame.Response): String? =
        response.result?.string("callId")?.takeIf(String::isNotEmpty)

    fun resultState(response: EngineProtocolFrame.Response): String? =
        response.result?.string("state")

    internal fun hangupResult(response: EngineProtocolFrame.Response, token: String): CallHangupResult? {
        if (response.error != null) return null
        val result = response.result ?: return null
        return when {
            result.keys == setOf("state") && result.string("state") == "IDLE" -> CallHangupResult.Completed
            result.keys == setOf("accepted", "callId") && result.string("callId") == token &&
                (result["accepted"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true -> CallHangupResult.Pending
            else -> null
        }
    }

    private fun incoming(token: String, data: JsonObject): CallWireEvent? = when (data.string("state")) {
        "RINGING" -> CallWireEvent.Ringing(token, data.peer())
        "IN_CALL" -> CallWireEvent.InCall(token, data.peer())
        "ENDED" -> {
            val reason = reasonOf(data.string("reason")) ?: return null
            CallWireEvent.Ended(token, reason, data.durationSec(), data.peer())
        }
        else -> null
    }

    private fun callState(token: String, data: JsonObject): CallWireEvent? = when (data.string("state")) {
        "DIALING" -> decodeCallProgress(data)?.let { CallWireEvent.Dialing(token, it.first, it.second) }
        "IN_CALL" -> CallWireEvent.InCall(token)
        "ENDING" -> CallWireEvent.Ending(token)
        else -> null
    }

    private fun callEnded(token: String, data: JsonObject): CallWireEvent? {
        val reason = when (data.string("reason")) {
            "hangup" -> CallWireReason.HANGUP
            "remote" -> CallWireReason.REMOTE
            "failed" -> CallWireReason.FAILED
            "canceled" -> CallWireReason.CANCELED
            else -> return null
        }
        return CallWireEvent.Ended(token, reason, data.durationSec())
    }

    private fun reasonOf(wire: String?): CallWireReason? = when (wire) {
        "rejected" -> CallWireReason.REJECTED
        "canceled" -> CallWireReason.CANCELED
        "timeout" -> CallWireReason.TIMEOUT
        "hangup" -> CallWireReason.HANGUP
        "remote" -> CallWireReason.REMOTE
        "failed" -> CallWireReason.FAILED
        else -> null
    }
}

internal fun decodeCallProgress(data: JsonObject): Pair<Boolean, Long>? {
    val ringback = if ("ringback" in data) (data["ringback"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null else false
    val revision = if ("earlyMediaRevision" in data) (data["earlyMediaRevision"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 } ?: return null else 0L
    return ringback to revision
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

private fun JsonObject.peer(): CallPeer? = string("peer")?.let(CallPeer::parse)

private fun JsonObject.durationSec(): Int? =
    int("durationSec")?.takeIf { it in 0..CallProtocolCodec.MAX_DURATION_SEC }
