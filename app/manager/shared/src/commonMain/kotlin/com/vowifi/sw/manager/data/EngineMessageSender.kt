package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.withLock

internal suspend fun sendMessage(session: EngineMessageSession, number: String, text: String, expectedOwner: SimOwnerId) {
    val planned = session.mutex.withLock {
        val transport = session.activeTransport ?: return@withLock null
        val owner = session.activeOwner ?: return@withLock null
        if (owner != expectedOwner) return@withLock null
        if (!session.selection.allowsSend(owner)) return@withLock null
        if (session.sendBusy || !session.storageHealthy) return@withLock null
        val messageId = session.nextMessageId()
        session.sendBusy = true
        session.attachmentNotice = null
        session.publishRecords()
        PlannedMessageSend(messageId, owner, transport, number, text).also { session.submitted = it }
    } ?: return
    if (!persistQueuedMessage(session, planned)) return
    try {
        val response = planned.transport.request("sendSms", SmsProtocolCodec.params(planned.number, planned.text))
        session.mutex.withLock {
            if (session.activeTransport !== planned.transport) return
            persistSendOutcome(session) { applySendResponse(session, planned, response) }
            session.finishSend()
        }
    } catch (_: TimeoutCancellationException) {
        persistUnknownOutcome(session, planned)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        persistUnknownOutcome(session, planned)
    }
}

private suspend fun persistQueuedMessage(
    session: EngineMessageSession,
    planned: PlannedMessageSend,
): Boolean {
    try {
        session.repository.insertQueued(
            ownerId = planned.owner,
            messageId = planned.messageId,
            peerAddress = planned.number,
            body = planned.text,
            nowMs = session.runtime.clock(),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        session.mutex.withLock {
            if (session.activeTransport !== planned.transport) return false
            session.sendBusy = false
            session.attachmentNotice = STORAGE_NOTICE
            session.publishRecords()
        }
        return false
    }
    session.mutex.withLock {
        if (session.activeTransport !== planned.transport) return false
        try {
            session.records = session.repository.listByOwner(planned.owner)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            session.sendBusy = false
            session.storageHealthy = false
            session.attachmentNotice = STORAGE_NOTICE
            session.publishRecords()
            return false
        }
        session.publishRecords()
    }
    return true
}

private suspend fun applySendResponse(
    session: EngineMessageSession,
    planned: PlannedMessageSend,
    response: EngineProtocolFrame.Response,
) {
    if (response.error != null) {
        session.repository.markFailed(planned.owner, planned.messageId, session.runtime.clock())
        session.pendingSent = emptyList()
        session.records = session.repository.listByOwner(planned.owner)
        return
    }
    val sendResult = runCatching { SmsProtocolCodec.sendResult(response) }.getOrNull()
    when {
        sendResult != null && SmsProtocolCodec.isAcceptedStatusCode(sendResult.statusCode) -> {
            session.repository.bindAccepted(
                planned.owner,
                planned.messageId,
                sendResult.smsId,
                session.runtime.clock(),
            )
            replayPendingSent(session)
        }
        sendResult != null -> {
            session.repository.markFailed(planned.owner, planned.messageId, session.runtime.clock())
            session.pendingSent = emptyList()
        }
        else -> {
            session.repository.markUnknown(planned.owner, planned.messageId, session.runtime.clock())
            session.pendingSent = emptyList()
        }
    }
    session.records = session.repository.listByOwner(planned.owner)
}

private suspend fun persistUnknownOutcome(session: EngineMessageSession, planned: PlannedMessageSend) {
    session.mutex.withLock {
        if (session.activeTransport !== planned.transport) return
        persistSendOutcome(session) {
            session.repository.markUnknown(planned.owner, planned.messageId, session.runtime.clock())
            session.pendingSent = emptyList()
            session.records = session.repository.listByOwner(planned.owner)
        }
        session.pendingSent = emptyList()
        session.finishSend()
    }
}

private suspend fun persistSendOutcome(session: EngineMessageSession, block: suspend () -> Unit) {
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        session.storageHealthy = false
        session.attachmentNotice = STORAGE_SYNC_NOTICE
    }
}
