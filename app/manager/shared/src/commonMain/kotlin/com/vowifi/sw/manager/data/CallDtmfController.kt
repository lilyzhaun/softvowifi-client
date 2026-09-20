package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal suspend fun EngineCallSource.performDtmf(expected: CallBinding, digit: Char) {
    val operation = mutex.withLock {
        if (!owns(expected) || session.phase != CallPhase.InCall || dtmfOperation != null || controlOperation != null) return
        if (!dtmfSupported || digit !in "0123456789*#ABCD") {
            session = session.copy(operationMessage = if (!dtmfSupported) CallFailureMessages.DTMF_UNSUPPORTED else CallFailureMessages.INVALID)
            publish()
            return
        }
        dtmfOperation = ++nextOperation
        session = session.copy(operationMessage = null)
        publish()
        nextOperation
    }
    val response = try {
        control.request(expected.owner, "sendDtmf", buildJsonObject {
            put("callId", expected.token)
            put("digit", digit.toString())
        }, CallProtocolCodec.CONTROL_TIMEOUT_MILLIS)
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { null }
    var query: EngineMessageTransport? = null
    mutex.withLock {
        if (!owns(expected) || dtmfOperation != operation || session.phase != CallPhase.InCall) return
        dtmfOperation = null
        val ok = response?.error == null &&
            (response?.result?.get("sent") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
        if (!ok) {
            if (response?.error?.code == 2004) dtmfSupported = false
            if (response?.error?.code in setOf(1001, 3002) || response == null) {
                sync = CallSyncState.Recovering
                query = activeTransport
            }
            session = session.copy(operationMessage = if (response == null) CallFailureMessages.NETWORK
                else if (response.error == null) CallFailureMessages.INVALID else CallProtocolCodec.messageOf(response.error))
        }
        publish()
    }
    query?.let { fetchSnapshot(it) }
}
