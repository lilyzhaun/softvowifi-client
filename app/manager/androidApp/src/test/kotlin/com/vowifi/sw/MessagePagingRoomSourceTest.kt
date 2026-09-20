package com.vowifi.sw

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.MessageUiState
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessagePagingRoomSourceTest {
    @Test fun engineAbsentSourceUsesRealRoomForRootSearchAndOlderThread() = runBlocking {
        val name = "paging-source-${UUID.randomUUID()}.db"
        val type = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase").asSubclass(RoomDatabase::class.java)
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), type, name)
            .setDriver(AndroidSQLiteDriver()).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1)).build()
        val repository = Class.forName("com.vowifi.sw.manager.data.AndroidMessageRepository").getConstructor(type).newInstance(database) as MessageRepository
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        var source: EngineMessageSource? = null
        try {
            repeat(121) { index -> repository.insertIncomingIfAbsent(owner, "row-$index", "engine-$index", "1555$index", "synthetic", index.toLong(), 1) }
            repeat(121) { index -> repository.insertIncomingIfAbsent(owner, "thread-$index", "thread-engine-$index", "1666", "long-window", index.toLong(), 1) }
            val running = EngineMessageSource(EngineMessageRuntime({ emptyList() }, { error("No engine permitted in this test") }, { 1L }),
                Dispatchers.IO, repository, { owner }, { "unused" })
            source = running
            val root = withTimeout(10_000) { running.state.first { it is MessageUiState.Inbox && !it.page.loading } } as MessageUiState.Inbox
            assertEquals(50, root.page.items.size)
            assertFalse(root.composerEnabled)
            running.loadMoreHistory()
            val more = withTimeout(10_000) { running.state.first { it is MessageUiState.Inbox && !it.page.loading && it.page.items.size == 100 } } as MessageUiState.Inbox
            assertEquals(100, more.page.items.map { it.identity }.distinct().size)
            running.searchHistory("LONG-WINDOW")
            val search = withTimeout(10_000) { running.state.first { it is MessageUiState.Inbox && it.page.query == "LONG-WINDOW" && !it.page.loading } } as MessageUiState.Inbox
            assertEquals(1, search.page.items.size)
            running.selectConversation(search.page.items.single().identity.handle.value)
            val latest = withTimeout(10_000) { running.state.first { it is MessageUiState.Ready && it.window?.loading == false } } as MessageUiState.Ready
            assertEquals(50, latest.records.size)
            running.loadOlderMessages()
            val older = withTimeout(10_000) { running.state.first { it is MessageUiState.Ready && it.records.size == 100 && it.window?.loading == false } } as MessageUiState.Ready
            assertEquals(100, older.records.map { it.id }.distinct().size)
            assertEquals(242L, repository.countUnreadIncoming())
        } finally { source?.close(); repository.close(); RuntimeEnvironment.getApplication().deleteDatabase(name) }
    }
}
