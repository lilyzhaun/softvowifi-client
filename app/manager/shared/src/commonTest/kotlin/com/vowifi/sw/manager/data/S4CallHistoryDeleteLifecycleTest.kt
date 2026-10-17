@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallHistoryRecordId
import com.vowifi.sw.manager.model.CallHistoryDeleteResult
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.HistoryChangeReason
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// S4/E2：原4项已有真实回执；Aggregate新接口合同待首跑，不把兼容默认冒充生产接线。
class S4CallHistoryDeleteLifecycleTest {
    @Test
    fun heldDeleteKeepsConfirmedRowsUntilRealCommitAndKeepsSiblingOwner() = runTest(timeout = 5.seconds) {
        val saved = FakeCallHistoryRepository()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        var deleteCalls = 0
        val repository = object : CallHistoryRepository by saved {
            override suspend fun deleteByIds(ownerId: SimOwnerId, ids: Set<CallHistoryRecordId>) {
                deleteCalls++
                entered.complete(Unit)
                try {
                    withTimeout(2_000) { release.await() }
                    saved.deleteByIds(ownerId, ids)
                } finally {
                    exited.complete(Unit)
                }
            }
        }
        var store: CallHistoryStore? = null
        try {
            seedCalls(saved)
            val running = CallHistoryStore(repository, backgroundScope.coroutineContext)
            store = running
            running.select(OWNER_A)
            runCurrent()
            val before = assertIs<CallHistoryState.Ready>(running.state.value)
            assertEquals(2, before.records.size)
            val sibling = saved.listByOwner(OWNER_B)
            val id = assertNotNull(before.records.first().recordId)

            running.delete(before, setOf(id))
            runCurrent()
            assertTrue(entered.isCompleted, "必须确实到达 repository 删除入口")
            assertFalse(exited.isCompleted)
            assertEquals(1, deleteCalls)
            assertSame(before, running.state.value, "pending 不能替换用于 expected 校验的 Ready 身份")
            assertEquals(before.records, saved.listByOwner(OWNER_A))
            assertEquals(sibling, saved.listByOwner(OWNER_B))

            release.complete(Unit)
            runCurrent()
            assertTrue(exited.isCompleted)
            val committed = assertIs<CallHistoryState.Ready>(running.state.value)
            assertEquals(before.records.filterNot { it.recordId == id }, saved.listByOwner(OWNER_A))
            assertEquals(saved.listByOwner(OWNER_A), committed.records)
            assertEquals(sibling, saved.listByOwner(OWNER_B))
        } finally {
            release.complete(Unit)
            runCurrent()
            store?.close()
            runCurrent()
            saved.close()
        }
    }

