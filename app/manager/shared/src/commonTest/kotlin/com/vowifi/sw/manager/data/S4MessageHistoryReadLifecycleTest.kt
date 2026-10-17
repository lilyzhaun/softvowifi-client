@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.NOW_MS
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.SYNTH_NUMBER
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.SYNTH_TEXT
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.HistoryChangeReason
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// S4/R6：原3项已有真实执行回执；被测IO可取消，不以NonCancellable掩盖离页取消。
// close全部自有读的新守护待首次执行，不将源码修改冒称GREEN。
class S4MessageHistoryReadLifecycleTest {
    private val threadA = StoredMessageConversation(OWNER_A, PeerNumberNormalizer.normalize(SYNTH_NUMBER))
    private val threadB = StoredMessageConversation(OWNER_B, threadA.key)

    @Test
    fun issuedOlderReadSurvivesLeavingAndCannotPolluteNewInboxOwnerAndQuery() = runTest(timeout = 5.seconds) {
        val saved = FakeMessageRepository()
        val probe = HeldOlderHistory(saved.history, threadA)
        val repository = object : MessageRepository by saved { override val history = probe }
        val selection = MessageHistorySelection(repository, backgroundScope, MessagePersistenceObserver { _, _, _ -> })
        try {
            seedThreads(saved)
            selection.refreshInbox()
            runCurrent()
            selection.select(threadA.handle.value)
            runCurrent()
            val latest = assertIs<MessageUiState.Ready>(selection.state.value)
            assertEquals(50, latest.records.size)
            assertEquals(threadA, assertNotNull(latest.window).identity)
            assertNotNull(latest.window?.older)

            selection.loadOlder()
            runCurrent()
            assertTrue(probe.entered.isCompleted)
            assertFalse(probe.exited.isCompleted)
            selection.select(null)
            selection.selectHistoryOwner(OWNER_B)
            val query = SYNTH_TEXT.uppercase()
            selection.searchInbox(query)
            runCurrent()
            val newScope = assertIs<MessageUiState.Inbox>(selection.state.value)
            assertEquals(OWNER_B, newScope.ownerId)
            assertEquals(query, newScope.page.query)
            assertFalse(newScope.page.loading)
            assertEquals(listOf(threadB), newScope.page.items.map { it.identity })

            probe.release.complete(Unit)
            runCurrent()
            assertTrue(probe.exited.isCompleted)
            // 旧 select(null) 会取消 await；此处预计是真正的行为失败，而不是编译/超时失败。
            assertFalse(probe.cancelled, "离页不得取消已经进入 repository 的分页读取")
            assertEquals(20, assertNotNull(probe.returned).records.size)
            assertEquals(newScope, selection.state.value, "旧读取不得改变新 owner/query 的根列表")
            assertEquals(1, probe.olderCalls)
            assertEquals(0, saved.listByOwnerCalls)
        } finally {
            probe.release.complete(Unit)
            runCurrent()
            selection.close()
            runCurrent()
            saved.close()
        }
    }

