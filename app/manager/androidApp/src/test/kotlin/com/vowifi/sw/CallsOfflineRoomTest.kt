package com.vowifi.sw

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.CallHistoryRepository
import com.vowifi.sw.manager.data.CallAudioController
import com.vowifi.sw.manager.data.CallAudioEvent
import com.vowifi.sw.manager.data.CallAudioSession
import com.vowifi.sw.manager.data.CallSource
import com.vowifi.sw.manager.data.EngineMessageRuntime
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.CallOutcome
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
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
class CallsOfflineRoomTest {
    private val databaseName = "calls-offline-migration.db"
    private val databaseClass = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiCallDatabase")
        .asSubclass(RoomDatabase::class.java)
    private val ownerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val ownerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
    private val path get() = RuntimeEnvironment.getApplication().getDatabasePath(databaseName)

    @Test fun productionSourceStartupReadsMigratedRoomWithoutEngineAndReopens() = runBlocking {
        seedV1()
        repeat(2) {
            val repository = openRepository()
            val source = offlineSource(repository)
            try {
                val history = withTimeout(5_000) { source.history.first { it !is CallHistoryState.Loading } }
                assertTrue(history is CallHistoryState.Ready)
                assertEquals(2, (history as CallHistoryState.Ready).records.size)
                assertEquals(ownerB, repository.lastOwner())
                assertTrue(withTimeout(5_000) { source.state.first { it !is CallUiState.Loading } } is CallUiState.Unavailable)
                assertEquals(2, source.historyOwners.value.size)
            } finally { source.close(); repository.close() }
        }
    }

    private fun offlineSource(repository: CallHistoryRepository): CallSource {
        val bindingClass = Class.forName("com.vowifi.sw.manager.data.CallHistoryBinding")
        val ownerFor: (String) -> SimOwnerId? = { error("offline must not read SIM") }
        val correlation: () -> String = { error("offline must not create call token") }
        val binding = bindingClass.constructors.single().newInstance(repository, ownerFor, correlation)
        val audio = object : CallAudioController {
            override val events = MutableSharedFlow<CallAudioEvent>()
            override suspend fun start(session: CallAudioSession) { error("offline must not start audio") }
            override fun stop() = Unit
        }
        val runtime = EngineMessageRuntime({ emptyList() }, { error("offline must not connect") }, { 900L })
        return Class.forName("com.vowifi.sw.manager.data.EngineCallSource").constructors
            .single { it.parameterCount == 6 }.newInstance(runtime, audio, Dispatchers.Unconfined, binding,
                com.vowifi.sw.manager.data.CallControlTransport { _, _, _, _ -> error("offline must not connect") },
                { 0L }) as CallSource
    }

    @Test fun v1MigrationPreservesRecordsSelectsActualNewestOwnerAndReopensSelection() = runBlocking {
        seedV1()
        val repository = openRepository()
        try {
            assertEquals(ownerB, repository.lastOwner())
            assertEquals(listOf(ownerA, ownerB), repository.listOwners())
            assertEquals(1, repository.listByOwner(ownerA).size)
            assertEquals(2, repository.listByOwner(ownerB).size)
            repository.interruptOpen(900)
            assertEquals(CallOutcome.INTERRUPTED, repository.listByOwner(ownerA).single().outcome)
            repository.selectOwner(ownerA)
        } finally { repository.close() }
        val reopened = openRepository()
        try {
            assertEquals(ownerA, reopened.lastOwner())
            assertEquals("15550000001", reopened.listByOwner(ownerA).single().peer?.digits())
            reopened.clearByOwner(ownerA)
            assertTrue(reopened.listByOwner(ownerA).isEmpty())
            assertEquals(2, reopened.listByOwner(ownerB).size)
        } finally { reopened.close() }
        val afterClear = openRepository()
        try {
            assertEquals(ownerA, afterClear.lastOwner())
            assertTrue(afterClear.listByOwner(ownerA).isEmpty())
            assertEquals(2, afterClear.listByOwner(ownerB).size)
        } finally { afterClear.close() }
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals(2, sqlite.version)
            sqlite.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(schema(2).getString("identityHash"), it.getString(0))
            }
        }
    }

    @Test fun newDatabaseHasNoOwnerAndNoHistory() = runBlocking {
        val repository = openRepository()
        try {
            assertNull(repository.lastOwner())
            assertTrue(repository.listByOwner(ownerA).isEmpty())
        } finally { repository.close() }
    }

    @Test fun invalidOldSchemaFailsWithoutDestroyingRecords() = runBlocking {
        seedV1()
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP INDEX index_call_records_owner_started")
        }
        val repository = openRepository()
        try {
            try {
                repository.lastOwner()
                fail("invalid schema must fail")
            } catch (error: Exception) {
                assertEquals("CallHistoryStorageException", error.javaClass.simpleName)
            }
        } finally { repository.close() }
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals(1, sqlite.version)
            sqlite.rawQuery("SELECT count(*) FROM call_records", null).use {
                assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0))
            }
        }
    }

    private fun openRepository(): CallHistoryRepository {
        val migration = Class.forName("com.vowifi.sw.manager.data.CallHistorySelectionEntityKt")
            .getMethod("getCALL_HISTORY_MIGRATION_1_2").invoke(null) as Migration
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), databaseClass, databaseName)
            .addMigrations(migration)
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1))
            .build()
        return Class.forName("com.vowifi.sw.manager.data.AndroidCallHistoryRepository")
            .getConstructor(databaseClass).newInstance(database) as CallHistoryRepository
    }

    private fun seedV1() {
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            val old = schema(1)
            val entities = old.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (entry in 0 until indices.length()) {
                    sqlite.execSQL(indices.getJSONObject(entry).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = old.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) sqlite.execSQL(setup.getString(index))
            for ((index, owner) in listOf(ownerA, ownerB, ownerB).withIndex()) {
                sqlite.execSQL(
                    "INSERT INTO call_records VALUES (?,?,'INCOMING','15550000001',?,NULL,NULL,NULL,'STARTED')",
                    arrayOf("sim-owner-v1:" + (if (owner == ownerA) "a" else "b").repeat(43),
                        "synthetic-$index", (100L + index).toString()),
                )
            }
            sqlite.version = 1
        }
    }

    private fun schema(version: Int): JSONObject = JSONObject(File(
        System.getProperty("message.schema.directory"),
        "com.vowifi.sw.manager.data.SoftVoWiFiCallDatabase/$version.json",
    ).readText()).getJSONObject("database")
}