    @Test
    fun failedDeleteDoesNotKillWorkerAndFreshRetryReallyCommits() = runTest(timeout = 5.seconds) {
        val saved = FakeCallHistoryRepository()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        var failNextDelete = false
        var deleteCalls = 0
        val repository = object : CallHistoryRepository by saved {
            override suspend fun deleteByIds(ownerId: SimOwnerId, ids: Set<CallHistoryRecordId>) {
                deleteCalls++
                if (failNextDelete) {
                    failNextDelete = false
                    entered.complete(Unit)
                    try {
                        withTimeout(2_000) { release.await() }
                        throw CallHistoryStorageException()
                    } finally {
                        exited.complete(Unit)
                    }
                }
                saved.deleteByIds(ownerId, ids)
            }
        }
        var store: CallHistoryStore? = null
        try {
            seedCalls(saved)
            val running = CallHistoryStore(repository, backgroundScope.coroutineContext)
            store = running
            running.select(OWNER_A)
            runCurrent()
            val initial = assertIs<CallHistoryState.Ready>(running.state.value)
            val warmupId = assertNotNull(initial.records.first().recordId)
            running.delete(initial, setOf(warmupId))
            runCurrent()
            val before = assertIs<CallHistoryState.Ready>(running.state.value)
            assertEquals(initial.records.filterNot { it.recordId == warmupId }, before.records)
            assertEquals(before.records, saved.listByOwner(OWNER_A))
            val sibling = saved.listByOwner(OWNER_B)
            val id = assertNotNull(before.records.single().recordId)

            failNextDelete = true
            running.delete(before, setOf(id))
            runCurrent()
            assertTrue(entered.isCompleted)
            assertSame(before, running.state.value)
            release.complete(Unit)
            runCurrent()
            assertTrue(exited.isCompleted)
            assertEquals(before.records, saved.listByOwner(OWNER_A), "失败未提交，目标必须仍在存储中")
            assertEquals(sibling, saved.listByOwner(OWNER_B))

            val nextWrite = completedWrite("corr-synth-after-delete-failure")
            running.write(CallHistoryEffect(1, OWNER_A, nextWrite))
            runCurrent()
            // 旧 worker 的外层 catch 结束循环；此行为断言预计失败，不用 Unit 推成功。
            assertEquals(2, saved.listByOwner(OWNER_A).size, "删除失败后必须仍处理下一次真实写入")
            val recovered = assertIs<CallHistoryState.Ready>(running.state.value)
            assertEquals(saved.listByOwner(OWNER_A), recovered.records)
            assertTrue(recovered.records.any { it.recordId == id })
            running.delete(recovered, setOf(id))
            runCurrent()
            assertEquals(3, deleteCalls)
            val committed = assertIs<CallHistoryState.Ready>(running.state.value)
            assertEquals(listOf(nextWrite.toRecord(OWNER_A)), committed.records)
            assertEquals(committed.records, saved.listByOwner(OWNER_A))
            assertEquals(sibling, saved.listByOwner(OWNER_B))
        } finally {
            release.complete(Unit)
            runCurrent()
            store?.close()
            runCurrent()
            saved.close()
        }
    }

    @Test
    fun equalReadKeepsExpectedButNewRowRejectsOldExpectedAndFreshDeleteSucceeds() = runTest(timeout = 5.seconds) {
        val saved = FakeCallHistoryRepository()
        var deleteCalls = 0
        val repository = object : CallHistoryRepository by saved {
            override suspend fun deleteByIds(ownerId: SimOwnerId, ids: Set<CallHistoryRecordId>) {
                deleteCalls++
                saved.deleteByIds(ownerId, ids)
            }
        }
        var store: CallHistoryStore? = null
        try {
            saved.upsert(OWNER_A, completedWrite())
            saved.upsert(OWNER_B, completedWrite())
            val running = CallHistoryStore(repository, backgroundScope.coroutineContext)
            store = running
            running.select(OWNER_A)
            runCurrent()
            val expected = assertIs<CallHistoryState.Ready>(running.state.value)
            val id = assertNotNull(expected.records.single().recordId)
            val sibling = saved.listByOwner(OWNER_B)

            running.write(CallHistoryEffect(1, OWNER_A, completedWrite()))
            runCurrent()
            assertSame(expected, running.state.value, "同值 publish 必须保留当前 Ready 身份")
            val nextWrite = completedWrite(CORR_B)
            running.write(CallHistoryEffect(2, OWNER_A, nextWrite))
            runCurrent()
            val fresh = assertIs<CallHistoryState.Ready>(running.state.value)
            assertEquals(2, fresh.records.size)
            assertTrue(fresh !== expected)

            running.delete(expected, setOf(id))
            runCurrent()
            assertEquals(0, deleteCalls, "旧 expected 即使包含仍存在的 id 也必须被拒绝")
            assertSame(fresh, running.state.value)
            assertEquals(fresh.records, saved.listByOwner(OWNER_A))

            running.delete(fresh, setOf(id))
            runCurrent()
            assertEquals(1, deleteCalls)
            assertEquals(listOf(nextWrite.toRecord(OWNER_A)), saved.listByOwner(OWNER_A))
            assertEquals(saved.listByOwner(OWNER_A), assertIs<CallHistoryState.Ready>(running.state.value).records)
            assertEquals(sibling, saved.listByOwner(OWNER_B))
        } finally {
            store?.close()
            runCurrent()
            saved.close()
        }
    }