    @Test
    fun issuedOlderReadSurvivesThreadSwitchWithoutPublishingOldScopeUnread() = runTest(timeout = 5.seconds) {
        val saved = FakeMessageRepository()
        val probe = HeldOlderHistory(saved.history, threadA)
        val repository = object : MessageRepository by saved { override val history = probe }
        val unread = mutableListOf<Pair<StoredMessageConversation, Set<String>>>()
        val observer = object : MessagePersistenceObserver {
            override fun onSnapshot(owner: SimOwnerId, records: List<MessageRecord>, freshIncomingId: String?) = Unit
            override fun onThreadUnread(conversation: StoredMessageConversation, ids: Set<String>) {
                unread += conversation to ids
            }
        }
        val selection = MessageHistorySelection(repository, backgroundScope, observer)
        try {
            seedThreads(saved)
            selection.refreshInbox()
            runCurrent()
            selection.select(threadA.handle.value)
            runCurrent()
            assertEquals(50, assertIs<MessageUiState.Ready>(selection.state.value).records.size)
            assertEquals(listOf(threadA), unread.map { it.first })
            assertEquals(70, unread.single().second.size)
            selection.select(threadA.handle.value)
            runCurrent()
            assertEquals(1, probe.firstPageCalls, "同一 handle 的重复选择不重新读取")

            selection.loadOlder()
            runCurrent()
            assertTrue(probe.entered.isCompleted)
            val pending = assertIs<MessageUiState.Ready>(selection.state.value)
            assertTrue(assertNotNull(pending.window).loading)
            selection.loadOlder()
            runCurrent()
            assertEquals(1, probe.olderCalls, "已发读取期间不得重复请求同一游标")
            selection.select(threadB.handle.value)
            runCurrent()
            val newScope = assertIs<MessageUiState.Ready>(selection.state.value)
            assertEquals(OWNER_B, newScope.ownerId)
            assertEquals(threadB, assertNotNull(newScope.window).identity)
            assertEquals(listOf("b-only"), newScope.records.map { it.id })
            assertEquals(listOf(threadA, threadB), unread.map { it.first })
            val unreadBeforeRelease = unread.toList()

            probe.release.complete(Unit)
            runCurrent()
            assertTrue(probe.exited.isCompleted)
            assertFalse(probe.cancelled, "换线程只失活旧结果，不取消已发读取")
            assertEquals(20, assertNotNull(probe.returned).records.size)
            assertEquals(newScope, selection.state.value)
            assertEquals(unreadBeforeRelease, unread, "失活读取也不能给通知 observer 发布旧 scope")
            assertEquals(0, saved.listByOwnerCalls)
        } finally {
            probe.release.complete(Unit)
            runCurrent()
            selection.close()
            runCurrent()
            saved.close()
        }
    }

    @Test
    fun olderFailureKeepsRowsAndCursorUntilManualRetryReallyAppends() = runTest(timeout = 5.seconds) {
        val saved = FakeMessageRepository()
        val probe = HeldOlderHistory(saved.history, threadA, failHeldRead = true)
        val repository = object : MessageRepository by saved { override val history = probe }
        val selection = MessageHistorySelection(repository, backgroundScope, MessagePersistenceObserver { _, _, _ -> })
        try {
            seedThreads(saved)
            selection.refreshInbox()
            runCurrent()
            selection.select(threadA.handle.value)
            runCurrent()
            val latest = assertIs<MessageUiState.Ready>(selection.state.value)
            assertEquals(50, latest.records.size)
            val originalCursor = assertNotNull(latest.window?.older)
            selection.loadOlder()
            runCurrent()
            assertTrue(probe.entered.isCompleted)
            assertEquals(originalCursor, probe.requestedCursor)
            selection.loadOlder()
            runCurrent()
            assertEquals(1, probe.olderCalls)
            probe.release.complete(Unit)
            runCurrent()
            assertTrue(probe.exited.isCompleted)
            assertFalse(probe.cancelled)
            val failed = assertIs<MessageUiState.Ready>(selection.state.value)
            assertEquals(latest.records, failed.records)
            assertTrue(assertNotNull(failed.window).failed)
            assertFalse(failed.window?.loading == true)
            assertEquals(originalCursor, failed.window?.older)

            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(1, probe.olderCalls, "失败不自动重发，只允许显式重试入口")
            selection.loadOlder()
            runCurrent()
            val appended = assertIs<MessageUiState.Ready>(selection.state.value)
            assertEquals(2, probe.olderCalls)
            assertEquals(70, appended.records.size)
            assertEquals(70, appended.records.map { it.id }.distinct().size)
            assertEquals(latest.records, appended.records.takeLast(50))
            val window = assertNotNull(appended.window)
            assertEquals(threadA, window.identity)
            assertFalse(window.loading)
            assertFalse(window.failed)
            assertEquals(null, window.older)
            selection.loadOlder()
            runCurrent()
            assertEquals(2, probe.olderCalls, "end 不再请求")
            assertEquals(0, saved.listByOwnerCalls)
        } finally {
            probe.release.complete(Unit)
            runCurrent()
            selection.close()
            runCurrent()
            saved.close()
        }
    }

