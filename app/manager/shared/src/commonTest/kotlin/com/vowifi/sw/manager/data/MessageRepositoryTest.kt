package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.model.SmsEngineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MessageRepositoryTest {
    @Test
    fun insertQueuedThenBindKeepsStableIdAndMarksAccepted() = runTest {
        val repository = FakeMessageRepository()
        repository.insertQueued(OWNER_A, MSG_OUT, SYNTH_NUMBER, SYNTH_TEXT, MESSAGE_TIME_MS)
        repository.bindAccepted(OWNER_A, MSG_OUT, MO_ID, STATUS_TIME_MS)

        val outgoing = assertIs<MessageRecord.Outgoing>(repository.listByOwner(OWNER_A).single())
        assertEquals(MSG_OUT, outgoing.id)
        assertEquals(MO_ID, outgoing.engineSmsId)
        assertEquals(OutgoingMessageStatus.ACCEPTED, outgoing.status)
        assertEquals(MESSAGE_TIME_MS, outgoing.timestampMs)
        assertEquals(STATUS_TIME_MS, repository.stored(MSG_OUT)?.statusUpdatedAtMs)
        assertNotEquals(outgoing.timestampMs, repository.stored(MSG_OUT)?.statusUpdatedAtMs)
        repository.close()
    }

    @Test
    fun insertQueuedFailureLeavesStoreEmpty() = runTest {
        val repository = FakeMessageRepository().also { it.failNextInsert = true }
        assertFailsWith<MessageStorageException> {
            repository.insertQueued(OWNER_A, MSG_OUT, SYNTH_NUMBER, SYNTH_TEXT, MESSAGE_TIME_MS)
        }
        assertEquals(STORAGE_NOTICE, MessageStorageException().message)
        assertEquals("MessageStorageException", MessageStorageException().toString())
        assertTrue(repository.listByOwner(OWNER_A).isEmpty())
        repository.close()
    }

    @Test
    fun outgoingStatusIsMonotonicAndDeliveryStaysOutgoing() = runTest {
        val repository = FakeMessageRepository()
        repository.insertQueued(OWNER_A, MSG_OUT, SYNTH_NUMBER, SYNTH_TEXT, MESSAGE_TIME_MS)
        repository.bindAccepted(OWNER_A, MSG_OUT, MO_ID, MESSAGE_TIME_MS)
        repository.applyOutgoingStatus(OWNER_A, MO_ID, SmsEngineStatus.DELIVERED, STATUS_TIME_MS)
        repository.applyOutgoingStatus(OWNER_A, MO_ID, SmsEngineStatus.DELIVERED, STATUS_TIME_MS + 1)
        repository.applyOutgoingStatus(OWNER_A, MO_ID, SmsEngineStatus.FAILED, STATUS_TIME_MS + 2)

        val records = repository.listByOwner(OWNER_A)
        val outgoing = assertIs<MessageRecord.Outgoing>(records.single())
        assertEquals(OutgoingMessageStatus.DELIVERY_REPORTED, outgoing.status)
        assertEquals(SmsEngineStatus.DELIVERED, outgoing.engineStatus)
        assertFalse(outgoing.isLocalMtReceipt)
        assertTrue(records.none { it is MessageRecord.Incoming })
        assertEquals(STATUS_TIME_MS, repository.stored(MSG_OUT)?.statusUpdatedAtMs)
        repository.close()
    }

    @Test
    fun incomingInsertIfAbsentIsTheOnlyIncomingProducer() = runTest {
        val repository = FakeMessageRepository()
        repository.insertIncomingIfAbsent(
            OWNER_A, MSG_IN, MT_ID, SYNTH_NUMBER, SYNTH_TEXT, MESSAGE_TIME_MS, STATUS_TIME_MS,
        )
        repository.insertIncomingIfAbsent(
            OWNER_A, "msg-in-2", MT_ID, SYNTH_PEER, "synth-two", MESSAGE_TIME_MS + 1, STATUS_TIME_MS + 1,
        )
        repository.applyOutgoingStatus(OWNER_A, MT_ID, SmsEngineStatus.DELIVERED, STATUS_TIME_MS)

        val incoming = assertIs<MessageRecord.Incoming>(repository.listByOwner(OWNER_A).single())
        assertEquals(MSG_IN, incoming.id)
        assertEquals(MT_ID, incoming.engineSmsId)
        assertEquals(SYNTH_NUMBER, incoming.peerLabel)
        assertEquals(SYNTH_TEXT, incoming.preview)
        assertTrue(incoming.isLocalMtReceipt)
        assertFalse(incoming.isRead)
        assertNull(incoming.readAtMs)
        repository.close()
    }

    @Test
    fun markFailedUnknownAndInterruptOnlyAffectQueued() = runTest {
        val repository = FakeMessageRepository()
        repository.insertQueued(OWNER_A, MSG_OUT, SYNTH_NUMBER, SYNTH_TEXT, MESSAGE_TIME_MS)
        repository.markFailed(OWNER_A, MSG_OUT, STATUS_TIME_MS)
        val failed = assertIs<MessageRecord.Outgoing>(repository.listByOwner(OWNER_A).single())
        assertEquals(OutgoingMessageStatus.FAILED, failed.status)
        assertEquals(SmsEngineStatus.FAILED, failed.engineStatus)

        repository.insertQueued(OWNER_A, "msg-out-2", SYNTH_PEER, "synth-two", MESSAGE_TIME_MS)
        repository.markUnknown(OWNER_A, "msg-out-2", STATUS_TIME_MS)
        val unknown = assertIs<MessageRecord.Outgoing>(
            repository.listByOwner(OWNER_A).first { it.id == "msg-out-2" },
        )
        assertEquals(OutgoingMessageStatus.UNKNOWN, unknown.status)
        assertNull(unknown.engineStatus)

        repository.insertQueued(OWNER_A, "msg-out-3", SYNTH_NUMBER, "synth-three", MESSAGE_TIME_MS)
        repository.interruptLeftoverQueued(OWNER_A, STATUS_TIME_MS)
        val interrupted = assertIs<MessageRecord.Outgoing>(
            repository.listByOwner(OWNER_A).first { it.id == "msg-out-3" },
        )
        assertEquals(OutgoingMessageStatus.UNKNOWN, interrupted.status)
        assertEquals(OutgoingMessageStatus.FAILED, failed.status)
        repository.close()
    }

    @Test
    fun ownersLastOwnerProbeAndCloseAreIsolated() = runTest {
        val repository = FakeMessageRepository()
        repository.insertQueued(OWNER_A, MSG_OUT, SYNTH_NUMBER, SYNTH_TEXT, MESSAGE_TIME_MS)
        repository.insertQueued(OWNER_B, "msg-b", SYNTH_PEER, "synth-two", MESSAGE_TIME_MS)
        repository.setLastOwner(OWNER_B)
        assertEquals(OWNER_B, repository.lastOwner())
        assertEquals(listOf(MSG_OUT), repository.listByOwner(OWNER_A).map { it.id })
        assertEquals(listOf("msg-b"), repository.listByOwner(OWNER_B).map { it.id })

        val probe = ByteArray(32) { 7 }
        repository.writeHmacProbe(probe)
        assertTrue(probe.contentEquals(repository.readHmacProbe()))
        repository.close()
        assertTrue(repository.closed)
        assertFailsWith<IllegalStateException> { repository.lastOwner() }
    }

    @Test
    fun listByOwnerSortsByMessageTimeThenId() = runTest {
        val repository = FakeMessageRepository()
        repository.insertQueued(OWNER_A, "msg-c", SYNTH_NUMBER, SYNTH_TEXT, MESSAGE_TIME_MS + 1)
        repository.insertQueued(OWNER_A, "msg-b", SYNTH_PEER, "synth-two", MESSAGE_TIME_MS)
        repository.insertQueued(OWNER_A, "msg-a", SYNTH_NUMBER, "synth-three", MESSAGE_TIME_MS)
        assertEquals(listOf("msg-a", "msg-b", "msg-c"), repository.listByOwner(OWNER_A).map { it.id })
        repository.close()
    }

    @Test
    fun hmacProbeRejectsInvalidBase64AndCredentialProtectedStorage() {
        val probe = ByteArray(32) { 9 }
        val encoded = HmacProbeCodec.encode(probe)
        assertTrue(probe.contentEquals(HmacProbeCodec.decode(encoded)))
        assertFailsWith<MessageStorageException> { HmacProbeCodec.decode("!!!") }
        assertFailsWith<MessageStorageException> { HmacProbeCodec.decode("not-base64") }
        assertEquals("MessageStorageException", runCatching { HmacProbeCodec.decode("!!!") }.exceptionOrNull()?.toString())
        requireCredentialEncryptedStorage(false)
        assertFailsWith<MessageStorageException> { requireCredentialEncryptedStorage(true) }
    }
}

private const val MSG_OUT = "msg-out"
private const val MSG_IN = "msg-in"
private const val SYNTH_NUMBER = "+155501"
private const val SYNTH_PEER = "+155502"
private const val SYNTH_TEXT = "synth-one"
private const val MO_ID = "mo-synth-1"
private const val MT_ID = "mt-synth-1"
private const val MESSAGE_TIME_MS = 1_700_000_000_000L
private const val STATUS_TIME_MS = 1_700_000_000_500L
