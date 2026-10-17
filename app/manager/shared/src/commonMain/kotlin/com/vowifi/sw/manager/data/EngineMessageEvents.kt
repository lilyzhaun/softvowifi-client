package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.HistoryChangeReason
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.withLock

internal suspend fun collectMessageEvents(
    session: EngineMessageSession,
    line: MessageLiveSession,
) {
    try {
        line.transport.events.onSubscription {
            line.transport.connect()
            session.mutex.withLock {
                if (!session.isCurrent(line)) return@withLock
                line.connected = true
                refreshMessageStatus(session, line)
            }
        }.collect { frame ->
            val event = SmsProtocolCodec.decodeEvent(frame) ?: return@collect
            session.mutex.withLock {
                if (!session.isCurrent(line)) return@withLock
                when (event) {
                    is SmsWireEvent.Received -> applyReceivedMessage(session, line, event)
                    is SmsWireEvent.Sent -> applyOrBufferSent(session, line, event)
                }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        disconnectMessageSession(session, line)
    }
}

private suspend fun applyReceivedMessage(
    session: EngineMessageSession,
    line: MessageLiveSession,
    event: SmsWireEvent.Received,
) {
    var freshIncomingId: String? = null
    try {
        val result = session.repository.insertIncomingIfAbsent(
            ownerId = line.owner,
            messageId = session.nextMessageId(),
            engineSmsId = event.smsId,
            peerAddress = event.number,
            body = event.text,
            messageAtMs = SmsEventTime.millis(event.timestampSeconds, session.runtime.clock()),
            nowMs = session.runtime.clock(),
        )
        session.unreadSummary.refresh()
        line.records = session.repository.listByOwner(line.owner)
        if (result.inserted) freshIncomingId = result.messageId
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        line.storageHealthy = false
        line.attachmentNotice = STORAGE_SYNC_NOTICE
    }
    val mutation = freshIncomingId?.let { MessageHistoryMutation(
        StoredMessageConversation(line.owner, PeerNumberNormalizer.normalize(event.number)), HistoryChangeReason.LIVE_INSERT, setOf(it)) }
    session.publishRecords(line, freshIncomingId, mutation)
}

internal suspend fun applyOrBufferSent(
    session: EngineMessageSession,
    line: MessageLiveSession,
    event: SmsWireEvent.Sent,
) {
    try {
        val known = line.records.any { record ->
            record is MessageRecord.Outgoing && record.engineSmsId == event.smsId
        }
        if (known) {
            session.repository.applyOutgoingStatus(line.owner, event.smsId, event.status, session.runtime.clock())
            line.records = session.repository.listByOwner(line.owner)
            session.publishRecords(line)
            return
        }
        if (line.sendBusy) {
            line.pendingSent = line.pendingSent + event
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        line.storageHealthy = false
        line.attachmentNotice = STORAGE_SYNC_NOTICE
        session.publishRecords(line)
    }
}

internal suspend fun replayPendingSent(session: EngineMessageSession, line: MessageLiveSession) {
    val buffered = line.pendingSent
    line.pendingSent = emptyList()
    buffered.forEach { event ->
        session.repository.applyOutgoingStatus(line.owner, event.smsId, event.status, session.runtime.clock())
    }
}
