package com.vowifi.sw

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessageConversationActionsHostTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val other = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
    private val identity = StoredMessageConversation(owner, "155501")
    private val name = "conversation-actions.db"
    private val dbClass = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase").asSubclass(RoomDatabase::class.java)
    private fun open(): MessageRepository {
        val db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), dbClass, name)
            .setDriver(AndroidSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1)).build()
        return Class.forName("com.vowifi.sw.manager.data.AndroidMessageRepository").getConstructor(dbClass).newInstance(db) as MessageRepository
    }
    private fun request(ids: List<String>) = requireNotNull(ObservedIncomingReadRequest.create(owner, identity.key, ids))

    private suspend fun awaitStartupInbox(source: EngineMessageSource): MessageUiState.Inbox = withTimeout(5000) {
        source.state.first {
            it is MessageUiState.Inbox && !it.page.loading && it.page.items.size == 1 &&
                it.page.items.single().outgoingStatus == OutgoingMessageStatus.UNKNOWN
        } as MessageUiState.Inbox
    }

    @Test fun groupedCountsAndReadTransactionPreserveOtherOwnersAndManualReminderSemanticsAfterReopen() = runBlocking {
        var repo = open()
        try {
            repeat(2) { index -> repo.insertIncomingIfAbsent(owner, "a-$index", "engine-a-$index", "+155501", "synthetic A", index.toLong(), 1) }
            repeat(3) { index -> repo.insertIncomingIfAbsent(other, "b-$index", "engine-b-$index", "+155501", "synthetic B", index.toLong(), 1) }
            repo.insertIncomingIfAbsent(owner, "a-other", "engine-a-other", "+155502", "synthetic other thread", 3, 1)
            assertEquals(mapOf(owner to 3L, other to 3L), repo.countUnreadByOwner())
            assertEquals(6L, repo.countUnreadIncoming())
            assertTrue(repo.changeConversation(identity, MessageConversationAction.MARK_READ))
            assertEquals(mapOf(owner to 1L, other to 3L), repo.countUnreadByOwner())
            val read = repo.listConversation(identity).filterIsInstance<MessageRecord.Incoming>()
            assertTrue(read.all { it.isRead && it.readAtMs != null })
            assertEquals(3, repo.listByOwner(other).filterIsInstance<MessageRecord.Incoming>().count { !it.isRead })
            repo.insertQueued(owner, "manual-anchor", "+155501", "synthetic reminder", 10)
            assertTrue(repo.changeConversation(identity, MessageConversationAction.MARK_UNREAD))
            assertTrue(repo.changeConversation(identity, MessageConversationAction.MARK_UNREAD))
            assertEquals(mapOf(owner to 2L, other to 3L), repo.countUnreadByOwner())
            assertEquals(5L, repo.countUnreadIncoming())
            assertTrue(repo.changeConversation(identity, MessageConversationAction.MARK_READ))
            assertEquals(mapOf(owner to 1L, other to 3L), repo.countUnreadByOwner())
            repo.close(); repo = open()
            assertEquals(mapOf(owner to 1L, other to 3L), repo.countUnreadByOwner())
            assertTrue(repo.listConversation(identity).filterIsInstance<MessageRecord.Incoming>().all { it.isRead })
            assertEquals(4L, repo.countUnreadIncoming())
        } finally { repo.close() }
    }

    @Test fun failedReadTransactionKeepsBothIncomingUnreadAndTheManualMarker() = runBlocking {
        val repo = open()
        try {
            repo.insertQueued(owner, "anchor", "+155501", "synthetic reminder", 1)
            assertTrue(repo.changeConversation(identity, MessageConversationAction.MARK_UNREAD))
            repo.insertIncomingIfAbsent(owner, "incoming", "engine-incoming", "+155501", "synthetic incoming", 2, 2)
            assertEquals(mapOf(owner to 2L), repo.countUnreadByOwner())
            SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
                database.execSQL("CREATE TRIGGER fail_mark_read BEFORE UPDATE OF `read` ON messages BEGIN SELECT RAISE(ABORT, 'synthetic read failure'); END")
            }
            try { repo.changeConversation(identity, MessageConversationAction.MARK_READ); fail("必须保留真实事务失败") }
            catch (error: Exception) { assertEquals("MessageStorageException", error.javaClass.simpleName) }
            assertEquals(mapOf(owner to 2L), repo.countUnreadByOwner())
            assertEquals(2L, repo.countUnreadIncoming())
            assertFalse((repo.listConversation(identity).single { it.id == "incoming" } as MessageRecord.Incoming).isRead)
        } finally { repo.close() }
    }

    @Test fun explicitReadThroughTheProductionSourceReconcilesOnlyItsOwnNotificationAndCounts() = runBlocking {
        val repo = open()
        repo.insertIncomingIfAbsent(owner, "a", "engine-a", "+155501", "synthetic A", 1, 1)
        repo.insertIncomingIfAbsent(other, "b", "engine-b", "+155501", "synthetic B", 1, 1)
        repo.setLastOwner(owner)
        val reconciled = mutableListOf<Pair<StoredMessageConversation, Set<String>>>()
        val source = EngineMessageSource(EngineMessageRuntime({ emptyList() }, { error("不得连接引擎或发送") }, { 10L }),
            Dispatchers.Unconfined, repo, { null }, { "unused" }, object : MessagePersistenceObserver {
                override fun onSnapshot(owner: SimOwnerId, records: List<MessageRecord>, freshIncomingId: String?) { assertNull(freshIncomingId) }
                override fun onThreadUnread(conversation: StoredMessageConversation, ids: Set<String>) { reconciled += conversation to ids }
            })
        try {
            withTimeout(5000) { source.state.first { it is MessageUiState.Inbox && !it.page.loading && it.ownerId == owner } }
            assertEquals(MessageConversationActionResult.APPLIED, source.changeConversation(identity.handle.value, MessageConversationAction.MARK_READ))
            withTimeout(5000) { source.unreadSummary.first { it == MessageUnreadSummary.Ready(1, mapOf(other to 1L)) } }
            withTimeout(5000) { source.state.first { it is MessageUiState.Inbox && !it.page.loading && it.page.items.singleOrNull()?.unreadCount == 0L } }
            assertEquals(identity to emptySet<String>(), reconciled.last())
            assertFalse((repo.listByOwner(other).single() as MessageRecord.Incoming).isRead)
            assertEquals(1L, repo.countUnreadIncoming())
        } finally { source.close(); repo.close() }
    }

    @Test fun markingUnreadDoesNotReloadLineLabelsOrRawMessages() = runBlocking {
        val stored = open()
        stored.insertQueued(owner, "sent", identity.key, "synthetic", 1)
        var labelLoads = 0
        var recordLoads = 0
        val repo = object : MessageRepository by stored {
            override suspend fun ownerLabels(owners: List<SimOwnerId>): Map<SimOwnerId, MessageOwnerLabel> {
                labelLoads++; return stored.ownerLabels(owners)
            }
            override suspend fun listByOwner(ownerId: SimOwnerId): List<MessageRecord> {
                recordLoads++; return stored.listByOwner(ownerId)
            }
        }
        val source = EngineMessageSource(EngineMessageRuntime({ emptyList() }, { error("offline") }, { 10L }),
            Dispatchers.Unconfined, repo, { null }, { "unused" })
        var observing: Job? = null
        try {
            awaitStartupInbox(source)
            val labelsBefore = labelLoads
            val recordsBefore = recordLoads
            val pageLoading = mutableListOf<Boolean>()
            observing = launch(Dispatchers.Unconfined) { source.state.collect { if (it is MessageUiState.Inbox) pageLoading += it.page.loading } }
            assertEquals(MessageConversationActionResult.APPLIED, source.changeConversation(identity.handle.value, MessageConversationAction.MARK_UNREAD))
            withTimeout(5000) { source.state.first { it is MessageUiState.Inbox && !it.page.loading && it.page.items.singleOrNull()?.unreadCount == 1L } }
            observing.cancel()
            assertEquals("标未读不得重读线路备注并插入加载提示", labelsBefore, labelLoads)
            assertEquals("手动提醒不改变消息正文，不应重新发布整份记录", recordsBefore, recordLoads)
            assertFalse("已有列表不能闪出分页加载态", pageLoading.any { it })
        } finally { observing?.cancel(); source.close(); stored.close() }
    }

    @Test fun inboxReadinessWaitsForStartupRecoveryRefresh() = runBlocking {
        val stored = open()
        stored.insertQueued(owner, "sent", identity.key, "synthetic", 1)
        val recoveryEntered = CompletableDeferred<Unit>()
        val releaseRecovery = CompletableDeferred<Unit>()
        val repo = object : MessageRepository by stored {
            override suspend fun interruptLeftoverQueued(ownerId: SimOwnerId, nowMs: Long) {
                recoveryEntered.complete(Unit)
                releaseRecovery.await()
                stored.interruptLeftoverQueued(ownerId, nowMs)
            }
        }
        val source = EngineMessageSource(EngineMessageRuntime({ emptyList() }, { error("offline") }, { 10L }),
            Dispatchers.Unconfined, repo, { null }, { "unused" })
        var waiting: Deferred<MessageUiState.Inbox>? = null
        try {
            withTimeout(5000) { recoveryEntered.await() }
            val initial = withTimeout(5000) {
                source.state.first { it is MessageUiState.Inbox && !it.page.loading && it.page.items.size == 1 }
            } as MessageUiState.Inbox
            assertEquals(OutgoingMessageStatus.QUEUED, initial.page.items.single().outgoingStatus)
            assertFalse(releaseRecovery.isCompleted)
            val ready = async(start = CoroutineStart.UNDISPATCHED) { awaitStartupInbox(source) }
            waiting = ready
            assertFalse("QUEUED首个Inbox不能表示启动恢复刷新已完成", ready.isCompleted)
            releaseRecovery.complete(Unit)
            assertEquals(OutgoingMessageStatus.UNKNOWN, ready.await().page.items.single().outgoingStatus)
        } finally { waiting?.cancel(); source.close(); releaseRecovery.complete(Unit); stored.close() }
    }

    @Test fun unreadReminderPersistsForOutgoingOnlyAndClearsOnlyWhenObserved() = runBlocking {
        var repo = open()
        try {
            repo.insertQueued(owner, "sent", identity.key, "synthetic", 1)
            repo.markFailed(owner, "sent", 2)
            assertTrue(repo.changeConversation(identity, MessageConversationAction.MARK_UNREAD))
            repo.close(); repo = open()
            assertEquals(1L, repo.countUnreadIncoming())
            assertEquals(1L, repo.history.summaries(MessageSummaryRequest(owner)).items.single().unreadCount)
            repo.markIncomingRead(request(listOf("unknown")), 3)
            assertEquals(1L, repo.countUnreadIncoming())
            repo.markIncomingRead(requireNotNull(ObservedIncomingReadRequest.create(other, identity.key, listOf("sent"))), 3)
            assertEquals(1L, repo.countUnreadIncoming())
            repo.markIncomingRead(request(listOf("sent")), 4)
            assertEquals(0L, repo.countUnreadIncoming())
            assertEquals(OutgoingMessageStatus.FAILED, (repo.listByOwner(owner).single() as MessageRecord.Outgoing).status)
        } finally { repo.close() }
    }

    @Test fun deleteIsScopedAndRemovesBothDirectionsAndManualUnreadAcrossReopen() = runBlocking {
        var repo = open()
        try {
            repo.insertQueued(owner, "sent", identity.key, "synthetic", 1)
            repo.insertIncomingIfAbsent(owner, "received", "engine-a", identity.key, "synthetic", 2, 2)
            repo.insertIncomingIfAbsent(other, "other-sim", "engine-b", identity.key, "synthetic", 3, 3)
            repo.insertIncomingIfAbsent(owner, "other-peer", "engine-c", "155502", "synthetic", 4, 4)
            repo.markIncomingRead(request(listOf("received")), 5)
            assertTrue(repo.changeConversation(identity, MessageConversationAction.MARK_UNREAD))
            assertTrue(repo.changeConversation(identity, MessageConversationAction.DELETE))
            repo.close(); repo = open()
            assertFalse(identity in repo.listConversations())
            assertEquals(listOf("other-peer"), repo.listByOwner(owner).map { it.id })
            assertEquals(listOf("other-sim"), repo.listByOwner(other).map { it.id })
            assertEquals(2L, repo.countUnreadIncoming())
            assertFalse(repo.changeConversation(identity, MessageConversationAction.DELETE))
        } finally { repo.close() }
    }

    @Test fun deleteFailureRollsBackMessagesAndUnreadMarker() = runBlocking {
        var repo = open()
        repo.insertQueued(owner, "sent", identity.key, "synthetic", 1)
        assertTrue(repo.changeConversation(identity, MessageConversationAction.MARK_UNREAD)); repo.close()
        val path = RuntimeEnvironment.getApplication().getDatabasePath(name)
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE TRIGGER reject_delete BEFORE DELETE ON messages BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        }
        repo = open()
        try {
            try { repo.changeConversation(identity, MessageConversationAction.DELETE); fail("must fail") }
            catch (e: Exception) { assertEquals("MessageStorageException", e.javaClass.simpleName) }
            assertEquals(1, repo.listByOwner(owner).size)
            assertEquals(1L, repo.countUnreadIncoming())
        } finally { repo.close() }
    }

    @Test fun offlineProductionSourceUpdatesListBadgeAndNotificationWithoutTransport() = runBlocking {
        val repo = open()
        repo.insertQueued(owner, "sent", identity.key, "synthetic", 1)
        val reconciled = mutableListOf<StoredMessageConversation>()
        val source = EngineMessageSource(EngineMessageRuntime({ emptyList() }, { error("offline action must not access transport") }, { 10L }),
            Dispatchers.Unconfined, repo, { null }, { "unused" }, object : MessagePersistenceObserver {
                override fun onSnapshot(owner: SimOwnerId, records: List<MessageRecord>, freshIncomingId: String?) {
                    assertNull(freshIncomingId)
                }
                override fun onThreadUnread(conversation: StoredMessageConversation, ids: Set<String>) { reconciled += conversation }
            })
        try {
            withTimeout(5000) { source.state.first { it is MessageUiState.Inbox } }
            assertEquals(MessageConversationActionResult.APPLIED, source.changeConversation(identity.handle.value, MessageConversationAction.MARK_UNREAD))
            withTimeout(5000) { source.state.first { it is MessageUiState.Inbox && it.page.items.singleOrNull()?.unreadCount == 1L } }
            withTimeout(5000) { source.unreadSummary.first { it == MessageUnreadSummary.Ready(1, mapOf(owner to 1L)) } }
            assertEquals(1L, repo.countUnreadIncoming())
            source.markVisibleRead(request(listOf("sent")))
            withTimeout(5000) { source.unreadSummary.first { it == MessageUnreadSummary.Ready(0) } }
            withTimeout(5000) { source.state.first { it is MessageUiState.Inbox && it.page.items.singleOrNull()?.unreadCount == 0L } }
            assertEquals(MessageConversationActionResult.APPLIED, source.changeConversation(identity.handle.value, MessageConversationAction.DELETE))
            withTimeout(5000) { source.state.first {
                it is MessageUiState.Empty || it is MessageUiState.Inbox && it.page.items.isEmpty() ||
                    it is MessageUiState.Unavailable && source.historyChoices.value.let { choices -> choices.storageAvailable && choices.lines.isEmpty() }
            } }
            assertTrue(identity in reconciled)
            assertEquals(0L, repo.countUnreadIncoming())
            assertEquals(MessageConversationActionResult.MISSING, source.changeConversation(identity.handle.value, MessageConversationAction.DELETE))
        } finally { source.close(); repo.close() }
    }
}
