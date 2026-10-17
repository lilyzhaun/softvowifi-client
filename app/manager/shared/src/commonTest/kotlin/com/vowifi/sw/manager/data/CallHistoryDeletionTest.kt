@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallHistoryDeleteResult
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
    // 规范§5.7 E2：删除失败保持同一Ready及原行，经真实await FAILED就地反馈，不使整页失效。
    @Test fun deletionFailureReturnsFailedKeepsSameReadyAndAllowsRealRetry() = runTest(UnconfinedTestDispatcher()) {
        val saved = FakeCallHistoryRepository()
        saved.upsert(OWNER_A, historyWrite())
        var failNextDelete = true
        var deleteCalls = 0
        val repo = object : CallHistoryRepository by saved {
            override suspend fun deleteByIds(ownerId: SimOwnerId, ids: Set<com.vowifi.sw.manager.model.CallHistoryRecordId>) {
                deleteCalls++
                if (failNextDelete) {
                    failNextDelete = false
                    throw CallHistoryStorageException()
                }
                saved.deleteByIds(ownerId, ids)
            }
        }
        val store = CallHistoryStore(repo, coroutineContext)
        try {
            store.select(OWNER_A)
            val before = assertIs<CallHistoryState.Ready>(store.state.value)
            val ids = before.records.mapNotNull { it.recordId }.toSet()
            assertTrue(ids.isNotEmpty())
            assertEquals(CallHistoryDeleteResult.FAILED, store.deleteAndAwait(before, ids))
            assertEquals(1, deleteCalls, "失败回执必须来自唯一一次真实repository调用")
            assertSame(before, store.state.value)
            assertSame(before, store.historySnapshot.value.view)
            assertTrue(store.historySnapshot.value.deletePending.isEmpty())
            assertEquals(before.records, saved.listByOwner(OWNER_A))
            assertEquals(1, saved.listByOwner(OWNER_A).size)
            assertEquals(CallHistoryDeleteResult.COMMITTED, store.deleteAndAwait(before, ids))
            assertEquals(2, deleteCalls, "同一原Ready的显式重试只增加一次真实删除，不与Unit双发")
            assertTrue(saved.listByOwner(OWNER_A).isEmpty())
            assertIs<CallHistoryState.Empty>(store.state.value)
            assertIs<CallHistoryState.Empty>(store.historySnapshot.value.view)
            assertTrue(store.historySnapshot.value.deletePending.isEmpty())
        } finally { store.close() }
    }
}
