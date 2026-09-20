package com.vowifi.sw

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.MessageRepository
import com.vowifi.sw.manager.data.MessageSummaryRequest
import com.vowifi.sw.manager.data.ObservedIncomingReadRequest
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.StoredMessageConversation
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessageHistoryPagingRoomTest {
    private fun owner(index: Int) = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + index.toString().padStart(43, 'a')))

    @Test fun scaleOwnerAggregatesAndSummaryPagesStayBounded() = runBlocking {
        val name = "history-scale-${UUID.randomUUID()}.db"
        val repository = open(name)
        try {
            repository.countUnreadIncoming()
            seed(name, 100, 10, 10)
            val started = System.nanoTime()
            val firstOwners = repository.history.owners()
            val lastOwners = repository.history.owners(firstOwners.next)
            assertEquals(50, firstOwners.items.size)
            assertEquals(50, lastOwners.items.size)
            assertNull(lastOwners.next)
            assertEquals(100, (firstOwners.items + lastOwners.items).map { it.owner }.distinct().size)
            (firstOwners.items + lastOwners.items).forEach {
                assertEquals(10L, it.conversationCount)
                assertEquals(100L, it.messageCount)
                assertEquals(100L, it.unreadCount)
            }
            for (index in 0 until 100) {
                val page = repository.history.summaries(MessageSummaryRequest(owner(index)))
                assertEquals(10, page.items.size)
                assertNull(page.next)
                assertTrue(page.items.all { it.identity.owner == owner(index) && it.messageCount == 10L && it.unreadCount == 10L })
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            println("history-scale owners=100 conversations=1000 messages=10000 queryMs=$elapsedMs")
            assertTrue("100-owner query pass exceeded 15 seconds", elapsedMs < 15_000)
        } finally { repository.close(); RuntimeEnvironment.getApplication().deleteDatabase(name) }
    }

    @Test fun equalTimestampSummaryCursorsAndSearchReachBeyondFirstBatch() = runBlocking<Unit> {
        val name = "history-search-${UUID.randomUUID()}.db"
        val repository = open(name)
        try {
            repository.countUnreadIncoming()
            seed(name, 2, 160, 1)
            val first = repository.history.summaries(MessageSummaryRequest(owner(0)))
            val second = repository.history.summaries(MessageSummaryRequest(owner(0), cursor = first.next))
            val third = repository.history.summaries(MessageSummaryRequest(owner(0), cursor = second.next))
            val last = repository.history.summaries(MessageSummaryRequest(owner(0), cursor = third.next))
            assertEquals(listOf(50, 50, 50, 10), listOf(first, second, third, last).map { it.items.size })
            assertNull(last.next)
            val all = listOf(first, second, third, last).flatMap { it.items }
            assertEquals(160, all.map { it.identity.key }.distinct().size)
            assertEquals(all.map { it.latestMessageId }.sortedDescending(), all.map { it.latestMessageId })
            val matches = repository.history.summaries(MessageSummaryRequest(owner(0), "nEeDlE"))
            assertEquals(listOf("15550000"), matches.items.map { it.identity.key })
            assertNull(matches.next)
            val searchFirst = repository.history.summaries(MessageSummaryRequest(owner(0), "synthetic"))
            val searchSecond = repository.history.summaries(MessageSummaryRequest(owner(0), "synthetic", searchFirst.next))
            val searchThird = repository.history.summaries(MessageSummaryRequest(owner(0), "synthetic", searchSecond.next))
            val searchLast = repository.history.summaries(MessageSummaryRequest(owner(0), "synthetic", searchThird.next))
            assertEquals(listOf(50, 50, 50, 8), listOf(searchFirst, searchSecond, searchThird, searchLast).map { it.items.size })
            assertEquals(158, listOf(searchFirst, searchSecond, searchThird, searchLast).flatMap { it.items }.map { it.identity.key }.distinct().size)
            assertNull(searchLast.next)
            assertEquals(0, repository.history.summaries(MessageSummaryRequest(owner(0), "token=")).items.size)
            assertEquals(0, repository.history.summaries(MessageSummaryRequest(owner(0), "hidden-tail")).items.size)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.history.summaries(MessageSummaryRequest(owner(1), cursor = first.next)) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.history.summaries(MessageSummaryRequest(owner(0), "changed", first.next)) }
            }
        } finally { repository.close(); RuntimeEnvironment.getApplication().deleteDatabase(name) }
    }

    @Test fun latestAndOlderThreadPagesPreserveTiesIsolationAndCompleteUnreadIds() = runBlocking {
        val name = "history-thread-${UUID.randomUUID()}.db"
        val repository = open(name)
        try {
            repository.countUnreadIncoming()
            seed(name, 2, 2, 123)
            val identity = StoredMessageConversation(owner(0), "15550000")
            val latest = repository.history.thread(identity)
            val older = repository.history.thread(identity, latest.older)
            val oldest = repository.history.thread(identity, older.older)
            assertEquals(listOf(50, 50, 23), listOf(latest, older, oldest).map { it.records.size })
            assertNull(oldest.older)
            val records = oldest.records + older.records + latest.records
            assertEquals(123, records.map { it.id }.distinct().size)
            assertEquals(records.map { it.id }.sorted(), records.map { it.id })
            assertTrue(records.all { it.id.startsWith("000-000-") })
            assertEquals(records.map { it.id }.toSet(), repository.history.unreadIds(identity).toSet())
            assertEquals(492L, repository.countUnreadIncoming())
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.history.thread(StoredMessageConversation(owner(1), identity.key), latest.older) }
            }
            assertEquals(0, repository.history.thread(StoredMessageConversation(owner(0), "missing")).records.size)
        } finally { repository.close(); RuntimeEnvironment.getApplication().deleteDatabase(name) }
    }

    @Test fun countsAndUnreadIdsReflectWritesWithoutLoadingBodiesOrChangingLabels() = runBlocking {
        val name = "history-updates-${UUID.randomUUID()}.db"
        val repository = open(name)
        try {
            repository.countUnreadIncoming()
            seed(name, 1, 1, 50)
            val identity = StoredMessageConversation(owner(0), "15550000")
            val labels = repository.ownerLabels(listOf(owner(0)))
            assertNull(repository.history.thread(identity).older)
            repository.insertQueued(owner(0), "new-outgoing", identity.key, "synthetic outgoing", 9)
            repository.bindAccepted(owner(0), "new-outgoing", "accepted-engine", 10)
            repository.markIncomingRead(requireNotNull(ObservedIncomingReadRequest.create(owner(0), identity.key, listOf("000-000-000"))), 11)
            val summary = repository.history.summaries(MessageSummaryRequest(owner(0))).items.single()
            assertEquals(51L, summary.messageCount)
            assertEquals(49L, summary.unreadCount)
            assertEquals("new-outgoing", summary.latestMessageId)
            assertEquals("synthetic outgoing", summary.latestPreview)
            assertEquals(com.vowifi.sw.manager.model.OutgoingMessageStatus.ACCEPTED, summary.outgoingStatus)
            repository.applyOutgoingStatus(owner(0), "accepted-engine", com.vowifi.sw.manager.model.SmsEngineStatus.FORWARDED, 12)
            assertEquals(com.vowifi.sw.manager.model.SmsEngineStatus.FORWARDED,
                repository.history.summaries(MessageSummaryRequest(owner(0))).items.single().engineStatus)
            assertEquals(49, repository.history.unreadIds(identity).size)
            assertFalse(repository.history.unreadIds(identity).contains("000-000-000"))
            val latest = repository.history.thread(identity)
            assertEquals("new-outgoing", latest.records.last().id)
            assertEquals(listOf("000-000-000"), repository.history.thread(identity, latest.older).records.map { it.id })
            assertEquals(49L, repository.history.owners().items.single().unreadCount)
            assertEquals(labels, repository.ownerLabels(listOf(owner(0))))
        } finally { repository.close(); RuntimeEnvironment.getApplication().deleteDatabase(name) }
    }

    private fun seed(name: String, ownerCount: Int, conversations: Int, messages: Int) {
        val app = RuntimeEnvironment.getApplication()
        app.openOrCreateDatabase(name, 0, null).use { database ->
            database.beginTransaction()
            try {
                database.compileStatement("INSERT INTO messages (message_id, sim_owner_id, direction, peer_address, conversation_key, body, message_at_ms, status_updated_at_ms, `read`) VALUES (?, ?, 'INCOMING', ?, ?, ?, 7, 7, 0)").use { statement ->
                    for (ownerIndex in 0 until ownerCount) for (conversation in 0 until conversations) for (message in 0 until messages) {
                        val peer = "1555" + conversation.toString().padStart(4, '0')
                        val body = when (conversation) {
                            0 -> "Needle synthetic"
                            1 -> "token=synthetic"
                            2 -> "x".repeat(90) + "hidden-tail"
                            else -> "synthetic"
                        }
                        statement.bindString(1, "%03d-%03d-%03d".format(ownerIndex, conversation, message))
                        statement.bindString(2, "sim-owner-v1:" + ownerIndex.toString().padStart(43, 'a'))
                        statement.bindString(3, peer)
                        statement.bindString(4, peer)
                        statement.bindString(5, body)
                        statement.executeInsert()
                    }
                }
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        }
    }

    private fun open(name: String): MessageRepository {
        val type = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase").asSubclass(RoomDatabase::class.java)
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), type, name)
            .setDriver(AndroidSQLiteDriver()).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1)).build()
        return Class.forName("com.vowifi.sw.manager.data.AndroidMessageRepository").getConstructor(type)
            .newInstance(database) as MessageRepository
    }
}
