package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallDirection
import com.vowifi.sw.manager.model.CallOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CallHistoryRepositoryTest {
    @Test
    fun insertListsNewestFirst() = runTest {
        val repository = FakeCallHistoryRepository()
        repository.upsert(OWNER_A, historyWrite(correlation = CORR_A, startedAtMs = T0))
        repository.upsert(OWNER_A, historyWrite(correlation = CORR_B, startedAtMs = T0 + 5))
        val listed = repository.listByOwner(OWNER_A)
        assertEquals(listOf(T0 + 5, T0), listed.map { it.startedAtMs })
        assertEquals(listOf(CallDirection.OUTGOING, CallDirection.OUTGOING), listed.map { it.direction })
        assertEquals(listOf(CallOutcome.STARTED, CallOutcome.STARTED), listed.map { it.outcome })
        repository.close()
    }

    @Test
    fun sameOwnerAndOpaqueIdUpsertsOneRow() = runTest {
        val repository = FakeCallHistoryRepository()
        repository.upsert(OWNER_A, historyWrite(outcome = CallOutcome.STARTED))
        repository.upsert(OWNER_A, historyWrite(outcome = CallOutcome.STARTED))
        repository.upsert(
            OWNER_A,
            historyWrite(connectedAtMs = T0 + 1, outcome = CallOutcome.CONNECTED),
        )
        val listed = repository.listByOwner(OWNER_A)
        assertEquals(1, listed.size)
        assertEquals(CallOutcome.CONNECTED, listed.single().outcome)
        assertEquals(T0, listed.single().startedAtMs)
        assertEquals(T0 + 1, listed.single().connectedAtMs)
        repository.close()
    }

    @Test
    fun terminalRecordIgnoresStaleActiveUpdate() = runTest {
        val repository = FakeCallHistoryRepository()
        repository.upsert(OWNER_A, endedWrite(CORR_A, T0))
        repository.upsert(OWNER_A, historyWrite(outcome = CallOutcome.STARTED))
        repository.upsert(
            OWNER_A,
            historyWrite(connectedAtMs = T0 + 1, outcome = CallOutcome.CONNECTED),
        )
        val listed = repository.listByOwner(OWNER_A)
        assertEquals(1, listed.size)
        assertEquals(CallOutcome.COMPLETED, listed.single().outcome)
        assertEquals(T0 + 2, listed.single().endedAtMs)
        assertEquals(1, listed.single().durationSec)
        repository.close()
    }

    @Test
    fun identicalOpaqueIdIsIsolatedPerOwner() = runTest {
        val repository = FakeCallHistoryRepository()
        repository.upsert(OWNER_A, historyWrite(direction = CallDirection.INCOMING))
        repository.upsert(OWNER_B, historyWrite(direction = CallDirection.OUTGOING, peer = null))
        assertEquals(1, repository.listByOwner(OWNER_A).size)
        assertEquals(1, repository.listByOwner(OWNER_B).size)
        assertEquals(CallDirection.INCOMING, repository.listByOwner(OWNER_A).single().direction)
        assertEquals(CallDirection.OUTGOING, repository.listByOwner(OWNER_B).single().direction)
        assertEquals(SYNTH_PHONE, repository.listByOwner(OWNER_A).single().peer?.digits())
        assertNull(repository.listByOwner(OWNER_B).single().peer)
        repository.close()
    }

    @Test
    fun validPhoneOrAnonymousPeerIsStored() = runTest {
        val repository = FakeCallHistoryRepository()
        repository.upsert(OWNER_A, historyWrite(correlation = CORR_A, peer = SYNTH_PHONE_PLUS))
        repository.upsert(OWNER_A, historyWrite(correlation = CORR_B, peer = null))
        val listed = repository.listByOwner(OWNER_A)
        val withPeer = listed.single { it.startedAtMs == T0 && it.peer != null }
        val anonymous = listed.single { it.peer == null }
        assertEquals(SYNTH_PHONE, withPeer.peer?.digits())
        assertNull(anonymous.peer)
        repository.close()
    }

    @Test
    fun clearAffectsOnlyOneOwner() = runTest {
        val repository = FakeCallHistoryRepository()
        repository.upsert(OWNER_A, historyWrite(correlation = CORR_A))
        repository.upsert(OWNER_B, historyWrite(correlation = CORR_A))
        repository.clearByOwner(OWNER_A)
        assertTrue(repository.listByOwner(OWNER_A).isEmpty())
        assertEquals(1, repository.listByOwner(OWNER_B).size)
        repository.close()
    }

    @Test
    fun pruneEvictsOldestForThatOwnerOnly() = runTest {
        val repository = FakeCallHistoryRepository()
        for (index in 1..501) {
            repository.upsert(OWNER_A, endedWrite(corr(index), T0 + index))
        }
        repository.upsert(OWNER_B, endedWrite(corr(1), T0 + 1))
        val listed = repository.listByOwner(OWNER_A)
        assertEquals(500, listed.size)
        assertEquals(T0 + 501, listed.first().startedAtMs)
        assertEquals(T0 + 2, listed.last().startedAtMs)
        assertEquals(1, repository.listByOwner(OWNER_B).size)
        assertEquals(T0 + 1, repository.listByOwner(OWNER_B).single().startedAtMs)
        repository.close()
    }

    @Test
    fun storageFailureIsBoundedAndDoesNotInventRecords() = runTest {
        val repository = FakeCallHistoryRepository().also { it.failNextUpsert = true }
        val error = assertFailsWith<CallHistoryStorageException> {
            repository.upsert(OWNER_A, historyWrite())
        }
        assertEquals("CallHistoryStorageException", error.toString())
        assertEquals(CALL_HISTORY_STORAGE_NOTICE, error.message)
        assertTrue(repository.listByOwner(OWNER_A).isEmpty())
        repository.upsert(OWNER_A, endedWrite(CORR_A, T0))
        repository.failNextList = true
        assertFailsWith<CallHistoryStorageException> { repository.listByOwner(OWNER_A) }
        val kept = repository.listByOwner(OWNER_A)
        assertEquals(1, kept.size)
        assertEquals(CallOutcome.COMPLETED, kept.single().outcome)
        repository.close()
    }

    @Test
    fun repeatedListKeepsTerminalRecord() = runTest {
        val repository = FakeCallHistoryRepository()
        repository.upsert(OWNER_A, endedWrite(CORR_A, T0))
        val first = repository.listByOwner(OWNER_A)
        val second = repository.listByOwner(OWNER_A)
        assertEquals(first, second)
        assertEquals(CallOutcome.COMPLETED, second.single().outcome)
        assertEquals(T0 + 2, second.single().endedAtMs)
        repository.close()
    }
}

private fun corr(index: Int): String = "corr-${index.toString().padStart(4, '0')}"

private fun endedWrite(correlation: String, startedAtMs: Long): CallHistoryWrite = historyWrite(
    correlation = correlation,
    startedAtMs = startedAtMs,
    connectedAtMs = startedAtMs + 1,
    endedAtMs = startedAtMs + 2,
    durationSec = 1,
    outcome = CallOutcome.COMPLETED,
)
