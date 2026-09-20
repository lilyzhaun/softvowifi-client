package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.model.SmsEngineStatus

internal object SmsSessionReducer {
    fun queue(
        records: List<MessageRecord>,
        localId: String,
        number: String,
        text: String,
        nowMs: Long,
    ): List<MessageRecord> = records + MessageRecord.Outgoing(
        id = localId,
        peerLabel = number,
        preview = text,
        timestampMs = nowMs,
        status = OutgoingMessageStatus.QUEUED,
    )

    fun bind(records: List<MessageRecord>, localId: String, smsId: String): List<MessageRecord> =
        records.map { record ->
            if (record is MessageRecord.Outgoing && record.id == localId) {
                record.copy(
                    engineSmsId = smsId,
                    status = if (record.status == OutgoingMessageStatus.QUEUED) {
                        OutgoingMessageStatus.ACCEPTED
                    } else {
                        record.status
                    },
                )
            } else {
                record
            }
        }

    fun failSend(records: List<MessageRecord>, localId: String): List<MessageRecord> =
        records.map { record ->
            if (record is MessageRecord.Outgoing && record.id == localId) {
                record.copy(
                    status = OutgoingMessageStatus.FAILED,
                    engineStatus = SmsEngineStatus.FAILED,
                )
            } else {
                record
            }
        }

    fun applyEvent(
        records: List<MessageRecord>,
        event: SmsWireEvent,
        nowMs: Long,
    ): List<MessageRecord> = when (event) {
        is SmsWireEvent.Received -> records + incoming(event, nowMs)
        is SmsWireEvent.Sent -> applySent(records, event)
    }

    private fun incoming(event: SmsWireEvent.Received, nowMs: Long): MessageRecord.Incoming =
        MessageRecord.Incoming(
            id = event.smsId,
            peerLabel = event.number,
            preview = event.text,
            timestampMs = event.timestampSeconds?.times(1_000) ?: nowMs,
        )

    private fun applySent(
        records: List<MessageRecord>,
        event: SmsWireEvent.Sent,
    ): List<MessageRecord> {
        val current = records.firstOrNull { record ->
            record is MessageRecord.Outgoing && record.engineSmsId == event.smsId
        } ?: return records
        if (current !is MessageRecord.Outgoing) return records
        val nextStatus = SmsStatusMachine.reduce(current.engineStatus, event.status)
        if (nextStatus == current.engineStatus) return records
        val updated = current.copy(
            status = nextStatus.toUiStatus(),
            engineStatus = nextStatus,
        )
        return records.map { record -> if (record === current) updated else record }
    }
}

private fun SmsEngineStatus.toUiStatus(): OutgoingMessageStatus = when (this) {
    SmsEngineStatus.ACCEPTED,
    SmsEngineStatus.PENDING,
    -> OutgoingMessageStatus.ACCEPTED
    SmsEngineStatus.DELIVERED,
    SmsEngineStatus.FORWARDED,
    SmsEngineStatus.REPLACED,
    -> OutgoingMessageStatus.DELIVERY_REPORTED
    SmsEngineStatus.FAILED -> OutgoingMessageStatus.FAILED
}
