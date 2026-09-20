package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.withLock

internal suspend fun collectMessageEvents(
    session: EngineMessageSession,
    transport: EngineMessageTransport,
) {
    try {
        transport.events.onSubscription { transport.connect() }.collect { frame ->
            val event = SmsProtocolCodec.decodeEvent(frame) ?: return@collect
            session.mutex.withLock {
                if (session.closed || session.activeTransport !== transport) return@withLock
                val owner = session.activeOwner ?: return@withLock
                when (event) {
                    is SmsWireEvent.Received -> applyReceivedMessage(session, owner, event)
                    is SmsWireEvent.Sent -> applyOrBufferSent(session, owner, event)
                }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        session.mutex.withLock {
            if (!session.closed && session.activeTransport === transport) {
                session.fail(MessageUiState.Failure(MESSAGE_CONNECT_FAILURE))
            }
        }
    }
}

private suspend fun applyReceivedMessage(
    session: EngineMessageSession,
    owner: SimOwnerId,
    event: SmsWireEvent.Received,
) {
    var freshIncomingId: String? = null
    try {
        val result = session.repository.insertIncomingIfAbsent(
            ownerId = owner,
            messageId = session.nextMessageId(),
            engineSmsId = event.smsId,
            peerAddress = event.number,
            body = event.text,
            messageAtMs = SmsEventTime.millis(event.timestampSeconds, session.runtime.clock()),
            nowMs = session.runtime.clock(),
        )
        session.unreadSummary.refresh()
        session.records = session.repository.listByOwner(owner)
        if (result.inserted) freshIncomingId = result.messageId
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        session.storageHealthy = false
        session.attachmentNotice = STORAGE_SYNC_NOTICE
    }
    session.publishRecords(freshIncomingId)
}

internal suspend fun applyOrBufferSent(
    session: EngineMessageSession,
    owner: SimOwnerId,
    event: SmsWireEvent.Sent,
) {
    try {
        val known = session.records.any { record ->
            record is MessageRecord.Outgoing && record.engineSmsId == event.smsId
        }
        if (known) {
            session.repository.applyOutgoingStatus(owner, event.smsId, event.status, session.runtime.clock())
            session.records = session.repository.listByOwner(owner)
            session.publishRecords()
            return
        }
        if (session.sendBusy) {
            session.pendingSent = session.pendingSent + event
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        session.storageHealthy = false
        session.attachmentNotice = STORAGE_SYNC_NOTICE
        session.publishRecords()
    }
}

internal suspend fun replayPendingSent(session: EngineMessageSession) {
    val owner = session.activeOwner ?: return
    val buffered = session.pendingSent
    session.pendingSent = emptyList()
    buffered.forEach { event ->
        session.repository.applyOutgoingStatus(owner, event.smsId, event.status, session.runtime.clock())
    }
}
