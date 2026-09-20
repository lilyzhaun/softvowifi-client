package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.model.SmsEngineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmsSessionReducerTest {
    @Test
    fun bindKeepsStableAppIdAndMarksAcceptedWithoutEngineEvent() {
        val queued = SmsSessionReducer.queue(
            records = emptyList(),
            localId = LOCAL_ID,
            number = NUMBER,
            text = TEXT,
            nowMs = MESSAGE_TIME_MS,
        )

        val outgoing = assertIs<MessageRecord.Outgoing>(
            SmsSessionReducer.bind(queued, LOCAL_ID, ENGINE_ID).single(),
        )

        assertEquals(LOCAL_ID, outgoing.id)
        assertEquals(ENGINE_ID, outgoing.engineSmsId)
        assertEquals(OutgoingMessageStatus.ACCEPTED, outgoing.status)
        assertEquals(null, outgoing.engineStatus)
        assertEquals(MESSAGE_TIME_MS, outgoing.timestampMs)
        assertFalse(outgoing.isLocalMtReceipt)
    }

    @Test
    fun pendingThenAcceptedDoesNotRollBack() {
        val pending = applySent(boundOutgoing(), SmsEngineStatus.PENDING)
        val accepted = applySent(pending, SmsEngineStatus.ACCEPTED)
        val outgoing = assertIs<MessageRecord.Outgoing>(accepted.single())

        assertEquals(SmsEngineStatus.PENDING, outgoing.engineStatus)
        assertEquals(OutgoingMessageStatus.ACCEPTED, outgoing.status)
    }

    @Test
    fun firstTerminalStatusAbsorbsConflictsAndDuplicateEvents() {
        val delivered = applySent(boundOutgoing(), SmsEngineStatus.DELIVERED)
        val duplicate = applySent(delivered, SmsEngineStatus.DELIVERED)
        val conflict = applySent(duplicate, SmsEngineStatus.FAILED)
        val outgoing = assertIs<MessageRecord.Outgoing>(conflict.single())

        assertEquals(delivered, duplicate)
        assertEquals(duplicate, conflict)
        assertEquals(SmsEngineStatus.DELIVERED, outgoing.engineStatus)
        assertEquals(OutgoingMessageStatus.DELIVERY_REPORTED, outgoing.status)
    }

    @Test
    fun acceptedCanSkipAheadDirectlyToDelivery() {
        val delivered = applySent(boundOutgoing(), SmsEngineStatus.DELIVERED)
        val outgoing = assertIs<MessageRecord.Outgoing>(delivered.single())

        assertEquals(SmsEngineStatus.DELIVERED, outgoing.engineStatus)
        assertEquals(OutgoingMessageStatus.DELIVERY_REPORTED, outgoing.status)
    }

    @Test
    fun sentEventKeepsOriginalMessageTime() {
        val delivered = SmsSessionReducer.applyEvent(
            records = boundOutgoing(),
            event = SmsWireEvent.Sent(
                smsId = ENGINE_ID,
                status = SmsEngineStatus.DELIVERED,
                timestampSeconds = EVENT_TIME_SECONDS,
            ),
            nowMs = EVENT_TIME_SECONDS * 1_000,
        )
        val outgoing = assertIs<MessageRecord.Outgoing>(delivered.single())

        assertEquals(MESSAGE_TIME_MS, outgoing.timestampMs)
    }

    @Test
    fun deliveryReportUpdatesOutgoingWithoutCreatingIncoming() {
        val delivered = applySent(boundOutgoing(), SmsEngineStatus.DELIVERED)

        assertEquals(1, delivered.size)
        val outgoing = assertIs<MessageRecord.Outgoing>(delivered.single())
        assertFalse(outgoing.isLocalMtReceipt)
        assertTrue(delivered.none { it is MessageRecord.Incoming })
    }

    private fun boundOutgoing(): List<MessageRecord> = listOf(
        MessageRecord.Outgoing(
            id = LOCAL_ID,
            engineSmsId = ENGINE_ID,
            peerLabel = NUMBER,
            preview = TEXT,
            timestampMs = MESSAGE_TIME_MS,
            status = OutgoingMessageStatus.ACCEPTED,
        ),
    )

    private fun applySent(
        records: List<MessageRecord>,
        status: SmsEngineStatus,
    ): List<MessageRecord> = SmsSessionReducer.applyEvent(
        records = records,
        event = SmsWireEvent.Sent(smsId = ENGINE_ID, status = status),
        nowMs = EVENT_TIME_SECONDS * 1_000,
    )
}

private const val LOCAL_ID = "local-synth-1"
private const val ENGINE_ID = "mo-synth-1"
private const val NUMBER = "+155501"
private const val TEXT = "synth-one"
private const val MESSAGE_TIME_MS = 1_700_000_000_000L
private const val EVENT_TIME_SECONDS = 1_755_000_000L
