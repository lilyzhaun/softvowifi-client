package com.vowifi.sw

import android.app.Application
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
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
@Config(sdk = [33], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallHistoryDeleteRoomTest {
    private val ownerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val ownerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
    private val dbClass = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiCallDatabase").asSubclass(RoomDatabase::class.java)
    private fun repository(): CallHistoryRepository {
        val db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), dbClass, "call-delete-test.db")
            .setDriver(AndroidSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1)).build()
        return Class.forName("com.vowifi.sw.manager.data.AndroidCallHistoryRepository").getConstructor(dbClass).newInstance(db) as CallHistoryRepository
    }
    private fun write(key: String, phone: String) = requireNotNull(CallHistoryWrite.parse(CallHistoryDraft(key,
        CallDirection.OUTGOING, phone, CallTimesDraft(1000, 2000, 62000, 60), CallOutcome.COMPLETED)))

    @Test fun productionRoomPreservesGlobalPrefixAcrossInterruptionAndReopen() = runBlocking {
        val repo = repository()
        try {
            repo.upsert(ownerA, requireNotNull(CallHistoryWrite.parse(CallHistoryDraft("synthetic-global",
                CallDirection.OUTGOING, "+12025550123", CallTimesDraft(1_000), CallOutcome.STARTED))))
            repo.upsert(ownerA, write("synthetic-legacy", "12025550124"))
            repo.interruptOpen(3_000)
        } finally { repo.close() }
        val reopened = repository()
        try {
            val records = reopened.listByOwner(ownerA)
            assertEquals("+12025550123", records.single { it.peer?.digits() == "12025550123" }.peer?.dialNumber())
            assertEquals("12025550124", records.single { it.peer?.digits() == "12025550124" }.peer?.dialNumber())
            assertEquals(CallOutcome.INTERRUPTED, records.single { it.peer?.digits() == "12025550123" }.outcome)
        } finally { reopened.close() }
    }

    @Test fun productionRoomDeletesOnlyConfirmedIdsAndPersistsAfterReopen() = runBlocking {
        val repo = repository()
        repo.upsert(ownerA, write("first", "155501")); repo.upsert(ownerA, write("second", "155501"))
        repo.upsert(ownerA, write("other-number", "155502")); repo.upsert(ownerB, write("first", "155501"))
        val before = repo.listByOwner(ownerA)
        assertEquals(3, before.size)
        val identical = before.filter { it.peer?.digits() == "155501" }
        assertEquals(2, identical.size)
        assertNotEquals(identical[0].recordId, identical[1].recordId)
        assertTrue(repo.listByOwner(ownerB).single().recordId !in identical.map { it.recordId })
        repo.deleteByIds(ownerA, setOf(requireNotNull(identical[0].recordId)))
        assertEquals(2, repo.listByOwner(ownerA).size)
        assertEquals(1, repo.listByOwner(ownerB).size)
        repo.close()
        val reopened = repository()
        try {
            val groupIds = reopened.listByOwner(ownerA).filter { it.peer?.digits() == "155501" }.mapNotNull { it.recordId }.toSet()
            reopened.deleteByIds(ownerA, groupIds)
            assertEquals("155502", reopened.listByOwner(ownerA).single().peer?.digits())
            assertEquals(1, reopened.listByOwner(ownerB).size)
        } finally { reopened.close() }
    }
}
