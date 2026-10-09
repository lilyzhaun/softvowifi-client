@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.SimActiveJoinInput
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
class HistoryLineNamesRoomTest {
    @Test fun realRepositoryKeepsRemarkAndUnreadHistoryWhileDefaultNamesSurviveReopenAndClear() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val owner = SimOwnerId.fromHmacSha256(ByteArray(32) { 4 })!!
        val raw = "89" + "123456789012345674"
        var active = listOf(SimActiveJoinInput(raw, 0, homeCarrierLabel = "T-Mobile"))
        val defaults = AndroidHistoryLineNames(context, { if (it == raw) owner else null }, active = { active })
        val name = "line-name-${UUID.randomUUID()}.db"
        fun open(): MessageRepository {
            val type = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase").asSubclass(RoomDatabase::class.java)
            val database = Room.databaseBuilder(context, type, name).setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1)).build()
            return Class.forName("com.vowifi.sw.manager.data.AndroidMessageRepository")
                .getConstructor(type, AndroidHistoryLineNames::class.java).newInstance(database, defaults) as MessageRepository
        }
        var repository = open()
        try {
            repository.insertIncomingIfAbsent(owner, "synthetic", "engine-synthetic", "155501", "synthetic", 1, 1)
            val before = repository.listByOwner(owner)
            repository.setLastOwner(owner)
            val first = repository.ownerLabels(listOf(owner)).getValue(owner)
            assertEquals("T-Mobile", first.defaultName)
            val saved = repository.saveOwnerRemark(owner, MessageOwnerRemark.parse("旅行线路"))
            assertEquals("旅行线路", saved.remark)
            assertEquals("T-Mobile", saved.defaultName)
            active = emptyList()
            repository.close(); repository = open()
            val reopened = repository.ownerLabels(listOf(owner)).getValue(owner)
            assertEquals("T-Mobile", reopened.defaultName)
            assertEquals(saved.ordinal, reopened.ordinal)
            assertEquals("旅行线路", reopened.remark)
            val cleared = repository.saveOwnerRemark(owner, MessageOwnerRemark.parse(""))
            assertEquals("T-Mobile", cleared.defaultName)
            assertEquals("", cleared.remark)
            assertEquals(owner, repository.lastOwner())
            assertEquals(before, repository.listByOwner(owner))
            assertEquals(1L, repository.countUnreadIncoming())
        } finally { repository.close(); context.deleteDatabase(name) }
    }
}
