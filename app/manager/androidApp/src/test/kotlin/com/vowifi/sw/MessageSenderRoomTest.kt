package com.vowifi.sw

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.MessageRecord
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessageSenderRoomTest {
    @Test fun existingAlphanumericSenderIsSearchableAndMatchesNotification() = runBlocking {
        val name = "sender-${UUID.randomUUID()}.db"
        val type = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase").asSubclass(RoomDatabase::class.java)
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), type, name)
            .setDriver(AndroidSQLiteDriver()).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1)).build()
        val repository = Class.forName("com.vowifi.sw.manager.data.AndroidMessageRepository")
            .getConstructor(type).newInstance(database) as MessageRepository
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        try {
            repository.insertIncomingIfAbsent(owner, "alpha", "engine-alpha", "ExampleVerify", "synthetic", 100L, 1)
            val row = repository.history.summaries(MessageSummaryRequest(owner, "exampleverify")).items.single()
            assertEquals("ExampleVerify", row.peerDisplay)
            val records = repository.history.thread(row.identity).records
            assertEquals("ExampleVerify", (records.single() as MessageRecord.Incoming).peerLabel)
            assertEquals(row.peerDisplay, MessageNotificationPresentation.from(row.identity, records)?.peer)
            assertEquals(1L, repository.countUnreadIncoming())
        } finally {
            repository.close()
            RuntimeEnvironment.getApplication().deleteDatabase(name)
        }
    }
}
