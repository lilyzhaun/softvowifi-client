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
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        val requests = CopyOnWriteArrayList<String>()
        val transport = object : EngineMessageTransport {
            override val events = kotlinx.coroutines.flow.MutableSharedFlow<EngineProtocolFrame.Event>()
            override suspend fun connect() = Unit
            override suspend fun request(method: String, params: kotlinx.serialization.json.JsonObject, timeoutMillis: Long): EngineProtocolFrame.Response {
                requests += method
                check(method == "getStatus") { "Unexpected request through B: $method" }
                return EngineProtocolFrame.Response(1, buildJsonObject {
                    put("tunnel", "CONNECTED"); put("reconnects", 0); put("ims", "REGISTERED")
                    put("call", "IDLE"); put("transport", "UDP"); put("pcscf", JsonArray(emptyList()))
                }, null)
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
            val initial = withTimeout(3000) { source.state.first { it is MessageUiState.Inbox && it.ownerId == ownerB && !it.page.loading && (!warm || it.composerEnabled) } } as MessageUiState.Inbox
            assertEquals(listOf("b-one"), initial.page.items.map { it.latestMessageId })
            assertEquals(warm, initial.composerEnabled)
            assertEquals(warm, requests.contains("getStatus"))
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
                assertEquals(0, requests.count { it == "sendSms" })
                assertTrue("Only read-only status requests are allowed: $requests", requests.all { it == "getStatus" })
                assertTrue(repository.listByOwner(ownerA).none { it is MessageRecord.Outgoing })
                assertTrue(repository.listByOwner(ownerB).none { it is MessageRecord.Outgoing })
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
                val inbox = source.state.value as MessageUiState.Inbox
                assertEquals(ownerB, inbox.ownerId)
                assertEquals(warm, inbox.composerEnabled)
                source.sendFromDisplayedOwner(ownerA, "155501", "stale UI must not send through B")
                assertEquals(0, requests.count { it == "sendSms" })
                assertTrue("Only read-only status requests are allowed: $requests", requests.all { it == "getStatus" })
                assertTrue(repository.listByOwner(ownerA).none { it is MessageRecord.Outgoing })
                assertTrue(repository.listByOwner(ownerB).none { it is MessageRecord.Outgoing })
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
