package com.vowifi.sw

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.MessageRepository
import com.vowifi.sw.manager.data.AndroidMessageNotifications
import com.vowifi.sw.manager.data.MessageVisibility
import com.vowifi.sw.manager.data.ObservedIncomingReadRequest
import com.vowifi.sw.manager.data.PeerNumberNormalizer
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.StoredMessageConversation
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessageUnreadPreviewRoomTest {
    @Test fun twoVisibleRowsLeaveEightUnreadInRoomAndNotificationWithoutTouchingOtherOwner() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val databaseClass = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase").asSubclass(RoomDatabase::class.java)
        val database = Room.inMemoryDatabaseBuilder(app, databaseClass).setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1)).build()
        val repository = Class.forName("com.vowifi.sw.manager.data.AndroidMessageRepository")
            .getConstructor(databaseClass).newInstance(database) as MessageRepository
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val other = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
        val peer = "+12025550123"
        val conversation = StoredMessageConversation(owner, PeerNumberNormalizer.normalize(peer))
        val otherConversation = StoredMessageConversation(other, conversation.key)
        val adapter = AndroidMessageNotifications(app, Activity::class.java, R.drawable.ic_stat_message)
        val manager = app.getSystemService(NotificationManager::class.java)
        val lookupJob = SupervisorJob()
        val scope = CoroutineScope(lookupJob + Dispatchers.IO)
        val lookup: suspend (StoredMessageConversation) -> List<MessageRecord> = repository::unreadNotificationPreview
        adapter.javaClass.methods.single { it.name.startsWith("bindPreviewLookup") }.invoke(adapter, scope, lookup)
        try {
            repeat(10) { index -> repository.insertIncomingIfAbsent(owner, "row-$index", "engine-$index", peer, "Synthetic $index", index.toLong(), 100) }
            repository.insertIncomingIfAbsent(other, "other-row", "other-engine", peer, "Synthetic other owner", 100, 100)
            val rows = repository.listConversation(conversation)
            val otherBefore = repository.listConversation(otherConversation)
            adapter.onSnapshot(owner, rows, "row-9")
            adapter.onSnapshot(other, otherBefore, "other-row")
            adapter.awaitNotifications()
            assertEquals(10, manager.activeNotifications.single { it.tag == conversation.handle.value }.notification.number)
            val otherNotification = manager.activeNotifications.single { it.tag == otherConversation.handle.value }.notification
            val reads = mutableListOf<String>()
            val visibility = MessageVisibility(adapter::visible) { request ->
                val update = runBlocking { repository.markIncomingRead(request, 300) }
                reads.addAll(update.updatedIds)
                adapter.onRead(request.ownerId, update.updatedIds.toSet())
            }
            val state = MessageUiState.Ready(rows, MessageOrigin.ENGINE, ownerId = owner)
            visibility.setResumed(true)
            visibility.observe(state, conversation.handle, listOf("row-8", "row-9", "other-row", "unknown"))
            withTimeout(10_000) { lookupJob.children.toList().joinAll() }
            adapter.awaitNotifications()
            assertEquals(setOf("row-8", "row-9"), reads.toSet())
            assertEquals(2, reads.size)
            val stored = repository.listConversation(conversation).filterIsInstance<MessageRecord.Incoming>()
            assertEquals((0..7).map { "row-$it" }.toSet(), stored.filterNot { it.isRead }.map { it.id }.toSet())
            assertEquals(setOf("row-8", "row-9"), stored.filter { it.isRead }.map { it.id }.toSet())
            assertEquals(9L, repository.countUnreadIncoming())
            assertEquals(otherBefore, repository.listConversation(otherConversation))
            assertEquals(8, manager.activeNotifications.single { it.tag == conversation.handle.value }.notification.number)
            visibility.setResumed(false)
            visibility.observe(state, conversation.handle, listOf("row-0"))
            adapter.onThreadUnread(conversation, stored.filterNot { it.isRead }.map { it.id }.toSet())
            withTimeout(10_000) { lookupJob.children.toList().joinAll() }
            adapter.awaitNotifications()
            val notification = manager.activeNotifications.single { it.tag == conversation.handle.value }.notification
            assertEquals(8, notification.number)
            val style = Notification.Builder.recoverBuilder(app, notification).style as Notification.MessagingStyle
            assertEquals((2..7).map { "Synthetic $it" }, style.messages.map { it.text.toString() })
            assertEquals(9L, repository.countUnreadIncoming())
            assertEquals(2, reads.size)
            val otherAfter = manager.activeNotifications.single { it.tag == otherConversation.handle.value }.notification
            assertEquals(otherNotification.number, otherAfter.number)
            assertEquals(otherNotification.extras.getString(Notification.EXTRA_TEXT), otherAfter.extras.getString(Notification.EXTRA_TEXT))
            assertEquals(2, manager.activeNotifications.size)
        } finally {
            adapter.javaClass.methods.single { it.name.startsWith("unbindPreviewLookup") }.invoke(adapter)
            scope.cancel()
            withTimeout(10_000) { lookupJob.join() }
            adapter.awaitNotifications()
            manager.cancelAll()
            repository.close()
        }
    }

    @Test fun latestSixQueryExcludesReadOutgoingOtherOwnerAndOtherPeer() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val databaseClass = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase").asSubclass(RoomDatabase::class.java)
        val database = Room.inMemoryDatabaseBuilder(app, databaseClass).setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1)).build()
        val repository = Class.forName("com.vowifi.sw.manager.data.AndroidMessageRepository")
            .getConstructor(databaseClass).newInstance(database) as MessageRepository
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val other = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
        val peer = "+12025550123"
        val conversation = StoredMessageConversation(owner, PeerNumberNormalizer.normalize(peer))
        try {
            for (index in 0..9) repository.insertIncomingIfAbsent(owner, "row-$index", "engine-$index", peer, "消息 $index", 100, 100)
            repository.insertIncomingIfAbsent(other, "other-owner", "engine-other", peer, "另一线路", 200, 200)
            repository.insertIncomingIfAbsent(owner, "other-peer", "engine-peer", "+12025550124", "另一号码", 200, 200)
            repository.insertQueued(owner, "outgoing", peer, "出向", 200)
            repository.markIncomingRead(requireNotNull(ObservedIncomingReadRequest.create(owner, conversation.key, listOf("row-9"))), 300)
            val unreadBefore = repository.countUnreadIncoming()
            val recordsBefore = repository.listConversation(conversation)
            val rows = repository.unreadNotificationPreview(conversation)
            assertEquals((3..8).map { "row-$it" }, rows.map { it.id })
            assertEquals(11, repository.countUnreadIncoming().toInt())
            assertTrue(repository.unreadNotificationPreview(StoredMessageConversation(other, "ExampleBank")).isEmpty())
            assertEquals(unreadBefore, repository.countUnreadIncoming())
            assertEquals(recordsBefore, repository.listConversation(conversation))
        } finally { repository.close() }
    }
}