    @Test
    fun ownerSwitchRejectsOldConfirmationButCurrentOwnerDeleteReallyCommits() = runTest(timeout = 5.seconds) {
        val saved = FakeCallHistoryRepository()
        val deletedOwners = mutableListOf<SimOwnerId>()
        val repository = object : CallHistoryRepository by saved {
            override suspend fun deleteByIds(ownerId: SimOwnerId, ids: Set<CallHistoryRecordId>) {
                deletedOwners += ownerId
                saved.deleteByIds(ownerId, ids)
            }
        }
        var store: CallHistoryStore? = null
        try {
            seedCalls(saved)
            val running = CallHistoryStore(repository, backgroundScope.coroutineContext)
            store = running
            running.select(OWNER_A)
            runCurrent()
            val old = assertIs<CallHistoryState.Ready>(running.state.value)
            val beforeB = saved.listByOwner(OWNER_B)
            running.select(OWNER_B)
            running.delete(old, old.records.mapNotNull { it.recordId }.toSet())
            runCurrent()
            assertTrue(deletedOwners.isEmpty())
            assertEquals(old.records, saved.listByOwner(OWNER_A))
            assertEquals(beforeB, saved.listByOwner(OWNER_B))
            val current = assertIs<CallHistoryState.Ready>(running.state.value)
            assertEquals(OWNER_B, current.owner)
            running.delete(current, current.records.mapNotNull { it.recordId }.toSet())
            runCurrent()
            assertEquals(listOf(OWNER_B), deletedOwners)
            assertTrue(saved.listByOwner(OWNER_B).isEmpty())
            assertIs<CallHistoryState.Empty>(running.state.value)
            assertEquals(old.records, saved.listByOwner(OWNER_A))
        } finally {
            store?.close()
            runCurrent()
            saved.close()
        }
    }

