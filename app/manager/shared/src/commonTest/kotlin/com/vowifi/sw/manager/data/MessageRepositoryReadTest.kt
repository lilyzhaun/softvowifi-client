package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.model.SmsEngineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MessageRepositoryReadTest {
    @Test
    fun incomingConstructorDefaultsToUnread() {
        val incoming = MessageRecord.Incoming(MSG_IN, SYNTH_NUMBER, SYNTH_TEXT, MESSAGE_TIME_MS)
        assertFalse(incoming.isRead)
        assertNull(incoming.readAtMs)
        assertEquals("Incoming(id=$MSG_IN, isRead=false)", incoming.toString())
        assertFalse(incoming.toString().contains(SYNTH_NUMBER))
        assertFalse(incoming.toString().contains(SYNTH_TEXT))
        val outgoing = MessageRecord.Outgoing(
            MSG_OUT,
            SYNTH_NUMBER,
            SYNTH_TEXT,
            MESSAGE_TIME_MS,
            OutgoingMessageStatus.QUEUED,
        )
        assertEquals("Outgoing(id=$MSG_OUT, status=QUEUED)", outgoing.toString())
        assertFalse(outgoing.toString().contains(SYNTH_TEXT))
    }

    @Test
    fun newIncomingStartsUnreadAndOutgoingNeverCountsAsUnread() = runTest {
        val repository = FakeMessageRepository()
        val inserted = repository.insertIncomingIfAbsent(
            OWNER_A, MSG_IN, MT_ID, SYNTH_NUMBER, SYNTH_TEXT, MESSAGE_TIME_MS, STATUS_TIME_MS,
        )
        repository.insertQueued(OWNER_A, MSG_OUT, SYNTH_NUMBER, SYNTH_TEXT, MESSAGE_TIME_MS)
        repository.bindAccepted(OWNER_A, MSG_OUT, MO_ID, STATUS_TIME_MS)
        repository.applyOutgoingStatus(OWNER_A, MO_ID, SmsEngineStatus.DELIVERED, STATUS_TIME_MS)

        assertTrue(inserted.inserted)
        assertEquals(MSG_IN, inserted.messageId)
        assertEquals("IncomingInsertResult(inserted=true)", inserted.toString())
        val records = repository.listByOwner(OWNER_A)
        val incoming = assertIs<MessageRecord.Incoming>(records.first { it.id == MSG_IN })
        val outgoing = assertIs<MessageRecord.Outgoing>(records.first { it.id == MSG_OUT })
        assertFalse(incoming.isRead)
        assertNull(incoming.readAtMs)
        assertEquals(OutgoingMessageStatus.DELIVERY_REPORTED, outgoing.status)
        assertFalse(outgoing.isLocalMtReceipt)
        repository.close()
    }

    @Test
    fun duplicateIncomingKeepsReadAndReportsNotInserted() = runTest {
        val repository = FakeMessageRepository()
        repository.insertIncomingIfAbsent(
            OWNER_A, MSG_IN, MT_ID, SYNTH_NUMBER, SYNTH_TEXT, MESSAGE_TIME_MS, STATUS_TIME_MS,
        )
        val request = checkNotNull(
            parseObservedIncomingRead(OWNER_WIRE_A, "155501", listOf(MSG_IN)),
        )
        val marked = repository.markIncomingRead(request, STATUS_TIME_MS)
        assertEquals(listOf(MSG_IN), marked.updatedIds)
        val duplicate = repository.insertIncomingIfAbsent(
            OWNER_A, "msg-in-2", MT_ID, SYNTH_PEER, "synth-two", MESSAGE_TIME_MS + 1, STATUS_TIME_MS + 1,
        )
        assertFalse(duplicate.inserted)
        assertEquals(MSG_IN, duplicate.messageId)
        val incoming = assertIs<MessageRecord.Incoming>(repository.listByOwner(OWNER_A).single())
        assertEquals(MSG_IN, incoming.id)
        assertTrue(incoming.isRead)
        assertEquals(STATUS_TIME_MS, incoming.readAtMs)
        val again = repository.markIncomingRead(request, STATUS_TIME_MS + 9)
        assertTrue(again.updatedIds.isEmpty())
        assertEquals(STATUS_TIME_MS, repository.stored(MSG_IN)?.readAtMs)
        repository.close()
    }

    @Test
    fun markObservedIdsLeavesLaterArrivalUnreadAndIgnoresWrongOwner() = runTest {
        val repository = FakeMessageRepository()
        repository.insertIncomingIfAbsent(
            OWNER_A, MSG_IN, MT_ID, SYNTH_NUMBER, SYNTH_TEXT, MESSAGE_TIME_MS, STATUS_TIME_MS,
        )
        val snapshot = checkNotNull(
            parseObservedIncomingRead(OWNER_WIRE_A, "155501", listOf(MSG_IN)),
        )
        assertEquals(1, repository.markIncomingRead(snapshot, STATUS_TIME_MS).updatedCount)

        val later = repository.insertIncomingIfAbsent(
            OWNER_A, "msg-in-later", "mt-synth-2", SYNTH_NUMBER, "synth-later", MESSAGE_TIME_MS + 2, STATUS_TIME_MS + 2,
        )
        assertTrue(later.inserted)
        val afterSnapshot = repository.markIncomingRead(snapshot, STATUS_TIME_MS + 3)
        assertTrue(afterSnapshot.updatedIds.isEmpty())
        val laterRecord = assertIs<MessageRecord.Incoming>(
            repository.listByOwner(OWNER_A).first { it.id == "msg-in-later" },
        )
        assertFalse(laterRecord.isRead)

        val otherOwner = checkNotNull(
            parseObservedIncomingRead(OWNER_WIRE_B, "155501", listOf("msg-in-later")),
        )
        assertEquals(0, repository.markIncomingRead(otherOwner, STATUS_TIME_MS + 4).updatedCount)
        val otherConversation = checkNotNull(
            parseObservedIncomingRead(OWNER_WIRE_A, "155502", listOf("msg-in-later")),
        )
        assertEquals(0, repository.markIncomingRead(otherConversation, STATUS_TIME_MS + 4).updatedCount)
        assertFalse(
            assertIs<MessageRecord.Incoming>(
                repository.listByOwner(OWNER_A).first { it.id == "msg-in-later" },
            ).isRead,
        )
        val emptyObserved = checkNotNull(
            parseObservedIncomingRead(OWNER_WIRE_A, "155501", emptyList()),
        )
        assertEquals(0, repository.markIncomingRead(emptyObserved, STATUS_TIME_MS + 5).updatedCount)
        repository.close()
    }

    @Test
    fun parseObservedIncomingReadRejectsUntrustedOwnerAndControlChars() {
        assertNull(parseObservedIncomingRead("sim-1", "155501", listOf(MSG_IN)))
        assertNull(parseObservedIncomingRead(OWNER_WIRE_A, "", listOf(MSG_IN)))
        assertNull(parseObservedIncomingRead(OWNER_WIRE_A, "155501\u0000", listOf(MSG_IN)))
        val parsed = checkNotNull(
            parseObservedIncomingRead(OWNER_WIRE_A, "155501", listOf("", MSG_IN, MSG_IN, "bad\u0007")),
        )
        assertEquals(OWNER_A, parsed.ownerId)
        assertEquals(listOf(MSG_IN), parsed.observedMessageIds)
        assertEquals("ObservedIncomingReadRequest(observedCount=1)", parsed.toString())
        assertEquals("IncomingReadUpdate(updatedCount=0)", IncomingReadUpdate.of(emptyList()).toString())
    }

    @Test
    fun mutatingOriginalIdsAfterParseDoesNotExpandScope() = runTest {
        val raw = mutableListOf(MSG_IN)
        val parsed = checkNotNull(parseObservedIncomingRead(OWNER_WIRE_A, "155501", raw))
        raw += "msg-extra"
        parsed.observedMessageIds.toMutableList() += "msg-extra-2"
        assertEquals(listOf(MSG_IN), parsed.observedMessageIds)
        val repository = FakeMessageRepository()
        repository.insertIncomingIfAbsent(
            OWNER_A, MSG_IN, MT_ID, SYNTH_NUMBER, SYNTH_TEXT, MESSAGE_TIME_MS, STATUS_TIME_MS,
        )
        repository.insertIncomingIfAbsent(
            OWNER_A, "msg-extra", "mt-extra", SYNTH_NUMBER, "synth-extra", MESSAGE_TIME_MS + 1, STATUS_TIME_MS,
        )
        val marked = repository.markIncomingRead(parsed, STATUS_TIME_MS)
        val leaked = marked.updatedIds.toMutableList()
        leaked += "msg-extra"
        assertEquals(listOf(MSG_IN), marked.updatedIds)
        assertTrue(assertIs<MessageRecord.Incoming>(repository.listByOwner(OWNER_A).first { it.id == MSG_IN }).isRead)
        assertFalse(assertIs<MessageRecord.Incoming>(repository.listByOwner(OWNER_A).first { it.id == "msg-extra" }).isRead)
        repository.close()
    }

    @Test
    fun markReadStorageErrorPreservesUnreadRows() = runTest {
        val repository = FakeMessageRepository().also { it.failNextMarkRead = true }
        repository.insertIncomingIfAbsent(
            OWNER_A, MSG_IN, MT_ID, SYNTH_NUMBER, SYNTH_TEXT, MESSAGE_TIME_MS, STATUS_TIME_MS,
        )
        val request = checkNotNull(
            parseObservedIncomingRead(OWNER_WIRE_A, "155501", listOf(MSG_IN)),
        )
        assertFailsWith<MessageStorageException> {
            repository.markIncomingRead(request, STATUS_TIME_MS)
        }
        val incoming = assertIs<MessageRecord.Incoming>(repository.listByOwner(OWNER_A).single())
        assertFalse(incoming.isRead)
        assertEquals("MessageStorageException", MessageStorageException().toString())
        repository.close()
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
