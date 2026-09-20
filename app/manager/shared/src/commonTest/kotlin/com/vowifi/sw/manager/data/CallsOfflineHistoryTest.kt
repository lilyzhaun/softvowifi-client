@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred

class CallsOfflineHistoryTest {
    @Test
    fun missingSimCanBrowseBothOwnersAndClearOnlyConfirmedOwner() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeCallHistoryRepository()
        repository.upsert(OWNER_A, historyWrite())
        repository.upsert(OWNER_B, historyWrite())
        val source = sourceOf(emptyList(), FakeCallTransport(), history = repository)
        try {
            val confirmationA = assertIs<CallHistoryState.Ready>(source.history.value)
            val choiceB = source.historyOwners.value.single { it.owner == OWNER_B }
            source.selectHistoryOwner(choiceB)
            assertEquals(OWNER_B, repository.lastOwner())
            source.clearHistory(confirmationA)
            assertEquals(1, repository.listByOwner(OWNER_A).size)
            assertEquals(1, repository.listByOwner(OWNER_B).size)
            source.clearHistory(assertIs<CallHistoryState.Ready>(source.history.value))
            assertIs<CallHistoryState.Empty>(source.history.value)
            source.selectHistoryOwner(source.historyOwners.value.single { it.owner == OWNER_A })
            assertIs<CallHistoryState.Ready>(source.history.value)
            assertEquals(1, repository.listByOwner(OWNER_A).size)
            assertEquals(0, repository.listByOwner(OWNER_B).size)
        } finally { source.close() }
    }

    @Test
    fun activeOwnerSwitchSurvivesStopAndProcessReopenWithoutClearingOtherOwner() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeCallHistoryRepository()
        repository.upsert(OWNER_A, historyWrite())
        repository.upsert(OWNER_B, historyWrite())
        var instances = listOf(callRunning(CALL_ICCID_A))
        val source = sourceOf(instances, FakeCallTransport(), history = repository,
            ownerIdFor = { if (it == CALL_ICCID_A) OWNER_A else OWNER_B }, listInstances = { instances })
        val old = assertIs<CallHistoryState.Ready>(source.history.value)
        instances = listOf(callRunning(CALL_ICCID_B))
        source.refresh()
        instances = emptyList()
        source.refresh()
        source.clearHistory(old)
        assertEquals(1, repository.listByOwner(OWNER_A).size)
        assertEquals(1, repository.listByOwner(OWNER_B).size)
        source.close()
        val reopened = sourceOf(emptyList(), FakeCallTransport(), history = repository)
        try {
            assertEquals(OWNER_B, repository.lastOwner())
            val confirmation = assertIs<CallHistoryState.Ready>(reopened.history.value)
            assertEquals(1, repository.listByOwner(OWNER_B).size)
            reopened.clearHistory(confirmation)
            assertIs<CallHistoryState.Empty>(reopened.history.value)
            assertEquals(1, repository.listByOwner(OWNER_A).size)
            assertEquals(0, repository.listByOwner(OWNER_B).size)
        } finally { reopened.close() }
    }

    @Test
    fun delayedDiskOwnerCannotReplaceActiveOwnerSelection() = runTest(UnconfinedTestDispatcher()) {
        val saved = FakeCallHistoryRepository()
        saved.upsert(OWNER_A, historyWrite())
        saved.upsert(OWNER_B, historyWrite())
        saved.selectOwner(OWNER_B)
        val gate = CompletableDeferred<Unit>()
        val repository = object : CallHistoryRepository by saved {
            override suspend fun lastOwner(): SimOwnerId? { gate.await(); return OWNER_B }
        }
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), FakeCallTransport(), history = repository)
        gate.complete(Unit)
        try {
            assertEquals(OWNER_A, saved.lastOwner())
            source.clearHistory(assertIs<CallHistoryState.Ready>(source.history.value))
            assertEquals(0, saved.listByOwner(OWNER_A).size)
            assertEquals(1, saved.listByOwner(OWNER_B).size)
        } finally { source.close() }
    }

    @Test
    fun storageFailureIsNotEmptyAndListFailureDoesNotEraseHistory() = runTest(UnconfinedTestDispatcher()) {
        val saved = FakeCallHistoryRepository()
        saved.upsert(OWNER_A, historyWrite())
        val source = sourceOf(emptyList(), FakeCallTransport(), history = saved,
            listInstances = { error("engine unavailable") })
        assertIs<CallUiState.Failure>(source.state.value)
        assertIs<CallHistoryState.Ready>(source.history.value)
        source.close()
        val repository = object : CallHistoryRepository by saved {
            override suspend fun lastOwner(): SimOwnerId? = throw CallHistoryStorageException()
        }
        val failing = sourceOf(emptyList(), FakeCallTransport(), history = repository)
        try { assertIs<CallHistoryState.Failure>(failing.history.value) }
        finally { failing.close() }
    }

    @Test
    fun coldSourceRestoresExistingHistoryWithoutEngineOrSim() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeCallHistoryRepository()
        repository.upsert(OWNER_A, historyWrite())
        val source = sourceOf(emptyList(), FakeCallTransport(), history = repository,
            ownerIdFor = { error("offline history must not resolve SIM identity") })
        try {
            assertIs<CallUiState.Unavailable>(source.state.value)
            assertEquals(repository.listByOwner(OWNER_A), assertIs<CallHistoryState.Ready>(source.history.value).records)
        } finally { source.close() }
    }

    @Test
    fun coldSourceWithNoRecordsIsEmpty() = runTest(UnconfinedTestDispatcher()) {
        val source = sourceOf(emptyList(), FakeCallTransport(), history = FakeCallHistoryRepository())
        try { assertIs<CallHistoryState.Empty>(source.history.value) }
        finally { source.close() }
    }
}