    @Test
    fun closeCancelsEverySourceOwnedReadAfterSelectionMovesOn() = runTest(timeout = 5.seconds) {
        val saved = FakeMessageRepository()
        val first = HeldOlderHistory(saved.history, threadA)
        val second = HeldOlderHistory(first, threadB)
        val repository = object : MessageRepository by saved { override val history = second }
        val selection = MessageHistorySelection(repository, backgroundScope, MessagePersistenceObserver { _, _, _ -> })
        try {
            seedThreads(saved)
            repeat(70) { index ->
                saved.insertIncomingIfAbsent(OWNER_B, "b-$index", "eb-$index", SYNTH_NUMBER, SYNTH_TEXT, index.toLong(), NOW_MS)
            }
            selection.refreshInbox(); runCurrent()
            selection.select(threadA.handle.value); runCurrent()
            selection.loadOlder(); runCurrent()
            assertTrue(first.entered.isCompleted)
            selection.select(threadB.handle.value); runCurrent()
            assertFalse(first.cancelled, "换线程保留已经进入repository的旧读取")
            selection.loadOlder(); runCurrent()
            assertTrue(second.entered.isCompleted)
            assertFalse(first.exited.isCompleted); assertFalse(second.exited.isCompleted)
            selection.close(); runCurrent()
            assertTrue(first.cancelled); assertTrue(second.cancelled)
            assertTrue(first.exited.isCompleted); assertTrue(second.exited.isCompleted)
            assertEquals(1, first.olderCalls); assertEquals(1, second.olderCalls)
        } finally {
            first.release.complete(Unit); second.release.complete(Unit)
            selection.close(); runCurrent(); saved.close()
        }
    }

    @Test
    fun productionNonLatestIncomingPublishesItsStoredIdAndDoesNotReplayForDuplicateOrEqualRefresh() = runTest(timeout = 5.seconds) {
        val saved = FakeMessageRepository()
        saved.insertIncomingIfAbsent(OWNER_A, "seed-oldest", "engine-oldest", SYNTH_NUMBER, SYNTH_TEXT, NOW_MS - 4_000, NOW_MS)
        saved.insertIncomingIfAbsent(OWNER_A, "seed-latest", "engine-latest", SYNTH_NUMBER, SYNTH_TEXT, NOW_MS, NOW_MS)
        val transport = FakeEngineMessageTransport()
        val source = EngineMessagePersistenceFixtures.sourceOf(
            listOf(EngineMessagePersistenceFixtures.running(EngineMessagePersistenceFixtures.ICCID_A)), transport, saved)
        try {
            runCurrent()
            val inbox = assertIs<MessageUiState.Inbox>(source.state.value)
            assertFalse(inbox.page.loading)
            assertEquals(listOf(threadA), inbox.page.items.map { it.identity })
            assertTrue(transport.statusRequests > 0, "前提：真实Session已连接并读过生产状态")
            source.selectConversation(threadA.handle.value); runCurrent()
            val initial = assertIs<MessageUiState.Ready>(source.state.value)
            assertEquals(listOf("seed-oldest", "seed-latest"), initial.records.map { it.id })
            val oldChange = assertNotNull(initial.window).change
            val eventId = "s4-nonlatest-mt"
            val timestampSeconds = NOW_MS / 1_000 - 1
            transport.emitReceived(eventId, SYNTH_NUMBER, SYNTH_TEXT, timestampSeconds); runCurrent()
            val stored = saved.listByOwner(OWNER_A)
            val incoming = assertIs<MessageRecord.Incoming>(stored.single { it.engineSmsId == eventId })
            val received = assertIs<MessageUiState.Ready>(source.state.value)
            assertEquals(3, stored.size)
            assertEquals(stored, received.records, "前提：实际MT入库后已由生产Selection完整读回")
            assertEquals(incoming.id, received.records[1].id)
            assertTrue(incoming.timestampMs < received.records.last().timestampMs)
            val change = assertNotNull(assertNotNull(received.window).change,
                "缺能力：成功读回的非latest真实MT必须携带稳定App id的显式原因")
            assertEquals(HistoryChangeReason.LIVE_INSERT, change.reason)
            assertEquals(setOf(incoming.id), change.ids)
            assertTrue(change.version > (oldChange?.version ?: -1))
            if (oldChange != null) assertTrue(change.scope === oldChange.scope)
            transport.emitReceived(eventId, SYNTH_NUMBER, SYNTH_TEXT, timestampSeconds); runCurrent()
            val duplicate = assertIs<MessageUiState.Ready>(source.state.value)
            assertEquals(stored, saved.listByOwner(OWNER_A))
            assertEquals(received.records, duplicate.records)
            val repeated = assertNotNull(duplicate.window).change
            if (repeated != null) {
                assertTrue(change.scope === repeated.scope)
                assertTrue(repeated.version >= change.version)
                assertFalse(repeated.version > change.version && repeated.reason == HistoryChangeReason.LIVE_INSERT,
                    "同一真实MT重复投递不能重放新增版本")
            }
            source.refresh(); runCurrent()
            val refreshed = assertIs<MessageUiState.Ready>(source.state.value)
            assertEquals(received.records, refreshed.records)
            val equal = assertNotNull(refreshed.window).change
            if (equal != null) {
                assertTrue(change.scope === equal.scope)
                assertTrue(equal.version >= (repeated?.version ?: change.version))
                assertFalse(equal.version > change.version && equal.reason == HistoryChangeReason.LIVE_INSERT,
                    "同值后台重读不能伪造新的入场")
            }
            assertTrue(transport.requests.isEmpty(), "此合同不发送短信或调用发送RPC")
        } finally {
            source.close(); runCurrent(); saved.close()
        }
    }

