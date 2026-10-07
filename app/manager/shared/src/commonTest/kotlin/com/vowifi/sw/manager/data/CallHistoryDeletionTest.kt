@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallHistoryState
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CallHistoryDeletionTest {
    @Test fun deletingOneOfIdenticalCallsUsesIdAndKeepsSiblingOwner() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeCallHistoryRepository()
        repo.upsert(OWNER_A, historyWrite(correlation = "first"))
        repo.upsert(OWNER_A, historyWrite(correlation = "second"))
        repo.upsert(OWNER_B, historyWrite(correlation = "first"))
        val store = CallHistoryStore(repo, coroutineContext)
        try {
            store.select(OWNER_A)
            val snapshot = assertIs<CallHistoryState.Ready>(store.state.value)
            val id = assertNotNull(snapshot.records.first().recordId)
            store.delete(snapshot, setOf(id))
            assertEquals(1, repo.listByOwner(OWNER_A).size)
            assertEquals(1, repo.listByOwner(OWNER_B).size)
            assertTrue(repo.listByOwner(OWNER_A).none { it.recordId == id })
        } finally { store.close() }
    }
    @Test fun oldConfirmationCannotDeleteAfterOwnerSwitch() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeCallHistoryRepository()
        repo.upsert(OWNER_A, historyWrite())
        repo.upsert(OWNER_B, historyWrite())
        val store = CallHistoryStore(repo, coroutineContext)
        try {
            store.select(OWNER_A)
            val before = assertIs<CallHistoryState.Ready>(store.state.value)
            store.select(OWNER_B)
            store.delete(before, before.records.mapNotNull { it.recordId }.toSet())
            assertEquals(1, repo.listByOwner(OWNER_A).size)
            assertEquals(1, repo.listByOwner(OWNER_B).size)
        } finally { store.close() }
    }
    @Test fun newRecordInvalidatesConfirmationInsteadOfDeletingNewlyArrivedCall() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeCallHistoryRepository()
        repo.upsert(OWNER_A, historyWrite())
        val store = CallHistoryStore(repo, coroutineContext)
        try {
            store.select(OWNER_A)
            val before = assertIs<CallHistoryState.Ready>(store.state.value)
            repo.upsert(OWNER_A, historyWrite(correlation = "new"))
            store.select(OWNER_A)
            store.delete(before, before.records.mapNotNull { it.recordId }.toSet())
            assertEquals(2, repo.listByOwner(OWNER_A).size)
        } finally { store.close() }
    }
    @Test fun deletionFailureIsVisibleWithoutDiscardingStoredRecords() = runTest(UnconfinedTestDispatcher()) {
        val saved = FakeCallHistoryRepository()
        saved.upsert(OWNER_A, historyWrite())
        val repo = object : CallHistoryRepository by saved {
            override suspend fun deleteByIds(ownerId: SimOwnerId, ids: Set<com.vowifi.sw.manager.model.CallHistoryRecordId>) {
                throw CallHistoryStorageException()
            }
        }
        val store = CallHistoryStore(repo, coroutineContext)
        try {
            store.select(OWNER_A)
            val before = assertIs<CallHistoryState.Ready>(store.state.value)
            store.delete(before, before.records.mapNotNull { it.recordId }.toSet())
            assertIs<CallHistoryState.Failure>(store.state.value)
            assertEquals(1, saved.listByOwner(OWNER_A).size)
        } finally { store.close() }
    }
}
