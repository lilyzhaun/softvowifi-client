package com.vowifi.sw

import android.content.Intent
import android.net.Uri
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class, CallCoordinatorShadow::class, MessageSourceShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessageColdRouteRoomTest {
    private val wireA = "sim-owner-v1:" + "a".repeat(43)
    private val ownerA = requireNotNull(SimOwnerId.parse(wireA))
    private val ownerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))

    @Test fun coldNotificationRestoresPersistedOwnerInsteadOfLastOwner() { scenario(false) }
    @Test fun warmNotificationRestoresOwnerWithoutSwitchingRunningEngine() { scenario(true) }

    private fun scenario(warm: Boolean) = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val name = "route-${UUID.randomUUID()}.db"
        val databaseClass = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase").asSubclass(RoomDatabase::class.java)
        fun open(): MessageRepository {
            val db = Room.databaseBuilder(app, databaseClass, name).setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1)).build()
            return Class.forName("com.vowifi.sw.manager.data.AndroidMessageRepository").getConstructor(databaseClass)
                .newInstance(db) as MessageRepository
        }
        open().let { repository ->
            repository.insertIncomingIfAbsent(ownerA, "a-one", "a-mt", "155501", "synthetic A", 1, 1)
            repository.insertIncomingIfAbsent(ownerB, "b-one", "b-mt", "155501", "synthetic B", 1, 1)
            repository.setLastOwner(ownerB)
            repository.close()
        }
        val repository = open()
        var sends = 0
        val transport = object : EngineMessageTransport {
            override val events = kotlinx.coroutines.flow.MutableSharedFlow<EngineProtocolFrame.Event>()
            override suspend fun connect() = Unit
            override suspend fun request(method: String, params: kotlinx.serialization.json.JsonObject, timeoutMillis: Long): EngineProtocolFrame.Response {
                sends++
                error("Must not send through B")
            }
        }
        val source = EngineMessageSource(
            EngineMessageRuntime({ if (warm) listOf(EngineInstanceState("8986001", EngineLifecycle.RUNNING, pid = 7)) else emptyList() }, { transport }, { 1000 }),
            Dispatchers.Unconfined, repository, { ownerB }, { "new" },
        )
        MessageSourceShadow.routeSource = source
        try {
            withTimeout(3000) { source.state.first { it is MessageUiState.Inbox && !it.page.loading } }
            source.selectHistoryOwner(ownerB)
            val initial = withTimeout(3000) { source.state.first { it is MessageUiState.Inbox && it.ownerId == ownerB && !it.page.loading } } as MessageUiState.Inbox
            assertEquals(listOf("b-one"), initial.page.items.map { it.latestMessageId })
            val digest = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest((wireA + "\u0000" + "155501").toByteArray()))
            val handle = "conversation-$digest"
            val tap = Intent(AndroidMessageNotifications.ACTION).setData(Uri.parse("softvowifi-message:$handle"))
                .putExtra(AndroidMessageNotifications.EXTRA_HANDLE, handle)
            Robolectric.buildActivity(MainActivity::class.java, if (warm) Intent() else tap).use { controller ->
                controller.create().start().resume()
                if (warm) controller.newIntent(tap)
                val restored = withTimeoutOrNull(2000) { source.state.first { it is MessageUiState.Ready && it.ownerId == ownerA } }
                assertNotNull("Notification must restore persisted A rather than leave loaded B", restored)
                val ready = restored as MessageUiState.Ready
                assertEquals(listOf("a-one"), ready.records.map { it.id })
                assertFalse(ready.composerEnabled)
                source.sendSms("155501", "must not send")
                assertEquals(0, sends)
                assertEquals(2L, repository.countUnreadIncoming())
                assertEquals(2, repository.listConversations().size)
                repository.insertIncomingIfAbsent(ownerA, "future-a", "future-mt", "155501", "synthetic future", 2, 2)
                source.markVisibleRead(requireNotNull(ObservedIncomingReadRequest.create(ownerA, "155501", listOf("a-one"))))
                withTimeout(3000) { source.state.first { state ->
                    state is MessageUiState.Ready && state.ownerId == ownerA && state.records.filterIsInstance<MessageRecord.Incoming>().any { it.id == "a-one" && it.isRead }
                } }
                assertEquals(2L, repository.countUnreadIncoming())
                assertEquals(listOf("future-a"), repository.listByOwner(ownerA).filterIsInstance<MessageRecord.Incoming>().filter { !it.isRead }.map { it.id })
                controller.newIntent(Intent(AndroidMessageNotifications.ACTION)
                    .setData(Uri.parse("softvowifi-message:conversation-forged"))
                    .putExtra(AndroidMessageNotifications.EXTRA_HANDLE, "conversation-forged"))
                withTimeout(3000) { source.state.first { it is MessageUiState.Unavailable } }
                assertEquals(2L, repository.countUnreadIncoming())
                controller.newIntent(Intent().putExtra("destination", "messages"))
                assertEquals(ownerB, (source.state.value as MessageUiState.Inbox).ownerId)
                source.sendFromDisplayedOwner(ownerA, "155501", "stale UI must not send through B")
                assertEquals(0, sends)
                controller.pause().stop().destroy()
            }
        } finally {
            MessageSourceShadow.routeSource = null
            source.close()
            repository.close()
            app.deleteDatabase(name)
        }
    }
}