    @Test
    fun aggregateDeleteWaitsForCommitAndReadbackWithAtomicPendingAndRejectsStaleExpected() = runTest(timeout = 5.seconds) {
        val saved = FakeCallHistoryRepository()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val readbackEntered = CompletableDeferred<Unit>()
        val readbackRelease = CompletableDeferred<Unit>()
        var committed = false
        var calls = 0
        val repository = object : CallHistoryRepository by saved {
            override suspend fun deleteByIds(ownerId: SimOwnerId, ids: Set<CallHistoryRecordId>) {
                calls++; entered.complete(Unit)
                withTimeout(2_000) { release.await() }
                saved.deleteByIds(ownerId, ids)
                committed = true
            }
            override suspend fun listByOwner(ownerId: SimOwnerId): List<com.vowifi.sw.manager.model.CallRecord> {
                if (committed && ownerId == OWNER_A) {
                    readbackEntered.complete(Unit)
                    withTimeout(2_000) { readbackRelease.await() }
                }
                return saved.listByOwner(ownerId)
            }
        }
        seedCalls(saved)
        val source = aggregate(repository, backgroundScope.coroutineContext)
        try {
            runCurrent()
            val before = assertIs<CallHistoryState.Ready>(source.history.value)
            assertEquals(OWNER_A, before.owner)
            val snapshot = assertNotNull(source.historySnapshot, "真实Aggregate须透传同一Store原子快照，不靠UI拼装")
            assertSame(before, snapshot.value.view)
            val sibling = saved.listByOwner(OWNER_B)
            val id = assertNotNull(before.records.first().recordId)
            val request = async { source.deleteHistoryAndAwait(before, setOf(id)) }
            runCurrent()
            assertTrue(entered.isCompleted, "公开await必须进入原Store的真实repository删除")
            assertFalse(request.isCompleted)
            assertEquals(setOf(id), snapshot.value.deletePending)
            assertSame(before, snapshot.value.view)
            assertEquals(before.records, saved.listByOwner(OWNER_A))
            assertEquals(CallHistoryDeleteResult.REJECTED, source.deleteHistoryAndAwait(before, setOf(id)))
            assertEquals(1, calls, "重复受理不能双发")
            release.complete(Unit); runCurrent()
            assertTrue(readbackEntered.isCompleted)
            assertFalse(request.isCompleted, "真实删除返回但读回仍held不能报告COMMITTED")
            assertSame(before, snapshot.value.view)
            assertEquals(before.records.filterNot { it.recordId == id }, saved.listByOwner(OWNER_A))
            readbackRelease.complete(Unit); runCurrent()
            assertEquals(CallHistoryDeleteResult.COMMITTED, request.await())
            val after = assertIs<CallHistoryState.Ready>(snapshot.value.view)
            assertSame(after, source.history.value)
            assertEquals(saved.listByOwner(OWNER_A), after.records)
            assertTrue(snapshot.value.deletePending.isEmpty())
            val change = assertNotNull(snapshot.value.change)
            assertEquals(HistoryChangeReason.USER_DELETE, change.reason)
            assertEquals(setOf(id), change.ids)
            assertEquals(CallHistoryDeleteResult.REJECTED, source.deleteHistoryAndAwait(before, setOf(id)))
            assertEquals(1, calls)
            source.deleteHistory(after, after.records.mapNotNull { it.recordId }.toSet())
            runCurrent()
            assertEquals(2, calls, "旧Unit入口与await共用Store，每个真实请求只写一次")
            assertIs<CallHistoryState.Empty>(snapshot.value.view)
            assertEquals(sibling, saved.listByOwner(OWNER_B))
        } finally {
            release.complete(Unit); readbackRelease.complete(Unit)
            source.close(); runCurrent(); saved.close()
        }
    }

