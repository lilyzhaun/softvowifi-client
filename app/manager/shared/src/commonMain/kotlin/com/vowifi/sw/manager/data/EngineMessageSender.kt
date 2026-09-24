package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.withLock

internal suspend fun sendMessage(session: EngineMessageSession, line: MessageLiveSession, number: String, text: String) {
    val planned = session.mutex.withLock {
        if (!session.isCurrent(line) || !session.isAvailable(line.owner) || !line.connected || !line.registered || line.sendBusy || !line.storageHealthy) return
        val send = PlannedMessageSend(session.nextMessageId(), line, number, text)
        line.submitted = send
        line.sendBusy = true
        line.attachmentNotice = null
        session.publishRecords(line)
        if (!persistQueuedMessage(session, send)) return
        send
    }
    if (!session.isCurrent(line)) return
    try {
        val response = planned.transport.request("sendSms", SmsProtocolCodec.params(planned.number, planned.text))
        session.mutex.withLock {
            if (!session.isCurrent(line)) return
            persistSendOutcome(session, line) { applySendResponse(session, planned, response) }
        }
    } catch (_: TimeoutCancellationException) {
        persistUnknownOutcome(session, planned)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        persistUnknownOutcome(session, planned)
    }
}

private suspend fun persistQueuedMessage(session: EngineMessageSession, planned: PlannedMessageSend): Boolean {
    val line = planned.line
    try {
        session.repository.insertQueued(planned.owner, planned.messageId, planned.number, planned.text, session.runtime.clock())
    } catch (error: CancellationException) { throw error
    } catch (_: Exception) {
        line.sendBusy = false
        line.attachmentNotice = STORAGE_NOTICE
        session.publishRecords(line)
        return false
    }
    try {
        line.records = session.repository.listByOwner(planned.owner)
    } catch (error: CancellationException) { throw error
    } catch (_: Exception) {
        line.sendBusy = false
        line.storageHealthy = false
        line.attachmentNotice = STORAGE_NOTICE
        session.publishRecords(line)
        return false
    }
    session.publishRecords(line)
    return true
}

private suspend fun applySendResponse(session: EngineMessageSession, planned: PlannedMessageSend, response: EngineProtocolFrame.Response) {
    val line = planned.line
    val sendResult = if (response.error == null) runCatching { SmsProtocolCodec.sendResult(response) }.getOrNull() else null
    when {
        response.error != null || sendResult != null && !SmsProtocolCodec.isAcceptedStatusCode(sendResult.statusCode) ->
            session.repository.markFailed(planned.owner, planned.messageId, session.runtime.clock())
        sendResult != null -> {
            session.repository.bindAccepted(planned.owner, planned.messageId, sendResult.smsId, session.runtime.clock())
            replayPendingSent(session, line)
        }
        else -> session.repository.markUnknown(planned.owner, planned.messageId, session.runtime.clock())
    }
    line.records = session.repository.listByOwner(planned.owner)
}

private suspend fun persistUnknownOutcome(session: EngineMessageSession, planned: PlannedMessageSend) {
    session.mutex.withLock {
        if (!session.isCurrent(planned.line)) return
        persistSendOutcome(session, planned.line) {
            session.repository.markUnknown(planned.owner, planned.messageId, session.runtime.clock())
            planned.line.records = session.repository.listByOwner(planned.owner)
        }
    }
}

private suspend fun persistSendOutcome(session: EngineMessageSession, line: MessageLiveSession, block: suspend () -> Unit) {
    try { block()
    } catch (error: CancellationException) { throw error
    } catch (_: Exception) {
        line.storageHealthy = false
        line.attachmentNotice = STORAGE_SYNC_NOTICE
    }
    line.pendingSent = emptyList()
    line.sendBusy = false
    session.publishRecords(line)
}
