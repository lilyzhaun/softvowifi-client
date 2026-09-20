package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallRecord
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class CallHistoryPublicationRaceTest {
    @Test fun selectedARejectsBPausedBetweenOwnerCheckAndReadyPublication() = runBlocking {
        val saved = FakeCallHistoryRepository()
        saved.upsert(OWNER_A, historyWrite(correlation = "owner-a", startedAtMs = T0 + 10))
        saved.upsert(OWNER_B, historyWrite(correlation = "owner-b", startedAtMs = T0))
        saved.selectOwner(OWNER_B)
        val enteredPublication = CompletableDeferred<Unit>()
        val releasePublication = CountDownLatch(1)
        val selectedA = AtomicBoolean(false)
        val publishedAfterSelection = ConcurrentLinkedQueue<CallHistoryState>()
        val repository = object : CallHistoryRepository by saved {
            override suspend fun listByOwner(ownerId: SimOwnerId): List<CallRecord> {
                val records = saved.listByOwner(ownerId)
                if (ownerId != OWNER_B) return records
                return object : List<CallRecord> by records {
                    override fun isEmpty(): Boolean {
                        enteredPublication.complete(Unit)
                        check(releasePublication.await(5, TimeUnit.SECONDS))
                        return records.isEmpty()
                    }
                }
            }
        }
        val store = CallHistoryStore(repository, Dispatchers.Default)
        val observer = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            store.state.collect { if (selectedA.get()) publishedAfterSelection.add(it) }
        }
        try {
            withTimeout(5_000) { enteredPublication.await() }
            store.select(OWNER_A)
            selectedA.set(true)
            releasePublication.countDown()
            val ready = withTimeout(5_000) {
                store.state.first { it is CallHistoryState.Ready && it.records.single().startedAtMs == T0 + 10 }
            }
            assertFalse(publishedAfterSelection.filterIsInstance<CallHistoryState.Ready>()
                .any { it.records.single().startedAtMs == T0 }, "old B published after select(A) returned")
            assertTrue(ready is CallHistoryState.Ready)
            assertEquals(OWNER_A, saved.lastOwner())
        } finally {
            releasePublication.countDown()
            observer.cancelAndJoin()
            store.close()
        }
    }
}
