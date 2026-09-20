@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallHistoryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class CallHistorySnapshotContractTest {
    @Test fun ownersAndHistoryAreAlreadyCommittedWhenOwnersCollectorRuns() = runBlocking<Unit> {
        val saved = FakeCallHistoryRepository()
        saved.upsert(OWNER_A, historyWrite())
        val releaseStartup = CompletableDeferred<Unit>()
        val repository = object : CallHistoryRepository by saved {
            override suspend fun interruptOpen(endedAtMs: Long) { releaseStartup.await() }
        }
        val store = CallHistoryStore(repository, Dispatchers.Default)
        val pairedView = CompletableDeferred<CallHistoryState>()
        val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            store.owners.collect { choices -> if (choices.isNotEmpty()) pairedView.complete(store.state.value) }
        }
        releaseStartup.complete(Unit)
        try {
            assertIs<CallHistoryState.Ready>(withTimeout(5_000) { pairedView.await() })
        } finally { observer.cancelAndJoin(); store.close() }
    }

    @Test fun selectionOnlyUpdatesDoNotEmitPublicViewsAndReplayMatchesValue() = runTest(UnconfinedTestDispatcher()) {
        val releaseStartup = CompletableDeferred<Unit>()
        val repository = object : CallHistoryRepository by FakeCallHistoryRepository() {
            override suspend fun interruptOpen(endedAtMs: Long) { releaseStartup.await() }
        }
        val store = CallHistoryStore(repository, coroutineContext)
        val views = mutableListOf<CallHistoryState>()
        val ownerViews = mutableListOf<List<CallHistoryOwnerChoice>>()
        val viewObserver = launch(start = CoroutineStart.UNDISPATCHED) { store.state.collect { views.add(it) } }
        val ownerObserver = launch(start = CoroutineStart.UNDISPATCHED) { store.owners.collect { ownerViews.add(it) } }
        try {
            store.select(OWNER_A)
            store.select(OWNER_B)
            assertEquals(listOf<CallHistoryState>(CallHistoryState.Loading), views)
            assertEquals(listOf(emptyList<CallHistoryOwnerChoice>()), ownerViews)
            assertEquals(listOf(store.state.value), store.state.replayCache)
            assertEquals(listOf(store.owners.value), store.owners.replayCache)
        } finally {
            viewObserver.cancelAndJoin(); ownerObserver.cancelAndJoin()
            releaseStartup.complete(Unit); store.close()
        }
    }

    @Test fun identicalWriteKeepsReadyReferenceUsableForClear() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeCallHistoryRepository()
        val write = historyWrite()
        repository.upsert(OWNER_A, write)
        val store = CallHistoryStore(repository, coroutineContext, clock = { T0 })
        val confirmation = assertIs<CallHistoryState.Ready>(store.state.value)
        store.write(CallHistoryEffect(1, OWNER_A, write))
        try {
            assertSame(confirmation, store.state.value)
            store.clear(confirmation)
            assertTrue(repository.listByOwner(OWNER_A).isEmpty())
        } finally { store.close() }
    }
}