    @Test
    fun aggregateDeleteSurvivesWaiterCancellationAndReturnsRealFailureBeforeRetry() = runTest(timeout = 5.seconds) {
        val saved = FakeCallHistoryRepository()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cancelled = false
        var fail = false
        var calls = 0
        val repository = object : CallHistoryRepository by saved {
            override suspend fun deleteByIds(ownerId: SimOwnerId, ids: Set<CallHistoryRecordId>) {
                calls++; entered.complete(Unit)
                try {
                    withTimeout(2_000) { release.await() }
                    if (fail) throw CallHistoryStorageException()
                    saved.deleteByIds(ownerId, ids)
                } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                    cancelled = true
                    throw error
                }
            }
        }
        seedCalls(saved)
        val source = aggregate(repository, backgroundScope.coroutineContext)
        try {
            runCurrent()
            val before = assertIs<CallHistoryState.Ready>(source.history.value)
            val snapshot = assertNotNull(source.historySnapshot)
            val sibling = saved.listByOwner(OWNER_B)
            val first = assertNotNull(before.records.first().recordId)
            val waiter = async { source.deleteHistoryAndAwait(before, setOf(first)) }
            runCurrent()
            assertTrue(entered.isCompleted)
            waiter.cancel(); runCurrent()
            assertFalse(cancelled, "页面取消等待不能取消source-owned删除worker")
            assertEquals(setOf(first), snapshot.value.deletePending)
            release.complete(Unit); runCurrent()
            assertEquals(before.records.filterNot { it.recordId == first }, saved.listByOwner(OWNER_A))
            assertEquals(saved.listByOwner(OWNER_A), assertIs<CallHistoryState.Ready>(snapshot.value.view).records)
            assertTrue(snapshot.value.deletePending.isEmpty())
            val expected = assertIs<CallHistoryState.Ready>(source.history.value)
            val remaining = expected.records.mapNotNull { it.recordId }.toSet()
            fail = true
            val failed = async { source.deleteHistoryAndAwait(expected, remaining) }
            runCurrent()
            assertEquals(CallHistoryDeleteResult.FAILED, failed.await())
            assertSame(expected, snapshot.value.view)
            assertEquals(expected.records, saved.listByOwner(OWNER_A))
            assertTrue(snapshot.value.deletePending.isEmpty())
            fail = false
            val retry = async { source.deleteHistoryAndAwait(expected, remaining) }
            runCurrent()
            assertEquals(CallHistoryDeleteResult.COMMITTED, retry.await())
            assertEquals(3, calls)
            assertIs<CallHistoryState.Empty>(snapshot.value.view)
            assertEquals(sibling, saved.listByOwner(OWNER_B))
        } finally {
            release.complete(Unit); source.close(); runCurrent(); saved.close()
        }
    }

    @Test
    fun aggregateHistorySelectionAwaitsActualReadbackAndKeepsChoiceReferenceGate() = runTest(timeout = 5.seconds) {
        val saved = FakeCallHistoryRepository()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var selections = 0
        val repository = object : CallHistoryRepository by saved {
            override suspend fun selectOwner(ownerId: SimOwnerId) {
                selections++
                saved.selectOwner(ownerId)
            }
            override suspend fun listByOwner(ownerId: SimOwnerId): List<com.vowifi.sw.manager.model.CallRecord> {
                if (ownerId == OWNER_B) {
                    entered.complete(Unit)
                    withTimeout(2_000) { release.await() }
                }
                return saved.listByOwner(ownerId)
            }
        }
        seedCalls(saved)
        val source = aggregate(repository, backgroundScope.coroutineContext)
        try {
            runCurrent()
            val before = assertIs<CallHistoryState.Ready>(source.history.value)
            val snapshot = assertNotNull(source.historySnapshot)
            val oldA = source.historyOwners.value.single { it.owner == OWNER_A }
            val choiceB = source.historyOwners.value.single { it.owner == OWNER_B }
            val baseSelections = selections
            assertFalse(source.selectHistoryOwnerAndAwait(CallHistoryOwnerChoice(OWNER_B, choiceB.label, false)),
                "结构同值但非当前choice引用必须被拒绝")
            assertSame(before, snapshot.value.view)
            assertEquals(baseSelections, selections)
            val selected = async { source.selectHistoryOwnerAndAwait(choiceB) }
            runCurrent()
            assertTrue(entered.isCompleted, "真实history选择必须进入Store读回")
            assertFalse(selected.isCompleted, "受理/Loading不代表读取确认")
            release.complete(Unit); runCurrent()
            assertTrue(selected.await())
            val confirmed = assertIs<CallHistoryState.Ready>(snapshot.value.view)
            assertEquals(OWNER_B, confirmed.owner)
            assertEquals(saved.listByOwner(OWNER_B), confirmed.records)
            assertEquals(OWNER_B, snapshot.value.owners.single { it.selected }.owner)
            assertFalse(source.selectHistoryOwnerAndAwait(oldA), "读回后旧choice引用不能绕过门禁")
            assertSame(confirmed, snapshot.value.view)
            source.selectHistoryOwner(source.historyOwners.value.single { it.owner == OWNER_A })
            runCurrent()
            assertEquals(OWNER_A, assertIs<CallHistoryState.Ready>(snapshot.value.view).owner)
            assertEquals(baseSelections + 2, selections, "Unit/await选择各一次，不重复发仓库选择")
        } finally {
            release.complete(Unit); source.close(); runCurrent(); saved.close()
        }
    }

    @Test
    fun aggregateCommittedDeleteWithFailedReadbackStaysUnconfirmedUntilRealReselection() = runTest(timeout = 5.seconds) {
        val saved = FakeCallHistoryRepository()
        var calls = 0
        val repository = object : CallHistoryRepository by saved {
            override suspend fun deleteByIds(ownerId: SimOwnerId, ids: Set<CallHistoryRecordId>) {
                calls++
                saved.deleteByIds(ownerId, ids)
                saved.failNextList = true
            }
        }
        saved.upsert(OWNER_A, completedWrite())
        val source = aggregate(repository, backgroundScope.coroutineContext)
        try {
            runCurrent()
            val before = assertIs<CallHistoryState.Ready>(source.history.value)
            val snapshot = assertNotNull(source.historySnapshot)
            val ids = before.records.mapNotNull { it.recordId }.toSet()
            val request = async { source.deleteHistoryAndAwait(before, ids) }
            runCurrent()
            assertEquals(CallHistoryDeleteResult.UNCONFIRMED, request.await())
            assertSame(before, snapshot.value.view)
            assertTrue(snapshot.value.deletePending.isEmpty())
            assertTrue(saved.listByOwner(OWNER_A).isEmpty(), "提交已发生，读回失败不能冒称FAILED/未提交")
            val read = async { source.selectHistoryOwnerAndAwait(source.historyOwners.value.single { it.selected }) }
            runCurrent()
            assertTrue(read.await())
            assertIs<CallHistoryState.Empty>(snapshot.value.view)
            assertEquals(1, calls, "读回核对不能自动重试删除")
        } finally {
            source.close(); runCurrent(); saved.close()
        }
    }

    @Test
    fun aggregateCloseSettlesAnIssuedDeleteWithinTheExistingFiveSecondBudget() = runTest(timeout = 5.seconds) {
        val saved = FakeCallHistoryRepository()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : CallHistoryRepository by saved {
            override suspend fun deleteByIds(ownerId: SimOwnerId, ids: Set<CallHistoryRecordId>) {
                entered.complete(Unit)
                release.await()
                saved.deleteByIds(ownerId, ids)
            }
        }
        saved.upsert(OWNER_A, completedWrite())
        val source = aggregate(repository, backgroundScope.coroutineContext)
        try {
            runCurrent()
            val before = assertIs<CallHistoryState.Ready>(source.history.value)
            val snapshot = assertNotNull(source.historySnapshot)
            val request = async { source.deleteHistoryAndAwait(before, before.records.mapNotNull { it.recordId }.toSet()) }
            runCurrent()
            assertTrue(entered.isCompleted); assertFalse(request.isCompleted)
            source.close(); advanceTimeBy(5_000); runCurrent()
            assertTrue(request.isCompleted, "关闭不得把真实await回执悬挂")
            assertEquals(CallHistoryDeleteResult.UNCONFIRMED, request.await())
            assertTrue(snapshot.value.deletePending.isEmpty())
            assertEquals(before.records, saved.listByOwner(OWNER_A))
        } finally {
            release.complete(Unit); source.close(); runCurrent(); saved.close()
        }
    }

    private fun aggregate(repository: CallHistoryRepository, context: CoroutineContext) = CallSourceAggregate(
        runtime = EngineMessageRuntime({ emptyList() }, { error("No engine allowed") }, { T0 }, { emptyList() }),
        audio = FakeCallAudioController(), control = CallControlTransport { _, _, _, _ -> error("No call allowed") },
        parentContext = context, repository = repository, ownerIdFor = { null }, nextCorrelation = { "unused" },
    )

    private suspend fun seedCalls(saved: FakeCallHistoryRepository) {
        saved.upsert(OWNER_A, completedWrite())
        saved.upsert(OWNER_A, completedWrite(CORR_B))
        saved.upsert(OWNER_B, completedWrite())
    }

    private fun completedWrite(correlation: String = CORR_A) = historyWrite(
        correlation = correlation,
        connectedAtMs = T0 + 1_000,
        endedAtMs = T0 + 2_000,
        durationSec = 1,
        outcome = CallOutcome.COMPLETED,
    )
}
