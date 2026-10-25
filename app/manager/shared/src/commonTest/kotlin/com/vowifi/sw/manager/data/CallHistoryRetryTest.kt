@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlin.test.*

class CallHistoryRetryTest {
    @Test fun startupStillReadsTheStoredPreferenceButTheLatestQueuedOwnerWins() = runTest {
        val saved = FakeCallHistoryRepository()
        for (owner in listOf(OWNER_A, OWNER_B)) saved.upsert(owner,
            historyWrite(endedAtMs = T0 + 1000, outcome = CallOutcome.REJECTED))
        saved.selectOwner(OWNER_B)
        val gate = CompletableDeferred<Unit>(); var reads = 0
        val repository = object : CallHistoryRepository by saved {
            override suspend fun lastOwner(): SimOwnerId? { reads++; gate.await(); return saved.lastOwner() }
        }
        val store = CallHistoryStore(repository, coroutineContext)
        try {
            store.select(OWNER_A) // Active selection is queued before the startup worker begins.
            runCurrent()
            assertEquals(1, reads, "即使已有排队选线，启动偏好读取/恢复入口仍必须执行")
            assertIs<CallHistoryState.Loading>(store.state.value)
            store.select(OWNER_B); store.select(OWNER_A)
            gate.complete(Unit); runCurrent()
            assertEquals(OWNER_A, assertIs<CallHistoryState.Ready>(store.state.value).owner)
            assertEquals(OWNER_A, saved.lastOwner())
            assertEquals(1, reads)
        } finally { gate.complete(Unit); store.close(); advanceUntilIdle() }
    }

    @Test fun initialReadFailureRetriesWithoutRepeatingCompletedStartupRecovery() = runTest(UnconfinedTestDispatcher()) {
        val saved = FakeCallHistoryRepository()
        saved.upsert(OWNER_A, historyWrite(endedAtMs = T0 + 1000, outcome = CallOutcome.REJECTED))
        var failRead = true; var recoveries = 0; var reads = 0
        val repository = object : CallHistoryRepository by saved {
            override suspend fun interruptOpen(endedAtMs: Long) { recoveries++; saved.interruptOpen(endedAtMs) }
            override suspend fun lastOwner(): SimOwnerId? {
                reads++
                if (failRead) { failRead = false; throw CallHistoryStorageException() }
                return saved.lastOwner()
            }
        }
        val store = CallHistoryStore(repository, coroutineContext)
        try {
            assertIs<CallHistoryState.Failure>(store.state.value)
            store.refresh(); runCurrent()
            assertEquals(OWNER_A, assertIs<CallHistoryState.Ready>(store.state.value).owner)
            assertEquals(2, reads); assertEquals(1, recoveries)
            store.close(); runCurrent(); store.refresh(); runCurrent()
            assertEquals(2, reads, "关闭后不能启动新读取")
        } finally { store.close(); advanceUntilIdle() }
    }

    @Test fun explicitReadAfterAnAmbiguousWriteNeverReplaysItAndNewWritesStillWork() = runTest(UnconfinedTestDispatcher()) {
        val saved = FakeCallHistoryRepository()
        var ambiguous = true
        val repository = object : CallHistoryRepository by saved {
            override suspend fun upsert(ownerId: SimOwnerId, write: CallHistoryWrite) {
                saved.upsert(ownerId, write)
                if (ambiguous) { ambiguous = false; throw CallHistoryStorageException() }
            }
        }
        val store = CallHistoryStore(repository, coroutineContext)
        try {
            store.select(OWNER_A)
            store.write(CallHistoryEffect(1, OWNER_A, historyWrite(endedAtMs = T0 + 1000, outcome = CallOutcome.REJECTED)))
            assertIs<CallHistoryState.Failure>(store.state.value)
            repeat(32) { store.select(OWNER_A) }
            assertIs<CallHistoryState.Failure>(store.state.value, "普通select不能掩盖失败写入")
            store.refresh(); runCurrent()
            assertEquals(1, assertIs<CallHistoryState.Ready>(store.state.value).records.size)
            assertEquals(1, saved.upsertCount, "仅重读，不重放结果未知的写入")
            store.write(CallHistoryEffect(2, OWNER_A, historyWrite(correlation = "fresh-after-retry",
                endedAtMs = T0 + 2000, outcome = CallOutcome.REJECTED)))
            assertEquals(2, assertIs<CallHistoryState.Ready>(store.state.value).records.size)
            assertEquals(2, saved.upsertCount)
            assertTrue(saved.listByOwner(OWNER_B).isEmpty())
        } finally { store.close(); advanceUntilIdle() }
    }

    @Test fun anOwnerSelectedDuringRetryCannotBeOverwrittenByTheOldRead() = runTest {
        val saved = FakeCallHistoryRepository()
        for (owner in listOf(OWNER_A, OWNER_B)) saved.upsert(owner,
            historyWrite(endedAtMs = T0 + 1000, outcome = CallOutcome.REJECTED))
        val gate = CompletableDeferred<Unit>(); var block = false
        val repository = object : CallHistoryRepository by saved {
            override suspend fun listByOwner(ownerId: SimOwnerId): List<CallRecord> {
                if (block && ownerId == OWNER_A) gate.await()
                return saved.listByOwner(ownerId)
            }
        }
        val store = CallHistoryStore(repository, coroutineContext)
        try {
            store.select(OWNER_A); runCurrent()
            assertEquals(OWNER_A, assertIs<CallHistoryState.Ready>(store.state.value).owner)
            block = true; store.refresh(); runCurrent()
            assertIs<CallHistoryState.Loading>(store.state.value)
            store.select(OWNER_B); gate.complete(Unit); runCurrent()
            assertEquals(OWNER_B, assertIs<CallHistoryState.Ready>(store.state.value).owner)
            assertEquals(HistoryChangeReason.REPLACE, store.historySnapshot.value.change?.reason)
        } finally { gate.complete(Unit); store.close(); advanceUntilIdle() }
    }

    @Test fun explicitRetryReallyReadsTheSelectedHistoryWithoutReattachingOrSelectingAnotherLine() = runTest(UnconfinedTestDispatcher()) {
        val f = CallAggregateFixture(coroutineContext)
        try {
            f.repository.upsert(OWNER_A, historyWrite(outcome = CallOutcome.REJECTED, endedAtMs = T0 + 1000))
            val selected = f.source.historyOwners.value.single { it.selected }
            f.repository.failNextList = true
            f.source.selectHistoryOwner(selected)
            assertIs<CallHistoryState.Failure>(f.source.history.value, "前提：真实repository读取失败")
            val connects = f.first.connectCalls to f.second.connectCalls
            val snapshots = f.first.snapshotRequests.size to f.second.snapshotRequests.size
            val outgoing = f.source.lines.value.outgoingBinding
            f.source.refreshHistory(); runCurrent()
            val result = assertIs<CallHistoryState.Ready>(f.source.history.value)
            assertEquals(OWNER_A, result.owner)
            assertEquals(1, result.records.size)
            assertSame(outgoing, f.source.lines.value.outgoingBinding)
            assertEquals(connects, f.first.connectCalls to f.second.connectCalls)
            assertEquals(snapshots, f.first.snapshotRequests.size to f.second.snapshotRequests.size,
                "历史重试不能冒充引擎快照刷新")
        } finally { f.source.close(); advanceUntilIdle() }
    }
}
