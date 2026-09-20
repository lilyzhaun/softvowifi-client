package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.model.SmsEngineStatus

internal data class FakeStoredMessage(
    val id: String,
    val ownerId: SimOwnerId,
    val incoming: Boolean,
    val peerAddress: String,
    val conversationKey: String,
    val body: String,
    val messageAtMs: Long,
    val engineSmsId: String?,
    val outgoingStatus: OutgoingMessageStatus?,
    val engineStatus: SmsEngineStatus?,
    val statusUpdatedAtMs: Long,
    val failureReason: String?,
    val isRead: Boolean,
    val readAtMs: Long?,
) {
    override fun toString(): String = "Stored"

    fun toRecord(): MessageRecord = if (incoming) {
        MessageRecord.Incoming(
            id = id,
            peerLabel = peerAddress,
            preview = body,
            timestampMs = messageAtMs,
            engineSmsId = engineSmsId,
            isRead = isRead,
            readAtMs = readAtMs,
        )
    } else {
        MessageRecord.Outgoing(
            id = id,
            peerLabel = peerAddress,
            preview = body,
            timestampMs = messageAtMs,
            status = outgoingStatus ?: OutgoingMessageStatus.QUEUED,
            engineSmsId = engineSmsId,
            engineStatus = engineStatus,
        )
    }
}

internal fun SmsEngineStatus.toUiStatus(): OutgoingMessageStatus = when (this) {
    SmsEngineStatus.ACCEPTED,
    SmsEngineStatus.PENDING,
    -> OutgoingMessageStatus.ACCEPTED
    SmsEngineStatus.DELIVERED,
    SmsEngineStatus.FORWARDED,
    SmsEngineStatus.REPLACED,
    -> OutgoingMessageStatus.DELIVERY_REPORTED
    SmsEngineStatus.FAILED -> OutgoingMessageStatus.FAILED
}