    @Test
    fun productionInitialOwnerQueryAndPageReadsPublishSuppressedCausesWithTheirActualScopes() = runTest(timeout = 5.seconds) {
        val saved = FakeMessageRepository()
        seedThreads(saved)
        repeat(55) { index ->
            saved.insertIncomingIfAbsent(OWNER_A, "conversation-$index", "engine-conversation-$index",
                SYNTH_NUMBER + (100 + index), SYNTH_TEXT, 100L + index, NOW_MS)
        }
        val transport = FakeEngineMessageTransport()
        val source = EngineMessagePersistenceFixtures.sourceOf(listOf(
            EngineMessagePersistenceFixtures.running(EngineMessagePersistenceFixtures.ICCID_A),
            EngineMessagePersistenceFixtures.running(EngineMessagePersistenceFixtures.ICCID_B)), transport, saved)
        try {
            runCurrent()
            val first = assertIs<MessageUiState.Inbox>(source.state.value)
            assertEquals(OWNER_A, first.ownerId); assertFalse(first.page.loading)
            assertEquals(50, first.page.items.size); assertNotNull(first.page.next)
            source.loadMoreHistory(); runCurrent()
            val page = assertIs<MessageUiState.Inbox>(source.state.value)
            assertEquals(56, page.page.items.size)
            assertEquals(56, page.page.items.map { it.identity }.distinct().size)
            assertFalse(page.page.loading); assertEquals(null, page.page.next)
            val queryText = SYNTH_TEXT.uppercase()
            source.searchHistory(queryText); runCurrent()
            val query = assertIs<MessageUiState.Inbox>(source.state.value)
            assertEquals(queryText, query.page.query); assertFalse(query.page.loading)
            assertEquals(50, query.page.items.size)
            source.selectHistoryOwner(OWNER_B); runCurrent()
            val ownerB = assertIs<MessageUiState.Inbox>(source.state.value)
            assertEquals(OWNER_B, ownerB.ownerId); assertEquals(queryText, ownerB.page.query)
            assertFalse(ownerB.page.loading); assertEquals(listOf(threadB), ownerB.page.items.map { it.identity })
            source.selectHistoryOwner(OWNER_A); runCurrent()
            val ownerA = assertIs<MessageUiState.Inbox>(source.state.value)
            assertEquals(OWNER_A, ownerA.ownerId); assertFalse(ownerA.page.loading)
            source.selectConversation(threadA.handle.value); runCurrent()
            val thread = assertIs<MessageUiState.Ready>(source.state.value)
            assertEquals(50, thread.records.size)
            assertEquals(threadA, assertNotNull(thread.window).identity)
            assertNotNull(thread.window?.older)
            source.loadOlderMessages(); runCurrent()
            val older = assertIs<MessageUiState.Ready>(source.state.value)
            assertEquals(70, older.records.size)
            assertEquals(70, older.records.map { it.id }.distinct().size)
            assertEquals(thread.records, older.records.takeLast(50))
            assertFalse(assertNotNull(older.window).loading); assertEquals(null, older.window?.older)
            // 初始/所有换条件与分页的真实读回均先成立，之后才断言尚缺的原因信号。
            val initialChange = assertNotNull(first.page.change, "缺能力：首次实际Inbox读取必须显式REPLACE以禁止补位")
            assertEquals(HistoryChangeReason.REPLACE, initialChange.reason)
            val merged = assertNotNull(page.page.change)
            assertEquals(HistoryChangeReason.PAGE_MERGE, merged.reason)
            assertTrue(initialChange.scope === merged.scope); assertTrue(merged.version > initialChange.version)
            val searched = assertNotNull(query.page.change)
            assertEquals(HistoryChangeReason.REPLACE, searched.reason)
            assertTrue(searched.scope !== initialChange.scope)
            val selectedB = assertNotNull(ownerB.page.change)
            assertEquals(HistoryChangeReason.REPLACE, selectedB.reason)
            assertTrue(selectedB.scope !== searched.scope)
            val selectedA = assertNotNull(ownerA.page.change)
            assertEquals(HistoryChangeReason.REPLACE, selectedA.reason)
            assertTrue(selectedA.scope !== selectedB.scope)
            val enteredThread = assertNotNull(thread.window?.change)
            assertEquals(HistoryChangeReason.REPLACE, enteredThread.reason)
            assertTrue(enteredThread.scope !== selectedA.scope)
            val prepended = assertNotNull(older.window?.change)
            assertEquals(HistoryChangeReason.PAGE_MERGE, prepended.reason)
            assertTrue(enteredThread.scope === prepended.scope); assertTrue(prepended.version > enteredThread.version)
            assertTrue(transport.requests.isEmpty())
        } finally {
            source.close(); runCurrent(); saved.close()
        }
    }

