package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallDirection
import com.vowifi.sw.manager.model.CallPeer
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

internal data class CallVersion(val epoch: String, val revision: Long) {
    override fun toString(): String = "CallVersion(revision=$revision)"
}
internal class CallSnapshot(
    val version: CallVersion, val phase: CallPhase, val token: String?,
    val direction: CallDirection?, val peer: CallPeer?, val elapsed: Long, val dtmf: Boolean,
)
internal object CallSnapshotCodec {
    fun version(data: JsonObject): CallVersion? {
        val epoch = data.text("epoch")?.takeIf { it.isNotEmpty() } ?: return null
        val revision = data.number("revision")?.takeIf { it >= 0 } ?: return null
        return CallVersion(epoch, revision)
    }
    fun decode(response: EngineProtocolFrame.Response): CallSnapshot? {
        if (response.error != null) return null
        val data = response.result ?: return null
        val version = version(data) ?: return null
        val phase = when (data.text("stage")) {
            "IDLE" -> CallPhase.Idle
            "RINGING" -> CallPhase.Ringing
            "DIALING" -> CallPhase.Dialing
            "CONNECTING" -> CallPhase.Connecting
            "IN_CALL" -> CallPhase.InCall
            "CANCELING" -> CallPhase.Canceling
            "ENDING" -> CallPhase.Ending
            else -> return null
        }
        val elapsed = data.number("confirmedElapsedMs")?.takeIf { it >= 0 } ?: return null
        val dtmf = (data["dtmfSupported"] as? JsonPrimitive)
            ?.takeIf { !it.isString }?.booleanOrNull ?: return null
        val token = data.text("callId")?.takeIf { it.isNotEmpty() }
        val direction = when (data.text("direction")) {
            "INCOMING" -> CallDirection.INCOMING
            "OUTGOING" -> CallDirection.OUTGOING
            else -> null
        }
        if (phase != CallPhase.Idle && (token == null || direction == null)) return null
        if (phase == CallPhase.Idle && ("callId" in data || "direction" in data || "peer" in data)) return null
        return CallSnapshot(version, phase, token, direction, data.text("peer")?.let(CallPeer::parse), elapsed, dtmf)
    }
}
private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.number(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
