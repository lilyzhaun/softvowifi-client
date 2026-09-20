package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallRecord
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class CallHistoryCollectorReentryTest {
    @Test fun failureCollectorCanJoinConcurrentSelection() = runBlocking {
        val store = CallHistoryStore(null, Dispatchers.Default)
        assertReentry(store, { it is CallHistoryState.Failure }) {
            withContext(Dispatchers.Default) { store.fail() }
        }
    }

    @Test fun readyCollectorCanJoinConcurrentSelection() = runBlocking {
        val saved = FakeCallHistoryRepository()
        saved.upsert(OWNER_A, historyWrite())
        val releaseRead = CompletableDeferred<Unit>()
        val repository = object : CallHistoryRepository by saved {
            override suspend fun listByOwner(ownerId: SimOwnerId): List<CallRecord> {
                releaseRead.await()
                return saved.listByOwner(ownerId)
            }
        }
        val store = CallHistoryStore(repository, Dispatchers.Default)
        assertReentry(store, { it is CallHistoryState.Ready }) { releaseRead.complete(Unit) }
    }

    private suspend fun assertReentry(
        store: CallHistoryStore,
        matches: (CallHistoryState) -> Boolean,
        trigger: suspend () -> Unit,
    ) {
        val completedInsideCallback = CompletableDeferred<Boolean>()
        val contenders = ConcurrentLinkedQueue<Thread>()
        val observer = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            store.state.collect { view ->
                if (matches(view) && !completedInsideCallback.isCompleted) {
                    val contender = thread(start = false, name = "calls-selection-reentry") { store.select(OWNER_B) }
                    contenders.add(contender)
                    contender.start()
                    contender.join(2_000)
                    completedInsideCallback.complete(!contender.isAlive)
                }
            }
        }
        try {
            trigger()
            assertTrue(withTimeout(5_000) { completedInsideCallback.await() },
                "external collector prevented another thread from finishing select")
        } finally {
            observer.cancelAndJoin()
            store.close()
            contenders.forEach { contender -> contender.join(5_000); check(!contender.isAlive) }
        }
    }
}
