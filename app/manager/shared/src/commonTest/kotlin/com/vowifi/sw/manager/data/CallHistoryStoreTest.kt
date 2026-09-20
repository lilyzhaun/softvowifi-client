@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallHistoryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class CallHistoryStoreTest {
    @Test
    fun ownerSwitchInvalidatesConfirmationEvenWithIdenticalRecords() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeCallHistoryRepository()
        val write = historyWrite()
        repository.upsert(OWNER_A, write)
        repository.upsert(OWNER_B, write)
        val store = CallHistoryStore(repository, coroutineContext)
        store.select(OWNER_A)
        val confirmation = assertIs<CallHistoryState.Ready>(store.state.value)
        store.select(OWNER_B)

        store.clear(confirmation)

        assertEquals(1, repository.listByOwner(OWNER_A).size)
        assertEquals(1, repository.listByOwner(OWNER_B).size)
        store.close()
    }

    @Test
    fun clearKeepsOtherOwnerRecords() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeCallHistoryRepository()
        repository.upsert(OWNER_A, historyWrite())
        repository.upsert(OWNER_B, historyWrite())
        val store = CallHistoryStore(repository, coroutineContext)
        store.select(OWNER_A)
        val confirmation = assertIs<CallHistoryState.Ready>(store.state.value)

        store.clear(confirmation)

        assertEquals(0, repository.listByOwner(OWNER_A).size)
        assertEquals(1, repository.listByOwner(OWNER_B).size)
        assertIs<CallHistoryState.Empty>(store.state.value)
        store.close()
    }

    @Test
    fun clearStorageFailureIsVisibleAndPreservesRecords() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeCallHistoryRepository()
        repository.upsert(OWNER_A, historyWrite())
        val failing = object : CallHistoryRepository by repository {
            override suspend fun clearByOwner(ownerId: SimOwnerId) { throw CallHistoryStorageException() }
        }
        val store = CallHistoryStore(failing, coroutineContext)
        store.select(OWNER_A)
        val confirmation = assertIs<CallHistoryState.Ready>(store.state.value)

        store.clear(confirmation)

        assertIs<CallHistoryState.Failure>(store.state.value)
        assertEquals(1, repository.listByOwner(OWNER_A).size)
        store.close()
    }
}
