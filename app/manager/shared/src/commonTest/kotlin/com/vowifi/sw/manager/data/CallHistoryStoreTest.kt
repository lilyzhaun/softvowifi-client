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
    fun historyNamesComeFromOwnerBoundLabelsWithoutChangingRecordsOrSelection() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeCallHistoryRepository()
        val terminal = historyWrite(endedAtMs = T0 + 1, outcome = com.vowifi.sw.manager.model.CallOutcome.COMPLETED)
        repository.upsert(OWNER_A, terminal)
        repository.upsert(OWNER_B, terminal)
        val before = repository.listByOwner(OWNER_A)
        val titles = mapOf(OWNER_A to "T-Mobile · •••• 1234", OWNER_B to "T-Mobile · •••• 5678")
        val store = CallHistoryStore(repository, coroutineContext, lineLabels = { owners -> titles.filterKeys { it in owners } })
        try {
            store.select(OWNER_A)
            assertEquals(titles, store.owners.value.associate { it.owner to it.label })
            assertEquals(before, repository.listByOwner(OWNER_A))
            store.selectHistory(store.owners.value.single { it.owner == OWNER_B })
            assertEquals(OWNER_B, repository.lastOwner())
            assertEquals("T-Mobile · •••• 5678", store.owners.value.single { it.selected }.label)
        } finally { store.close() }
    }

    @Test
    fun manualHistorySelectionKeepsOwnerButStillRefreshesNewRecords() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeCallHistoryRepository()
        repository.upsert(OWNER_A, historyWrite())
        repository.upsert(OWNER_B, historyWrite())
        val store = CallHistoryStore(repository, coroutineContext)
        try {
            store.select(OWNER_A)
            store.selectHistory(store.owners.value.single { it.owner == OWNER_B })
            store.select(OWNER_A)
            assertEquals(OWNER_B, repository.lastOwner())
            repository.upsert(OWNER_B, historyWrite(correlation = "second-record", startedAtMs = 2_000L))
            store.select(OWNER_B)
            assertEquals(2, assertIs<CallHistoryState.Ready>(store.state.value).records.size)
        } finally { store.close() }
    }

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