    private suspend fun seedThreads(saved: FakeMessageRepository) {
        repeat(70) { index ->
            saved.insertIncomingIfAbsent(OWNER_A, "a-$index", "ea-$index", SYNTH_NUMBER, SYNTH_TEXT, index.toLong(), NOW_MS)
        }
        saved.insertIncomingIfAbsent(OWNER_B, "b-only", "eb-only", SYNTH_NUMBER, SYNTH_TEXT, 1, NOW_MS)
    }

    // 只有等待/失败屏障；所有 owner、游标、分页、记录和未读算法都委托给既有 repository。
    private class HeldOlderHistory(
        private val saved: MessageHistoryRepository,
        private val target: StoredMessageConversation,
        private val failHeldRead: Boolean = false,
    ) : MessageHistoryRepository by saved {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        var cancelled = false
            private set
        var returned: MessageThreadSlice? = null
            private set
        var firstPageCalls = 0
            private set
        var olderCalls = 0
            private set
        var requestedCursor: MessageThreadCursor? = null
            private set

        override suspend fun thread(identity: StoredMessageConversation, cursor: MessageThreadCursor?): MessageThreadSlice {
            if (identity != target) return saved.thread(identity, cursor)
            if (cursor == null) {
                firstPageCalls++
                return saved.thread(identity, cursor)
            }
            olderCalls++
            if (olderCalls != 1) return saved.thread(identity, cursor)
            requestedCursor = cursor
            entered.complete(Unit)
            try {
                withTimeout(2_000) { release.await() }
                if (failHeldRead) throw MessageStorageException()
                return saved.thread(identity, cursor).also { returned = it }
            } catch (cancelledRead: CancellationException) {
                cancelled = true
                throw cancelledRead
            } finally {
                exited.complete(Unit)
            }
        }
    }
}
