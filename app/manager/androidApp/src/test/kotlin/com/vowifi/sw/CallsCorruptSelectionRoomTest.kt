package com.vowifi.sw

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.CallAudioController
import com.vowifi.sw.manager.data.CallAudioEvent
import com.vowifi.sw.manager.data.CallAudioSession
import com.vowifi.sw.manager.data.CallHistoryDraft
import com.vowifi.sw.manager.data.CallHistoryRepository
import com.vowifi.sw.manager.data.CallHistoryWrite
import com.vowifi.sw.manager.data.CallSource
import com.vowifi.sw.manager.data.EngineMessageRuntime
import com.vowifi.sw.manager.data.EngineMessageTransport
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.CallDirection
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.CallTimesDraft
import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineProtocolError
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
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
class CallsCorruptSelectionRoomTest {
    private val databaseName = "calls-corrupt-selection.db"
    private val databaseClass = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiCallDatabase")
        .asSubclass(RoomDatabase::class.java)
    private val ownerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val ownerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
    private val path get() = RuntimeEnvironment.getApplication().getDatabasePath(databaseName)

    @Test fun malformedSelectionRecoversRecentStoredOwnerWithoutChangingRecords() = runBlocking {
        seedCorruptSelection()
        val repository = openRepository()
        try {
            assertEquals(ownerB, repository.lastOwner())
            assertEquals(100L, repository.listByOwner(ownerA).single().startedAtMs)
            assertEquals(200L, repository.listByOwner(ownerB).single().startedAtMs)
        } finally { repository.close() }
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            sqlite.rawQuery("SELECT count(*) FROM call_history_selection WHERE id=1 AND owner_id='malformed'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
        val reopened = openRepository()
        try { assertEquals(ownerB, reopened.lastOwner()) }
        finally { reopened.close() }
    }

    @Test fun corruptSelectionWithNoRecordsRecoversEmptyInsteadOfStorageFailure() = runBlocking {
        seedCorruptSelection()
        val repository = openRepository()
        repository.clearByOwner(ownerA)
        repository.clearByOwner(ownerB)
        val source = source(repository, active = false)
        try {
            val terminal = withTimeout(5_000) { source.history.first { it !is CallHistoryState.Loading } }
            assertTrue(terminal is CallHistoryState.Empty)
            assertNull(repository.lastOwner())
        } finally { source.close(); repository.close() }
    }

    @Test fun queuedActiveOwnerWinsCorruptPreferenceAndCallWritesRemainUsable() = runBlocking {
        seedCorruptSelection()
        val saved = openRepository()
        val readingPreference = CompletableDeferred<Unit>()
        val releasePreference = CompletableDeferred<Unit>()
        val repository = object : CallHistoryRepository by saved {
            override suspend fun lastOwner(): SimOwnerId? {
                readingPreference.complete(Unit)
                releasePreference.await()
                return saved.lastOwner()
            }
        }
        val source = source(repository, active = true)
        try {
            withTimeout(5_000) { readingPreference.await() }
            releasePreference.complete(Unit)
            val terminal = withTimeout(5_000) { source.history.first { it !is CallHistoryState.Loading } }
            assertTrue(terminal is CallHistoryState.Ready)
            assertEquals(100L, (terminal as CallHistoryState.Ready).records.single().startedAtMs)
            assertEquals(ownerA, saved.lastOwner())
            source.dial("15550000003")
            withTimeout(5_000) {
                source.history.first { it is CallHistoryState.Ready && it.records.any { record -> record.outcome == CallOutcome.FAILED } }
            }
            assertEquals(2, saved.listByOwner(ownerA).size)
            assertEquals(1, saved.listByOwner(ownerB).size)
        } finally { releasePreference.complete(Unit); source.close(); saved.close() }
    }

    @Test fun actualSelectionReadFailureRemainsFailureAndPreservesCallRecords() = runBlocking {
        seedCorruptSelection()
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE call_history_selection")
        }
        val repository = openRepository()
        val source = source(repository, active = false)
        try {
            val terminal = withTimeout(5_000) { source.history.first { it !is CallHistoryState.Loading } }
            assertTrue(terminal is CallHistoryState.Failure)
            assertEquals(1, repository.listByOwner(ownerA).size)
            assertEquals(1, repository.listByOwner(ownerB).size)
        } finally { source.close(); repository.close() }
    }

    private suspend fun seedCorruptSelection() {
        val repository = openRepository()
        try {
            repository.upsert(ownerA, write("original-a", 100))
            repository.upsert(ownerB, write("original-b", 200))
        } finally { repository.close() }
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO call_history_selection VALUES (1, 'malformed')")
        }
    }

    private fun write(correlation: String, started: Long): CallHistoryWrite = requireNotNull(CallHistoryWrite.parse(
        CallHistoryDraft(correlation, CallDirection.INCOMING, null, CallTimesDraft(started, endedAtMs = started + 1), CallOutcome.MISSED),
    ))

    private fun openRepository(): CallHistoryRepository {
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), databaseClass, databaseName)
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1))
            .build()
        return Class.forName("com.vowifi.sw.manager.data.AndroidCallHistoryRepository")
            .getConstructor(databaseClass).newInstance(database) as CallHistoryRepository
    }

    private fun source(repository: CallHistoryRepository, active: Boolean): CallSource {
        val bindingClass = Class.forName("com.vowifi.sw.manager.data.CallHistoryBinding")
        val ownerFor: (String) -> SimOwnerId? = { ownerA }
        val correlation: () -> String = { "synthetic-after-corrupt" }
        val binding = bindingClass.constructors.single().newInstance(repository, ownerFor, correlation)
        val audio = object : CallAudioController {
            override val events = MutableSharedFlow<CallAudioEvent>()
            override suspend fun start(session: CallAudioSession) { error("audio must not start") }
            override fun stop() = Unit
        }
        val transport = object : EngineMessageTransport {
            override val events = MutableSharedFlow<EngineProtocolFrame.Event>()
            override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long) =
                EngineProtocolFrame.Response(1, null, EngineProtocolError(2000, "synthetic failure"))
        }
        val runtime = EngineMessageRuntime(
            { if (active) listOf(EngineInstanceState("8986001", EngineLifecycle.RUNNING, pid = 7)) else emptyList() },
            { transport }, { 900L },
        )
        return Class.forName("com.vowifi.sw.manager.data.EngineCallSource").constructors
            .single { it.parameterCount == 6 }.newInstance(runtime, audio, Dispatchers.Unconfined, binding,
                com.vowifi.sw.manager.data.CallControlTransport { _, method, params, timeout ->
                    if (method == "getCallSnapshot") EngineProtocolFrame.Response(1,
                        kotlinx.serialization.json.Json.parseToJsonElement("""{"epoch":"host","revision":0,"stage":"IDLE","confirmedElapsedMs":0,"dtmfSupported":false}""") as JsonObject, null)
                    else transport.request(method, params, timeout)
                }, { 0L }) as CallSource
    }
}
