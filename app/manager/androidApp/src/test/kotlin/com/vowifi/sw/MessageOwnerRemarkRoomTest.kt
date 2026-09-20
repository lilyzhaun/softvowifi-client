package com.vowifi.sw

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.MessageOwnerRemark
import com.vowifi.sw.manager.data.MessageRepository
import com.vowifi.sw.manager.data.SimOwnerId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessageOwnerRemarkRoomTest {
    private val ownerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val ownerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))

    @Test fun remarksReopenClearAndDuplicateWithoutChangingMessagesOrMetadata() = runBlocking {
        val name = "owner-remarks-${UUID.randomUUID()}.db"
        val app = RuntimeEnvironment.getApplication()
        val seed = open(name)
        try {
            seed.setLastOwner(ownerB)
            seed.writeHmacProbe(byteArrayOf(1, 3, 5))
            seed.insertIncomingIfAbsent(ownerA, "incoming", "engine", "155501", "synthetic", 1, 1)
            val before = seed.listByOwner(ownerA)
            val first = seed.saveOwnerRemark(ownerA, MessageOwnerRemark.parse("  测试线路  "))
            val second = seed.saveOwnerRemark(ownerB, MessageOwnerRemark.parse("测试线路"))
            assertEquals(first.remark, second.remark)
            assertTrue(first.ordinal != second.ordinal)
            assertEquals(before, seed.listByOwner(ownerA))
        } finally { seed.close() }
        val reopened = open(name)
        try {
            val labels = reopened.ownerLabels(listOf(ownerB, ownerA))
            assertEquals("测试线路", labels.getValue(ownerA).remark)
            assertEquals(1L, labels.getValue(ownerA).ordinal)
            assertEquals(2L, labels.getValue(ownerB).ordinal)
            assertEquals(ownerB, reopened.lastOwner())
            assertArrayEquals(byteArrayOf(1, 3, 5), reopened.readHmacProbe())
            assertEquals(1L, reopened.countUnreadIncoming())
            val cleared = reopened.saveOwnerRemark(ownerA, MessageOwnerRemark.parse("   "))
            assertEquals("", cleared.remark)
            assertEquals(1L, cleared.ordinal)
        } finally { reopened.close() }
        val cleared = open(name)
        try {
            assertEquals("", cleared.ownerLabels(listOf(ownerA)).getValue(ownerA).remark)
            assertEquals(1L, cleared.countUnreadIncoming())
        } finally { cleared.close(); app.deleteDatabase(name) }
    }

    @Test fun concurrentAllocationAcrossRoomConnectionsHasUniqueStableOrdinals() = runBlocking {
        val name = "owner-ordinals-${UUID.randomUUID()}.db"
        val first = open(name)
        val second = open(name)
        try {
            val owners = ('a'..'t').map { requireNotNull(SimOwnerId.parse("sim-owner-v1:" + it.toString().repeat(43))) }
            val labels = coroutineScope {
                owners.mapIndexed { index, owner -> async(Dispatchers.Default) {
                    (if (index % 2 == 0) first else second).ownerLabels(listOf(owner)).getValue(owner)
                } }.awaitAll()
            }
            assertEquals((1L..20L).toSet(), labels.map { it.ordinal }.toSet())
            val reordered = first.ownerLabels(owners.reversed())
            owners.forEachIndexed { index, owner -> assertEquals(labels[index], reordered.getValue(owner)) }
            val same = coroutineScope { List(8) { index -> async(Dispatchers.Default) {
                (if (index % 2 == 0) first else second).ownerLabels(listOf(ownerA)).getValue(ownerA)
            } }.awaitAll() }
            assertEquals(1, same.distinct().size)
        } finally { first.close(); second.close(); RuntimeEnvironment.getApplication().deleteDatabase(name) }
    }

    private fun open(name: String): MessageRepository {
        val databaseClass = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase").asSubclass(RoomDatabase::class.java)
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), databaseClass, name)
            .setDriver(AndroidSQLiteDriver()).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1)).build()
        return Class.forName("com.vowifi.sw.manager.data.AndroidMessageRepository").getConstructor(databaseClass)
            .newInstance(database) as MessageRepository
    }
}
