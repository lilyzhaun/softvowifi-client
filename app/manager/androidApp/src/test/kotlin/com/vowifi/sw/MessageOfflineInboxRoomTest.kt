package com.vowifi.sw

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.EngineMessageRuntime
import com.vowifi.sw.manager.data.EngineMessageSource
import com.vowifi.sw.manager.data.MessageRepository
import com.vowifi.sw.manager.data.ObservedIncomingReadRequest
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessageOfflineInboxRoomTest {
    @Test fun reopenedRoomHistoryIsBrowsableWithoutAnEngineOrLastOwner() = browse(false)
    @Test fun rootPermissionFailureDoesNotPreventBrowsingReopenedRoomHistory() = browse(true)

    private fun browse(permissionDenied: Boolean) = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val name = "offline-inbox-${UUID.randomUUID()}.db"
        val ownerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val ownerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
        val databaseClass = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase").asSubclass(RoomDatabase::class.java)
        fun open(): MessageRepository {
            val database = Room.databaseBuilder(app, databaseClass, name).setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1)).build()
            return Class.forName("com.vowifi.sw.manager.data.AndroidMessageRepository").getConstructor(databaseClass)
                .newInstance(database) as MessageRepository
        }
        val seed = open()
        try {
            seed.insertIncomingIfAbsent(ownerA, "a", "a", "155501", "synthetic", 1, 1)
            seed.insertIncomingIfAbsent(ownerB, "b", "b", "155501", "synthetic", 2, 2)
        } finally { seed.close() }
        val repository = open()
        val source = EngineMessageSource(
            EngineMessageRuntime(
                {
                    if (permissionDenied) throw Class.forName("com.vowifi.sw.manager.data.MessagePermissionException")
                        .asSubclass(Exception::class.java).getConstructor(String::class.java).newInstance("denied")
                    emptyList()
                },
                { error("Offline browsing must not attach a transport") }, { 10 },
            ),
            Dispatchers.Unconfined, repository, { ownerB }, { "unused" },
        )
        try {
            withTimeout(5_000) { source.historyChoices.first { it.lines.size == 2 } }
            source.selectHistoryOwner(ownerA)
            val first = withTimeout(5_000) { source.state.first { it is MessageUiState.Inbox && it.ownerId == ownerA && !it.page.loading } } as MessageUiState.Inbox
            assertEquals(listOf("a"), first.page.items.map { it.latestMessageId })
            assertEquals(1L, first.page.items.single().messageCount)
            source.selectHistoryOwner(ownerB)
            val second = withTimeout(5_000) { source.state.first { it is MessageUiState.Inbox && it.ownerId == ownerB && !it.page.loading } } as MessageUiState.Inbox
            assertEquals(listOf("b"), second.page.items.map { it.latestMessageId })
            assertEquals(1L, second.page.items.single().messageCount)
            assertFalse(second.composerEnabled)
            source.sendFromDisplayedOwner(ownerB, "155501", "synthetic")
            source.markVisibleRead(requireNotNull(ObservedIncomingReadRequest.create(ownerB, "155501", listOf("b"))))
            withTimeout(5_000) { source.state.first { state ->
                state is MessageUiState.Inbox && state.ownerId == ownerB && !state.page.loading &&
                    state.page.items.singleOrNull()?.let { it.latestMessageId == "b" && it.unreadCount == 0L } == true
            } }
            assertEquals(1L, repository.countUnreadIncoming())
            assertNull(repository.lastOwner())
            assertEquals(2, repository.listConversations().size)
        } finally {
            source.close()
            repository.close()
            app.deleteDatabase(name)
        }
    }
}
