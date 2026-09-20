package com.vowifi.sw

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.MessageRepository
import com.vowifi.sw.manager.data.ObservedIncomingReadRequest
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.SmsEngineStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessageUnreadRoomTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val databaseName = "unread-summary-${UUID.randomUUID()}.db"
    private val ownerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val ownerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
    private val databaseClass = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase")
        .asSubclass(RoomDatabase::class.java)

    @After fun cleanup() { context.deleteDatabase(databaseName) }

    @Test fun aggregateReadsAllOwnersAndSurvivesReadDuplicateAndReopen() = runBlocking {
        val repository = openRepository()
        try {
            assertEquals(0L, repository.countUnreadIncoming())
            for ((owner, prefix, count) in listOf(Triple(ownerA, "a", 2), Triple(ownerB, "b", 3))) {
                for (index in 1..count) repository.insertIncomingIfAbsent(
                    owner, "$prefix-$index", "engine-$prefix-$index", "155501", "synthetic", index.toLong(), index.toLong(),
                )
            }
            repository.setLastOwner(ownerA)
            repository.insertQueued(ownerA, "outgoing", "155501", "synthetic", 1)
            repository.bindAccepted(ownerA, "outgoing", "engine-outgoing", 2)
            repository.applyOutgoingStatus(ownerA, "engine-outgoing", SmsEngineStatus.DELIVERED, 3)
            assertEquals(5L, repository.countUnreadIncoming())
            val request = requireNotNull(ObservedIncomingReadRequest.create(ownerA, "155501", listOf("a-1")))
            assertEquals(1, repository.markIncomingRead(request, 9000).updatedCount)
            assertEquals(4L, repository.countUnreadIncoming())
            assertFalse(repository.insertIncomingIfAbsent(ownerA, "duplicate", "engine-a-1", "155501", "synthetic", 10, 10).inserted)
            assertEquals(4L, repository.countUnreadIncoming())
            assertEquals(3, repository.listByOwner(ownerB).filterIsInstance<MessageRecord.Incoming>().count { !it.isRead })
            assertEquals(listOf("a-1", "outgoing", "a-2"), repository.listByOwner(ownerA).map { it.id })
        } finally { repository.close() }
        val reopened = openRepository()
        try {
            assertEquals(4L, reopened.countUnreadIncoming())
            assertEquals(ownerA, reopened.lastOwner())
        } finally { reopened.close() }
    }

    @Test fun productionV1MigrationLeavesHistoricalOwnersWithZeroUnread() = runBlocking {
        val path = context.getDatabasePath(databaseName)
        requireNotNull(path.parentFile).mkdirs()
        val schema = JSONObject(File(
            System.getProperty("message.schema.directory"), "${databaseClass.name}/1.json",
        ).readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (entry in 0 until indices.length()) {
                    sqlite.execSQL(indices.getJSONObject(entry).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) sqlite.execSQL(setup.getString(index))
            for ((index, wire) in listOf("sim-owner-v1:" + "a".repeat(43), "sim-owner-v1:" + "b".repeat(43)).withIndex()) {
                sqlite.execSQL(
                    "INSERT INTO messages(message_id,sim_owner_id,direction,peer_address,conversation_key,body,message_at_ms,status_updated_at_ms) VALUES (?,?,'INCOMING','155501','155501','synthetic',1,2)",
                    arrayOf("old-$index", wire),
                )
            }
            sqlite.version = 1
        }
        val repository = openRepository()
        try {
            assertEquals(0L, repository.countUnreadIncoming())
            assertTrue(repository.listByOwner(ownerA).filterIsInstance<MessageRecord.Incoming>().single().isRead)
            assertTrue(repository.listByOwner(ownerB).filterIsInstance<MessageRecord.Incoming>().single().isRead)
        } finally { repository.close() }
    }

    private fun openRepository(): MessageRepository {
        val migration = Class.forName("com.vowifi.sw.manager.data.MessageReadMigrationKt")
            .getMethod("getMESSAGE_READ_MIGRATION_1_2").invoke(null) as Migration
        val database = Room.databaseBuilder(context, databaseClass, databaseName)
            .addMigrations(migration)
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1))
            .build()
        return Class.forName("com.vowifi.sw.manager.data.AndroidMessageRepository")
            .getConstructor(databaseClass).newInstance(database) as MessageRepository
    }
}
