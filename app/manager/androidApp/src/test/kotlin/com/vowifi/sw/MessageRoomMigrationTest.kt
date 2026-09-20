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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
class MessageRoomMigrationTest {
    private val databaseName = "task33-private-migration.db"
    private val databaseClass = Class.forName(
        "com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase",
    ).asSubclass(RoomDatabase::class.java)
    private val ownerWire = "sim-owner-v1:" + "a".repeat(43)
    private val owner = requireNotNull(SimOwnerId.parse(ownerWire))
    private val peer = "15550000001"
    private val path get() = RuntimeEnvironment.getApplication().getDatabasePath(databaseName)

    @Test fun preservesV1RecordsAndIdentityWhenProductionMigrationOpensAndReopens() = runBlocking {
        seedV1()
        val repository = openRepository()
        try {
            val old = repository.listByOwner(owner).filterIsInstance<MessageRecord.Incoming>().single()
            assertEquals("old-incoming", old.id)
            assertEquals("synthetic historical body", old.preview)
            assertTrue(old.isRead)
            assertEquals(123L, old.readAtMs)
            assertEquals(owner, repository.lastOwner())
            assertTrue(repository.insertIncomingIfAbsent(owner, "new", "new-engine", peer, "synthetic", 200, 200).inserted)
            val fresh = repository.listByOwner(owner).filterIsInstance<MessageRecord.Incoming>().single { it.id == "new" }
            assertFalse(fresh.isRead)
            assertNull(fresh.readAtMs)
            assertFalse(repository.insertIncomingIfAbsent(owner, "duplicate", "new-engine", peer, "synthetic", 200, 200).inserted)
        } finally {
            repository.close()
        }
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals("2", scalar(sqlite, "PRAGMA user_version"))
            assertEquals(schema(2).getString("identityHash"), scalar(sqlite, "SELECT identity_hash FROM room_master_table WHERE id=42"))
            assertEquals("2", scalar(sqlite, "SELECT count(*) FROM sqlite_master WHERE type='index' AND name LIKE 'index_messages_%'"))
        }
        val reopened = openRepository()
        try {
            assertEquals(3, reopened.listByOwner(owner).size)
            assertFalse(reopened.listByOwner(owner).filterIsInstance<MessageRecord.Incoming>().single { it.id == "new" }.isRead)
        } finally {
            reopened.close()
        }
    }

    @Test fun rollsBackFirstChunkWhenSecondChunkSqlFails() = runBlocking {
        seedV1()
        val migrated = openRepository()
        try {
            assertEquals(2, migrated.listByOwner(owner).size)
        } finally {
            migrated.close()
        }
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            sqlite.beginTransaction()
            try {
                for (index in 0 until 2005) {
                    sqlite.execSQL(
                        "INSERT INTO messages (message_id,sim_owner_id,direction,peer_address,conversation_key,body,message_at_ms,status_updated_at_ms,`read`) VALUES (?,?,'INCOMING',?,?,'synthetic',200,200,0)",
                        arrayOf("bulk-$index", ownerWire, peer, peer),
                    )
                }
                sqlite.execSQL("CREATE TRIGGER fail_second_chunk BEFORE UPDATE OF `read` ON messages WHEN OLD.message_id='bulk-996' BEGIN SELECT RAISE(ABORT, 'task33 injected second chunk'); END")
                sqlite.setTransactionSuccessful()
            } finally {
                sqlite.endTransaction()
            }
        }
        val repository = openRepository()
        val request = requireNotNull(ObservedIncomingReadRequest.create(owner, peer, (0 until 2005).map { "bulk-$it" }))
        try {
            try {
                repository.markIncomingRead(request, 9000)
                fail("second chunk must fail")
            } catch (error: Exception) {
                assertEquals("com.vowifi.sw.manager.data.MessageStorageException", error.javaClass.name)
                assertEquals(2005, repository.listByOwner(owner).filterIsInstance<MessageRecord.Incoming>().count { !it.isRead })
            }
        } finally {
            repository.close()
        }
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            assertEquals("0", scalar(sqlite, "SELECT count(*) FROM messages WHERE message_id LIKE 'bulk-%' AND read_at_ms IS NOT NULL"))
            sqlite.execSQL("DROP TRIGGER fail_second_chunk")
        }
        val reopened = openRepository()
        try {
            assertEquals(2005, reopened.markIncomingRead(request, 9000).updatedCount)
            assertEquals(0, reopened.markIncomingRead(request, 9999).updatedCount)
            assertTrue(reopened.listByOwner(owner).filterIsInstance<MessageRecord.Incoming>().all { it.isRead })
        } finally {
            reopened.close()
        }
    }

    @Test fun rejectsInvalidV1SchemaInsteadOfDestructivelyRecreating() = runBlocking {
        seedV1()
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP INDEX index_messages_owner_conversation_sort")
        }
        val repository = openRepository()
        try {
            try {
                repository.listByOwner(owner)
                fail("Room schema validation must reject missing index")
            } catch (error: Exception) {
                assertEquals("com.vowifi.sw.manager.data.MessageStorageException", error.javaClass.name)
                SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use {
                    assertEquals("1", scalar(it, "PRAGMA user_version"))
                    assertEquals("2", scalar(it, "SELECT count(*) FROM messages"))
                }
            }
        } finally {
            repository.close()
        }
    }

    private fun openRepository(): MessageRepository {
        val migration = Class.forName("com.vowifi.sw.manager.data.MessageReadMigrationKt")
            .getMethod("getMESSAGE_READ_MIGRATION_1_2").invoke(null) as Migration
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), databaseClass, databaseName)
            .addMigrations(migration)
            .setDriver(AndroidSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1))
            .build()
        return Class.forName("com.vowifi.sw.manager.data.AndroidMessageRepository")
            .getConstructor(databaseClass).newInstance(database) as MessageRepository
    }

    private fun seedV1() {
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            val schema = schema(1)
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
            sqlite.execSQL("INSERT INTO message_metadata VALUES ('last_owner', ?)", arrayOf(ownerWire))
            for (direction in listOf("INCOMING", "OUTGOING")) {
                sqlite.execSQL(
                    "INSERT INTO messages VALUES (?,?,?, ?,?,'synthetic historical body',100,?,NULL,NULL,123,NULL)",
                    arrayOf("old-${direction.lowercase()}", ownerWire, direction, peer, peer, "engine-$direction"),
                )
            }
            sqlite.version = 1
        }
    }

    private fun schema(version: Int): JSONObject = JSONObject(
        File(System.getProperty("message.schema.directory"), "${databaseClass.name}/$version.json").readText(),
    ).getJSONObject("database")

    private fun scalar(sqlite: SQLiteDatabase, query: String): String = sqlite.rawQuery(query, null).use {
        check(it.moveToFirst())
        it.getString(0)
    }
}
