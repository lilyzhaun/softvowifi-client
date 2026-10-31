package com.vowifi.sw

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.MessageRepository
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.StoredMessageConversation
import java.util.concurrent.ConcurrentLinkedQueue
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
class MessageConversationExistenceRoomTest {
    @Test fun existenceIsOwnerScopedIncludesOutgoingAndOnlySelectsOneIdentifierWithoutReadingBodies() = runBlocking {
        val queries = ConcurrentLinkedQueue<String>()
        val delegate = AndroidSQLiteDriver()
        val driver = object : SQLiteDriver by delegate {
            override fun open(fileName: String): SQLiteConnection {
                val connection = delegate.open(fileName)
                return object : SQLiteConnection by connection {
                    override fun prepare(sql: String): SQLiteStatement {
                        queries.add(sql)
                        return connection.prepare(sql)
                    }
                }
            }
        }
        val type = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase").asSubclass(RoomDatabase::class.java)
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), type).setDriver(driver)
            .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1)).build()
        val repo = Class.forName("com.vowifi.sw.manager.data.AndroidMessageRepository").getConstructor(type).newInstance(database) as MessageRepository
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val other = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
        val incoming = StoredMessageConversation(owner, "10086")
        try {
            assertFalse(repo.history.hasConversation(incoming))
            repeat(60) { index -> repo.insertIncomingIfAbsent(owner, "incoming-$index", "engine-$index", "10086", "synthetic body", index.toLong(), 1) }
            repo.insertQueued(other, "outgoing", "10087", "synthetic outgoing", 1)
            queries.clear()
            assertTrue(repo.history.hasConversation(incoming))
            assertFalse(repo.history.hasConversation(StoredMessageConversation(other, "10086")))
            assertFalse(repo.history.hasConversation(StoredMessageConversation(owner, "10087")))
            assertTrue(repo.history.hasConversation(StoredMessageConversation(other, "10087")))
            val selects = queries.map { it.trim().replace(Regex("\\s+"), " ").uppercase() }
                .filter { it.startsWith("SELECT ") && it.contains(" FROM MESSAGES ") }
            assertEquals("四次存在性检查均必须到达真实 SQLite", 4, selects.size)
            selects.forEach { sql ->
                assertEquals("只取标识，不读正文或 SELECT *", "SELECT MESSAGE_ID", sql.substringBefore(" FROM "))
                assertTrue("结果有界 LIMIT 1", sql.contains("LIMIT 1"))
                assertTrue(sql.contains("SIM_OWNER_ID = ?")); assertTrue(sql.contains("CONVERSATION_KEY = ?"))
            }
            assertEquals("存在性查询不标读", 60L, repo.countUnreadIncoming())
            assertEquals("存在性查询不选择线路", null, repo.lastOwner())
        } finally { repo.close() }
    }
}
