package com.vowifi.sw

import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.EngineMessageRuntime
import com.vowifi.sw.manager.data.EngineMessageSource
import com.vowifi.sw.manager.data.EngineMessageTransport
import com.vowifi.sw.manager.data.MessageHistoryRepository
import com.vowifi.sw.manager.data.MessageOwnerCursor
import com.vowifi.sw.manager.data.MessageOwnerLabel
import com.vowifi.sw.manager.data.MessageOwnerRemark
import com.vowifi.sw.manager.data.MessageRepository
import com.vowifi.sw.manager.data.MessageSummaryRequest
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineProtocolFrame
import com.vowifi.sw.manager.model.MessageUiState
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessageLabelsLoadingRoomTest {
    @Test fun historicalOwnersWithExistingLiveLabelsSettleOnIoDispatcher() = scenario(false)
    @Test fun historicalOwnersAndNewLiveOwnersSettleOnIoDispatcher() = scenario(true)

    private fun scenario(newOwners: Boolean) = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val name = "labels-loading-${UUID.randomUUID()}.db"
        val databaseClass = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase").asSubclass(RoomDatabase::class.java)
        fun open(): MessageRepository {
            val database = Room.databaseBuilder(app, databaseClass, name).setDriver(AndroidSQLiteDriver())
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1)).build()
            return Class.forName("com.vowifi.sw.manager.data.AndroidMessageRepository").getConstructor(databaseClass)
                .newInstance(database) as MessageRepository
        }
        val historical = ('a'..'d').map { requireNotNull(SimOwnerId.parse("sim-owner-v1:" + it.toString().repeat(43))) }
        val live = if (newOwners) ('e'..'f').map { requireNotNull(SimOwnerId.parse("sim-owner-v1:" + it.toString().repeat(43))) }
            else historical.take(2)
        val seed = open()
        try {
            repeat(34) { index -> seed.insertIncomingIfAbsent(historical[index % 4], "message-$index", "wire-$index",
                "155501", "synthetic", index.toLong(), index.toLong()) }
            historical.forEachIndexed { index, owner -> seed.saveOwnerRemark(owner, MessageOwnerRemark.parse("synthetic-$index")) }
            seed.setLastOwner(historical.first())
            seed.writeHmacProbe(byteArrayOf(1, 2, 3))
        } finally { seed.close() }
        SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM messages", null).use { it.moveToFirst(); assertEquals(34, it.getInt(0)) }
            db.rawQuery("SELECT COUNT(*) FROM message_metadata", null).use { it.moveToFirst(); assertEquals(10, it.getInt(0)) }
        }
        val stored = open()
        val labelsStarted = AtomicInteger()
        val labelsCompleted = AtomicInteger()
        val labelsCancelled = AtomicInteger()
        val requestedSize = AtomicInteger()
        val returnedSize = AtomicInteger()
        val ownerReads = AtomicInteger()
        val summaryReads = AtomicInteger()
        val exception = AtomicReference<String?>(null)
        val repository = object : MessageRepository by stored {
            override val history = object : MessageHistoryRepository by stored.history {
                override suspend fun owners(cursor: MessageOwnerCursor?) = stored.history.owners(cursor).also { ownerReads.incrementAndGet() }
                override suspend fun summaries(request: MessageSummaryRequest) = stored.history.summaries(request).also { summaryReads.incrementAndGet() }
            }
            override suspend fun ownerLabels(owners: List<SimOwnerId>): Map<SimOwnerId, MessageOwnerLabel> {
                labelsStarted.incrementAndGet(); requestedSize.set(owners.size)
                try {
                    return stored.ownerLabels(owners).also { returnedSize.set(it.size); labelsCompleted.incrementAndGet() }
                } catch (cancelled: CancellationException) { labelsCancelled.incrementAndGet(); throw cancelled }
            }
        }
        val wireA = LabelStatusWire()
        val wireB = LabelStatusWire()
        val source = EngineMessageSource(
            EngineMessageRuntime({ listOf(EngineInstanceState("a", EngineLifecycle.RUNNING, 1), EngineInstanceState("b", EngineLifecycle.RUNNING, 2)) },
                { if (it == "a") wireA else wireB }, { 100 }, { listOf("a", "b") }),
            Dispatchers.IO + CoroutineExceptionHandler { _, error -> exception.set(error.javaClass.simpleName) },
            repository, { if (it == "a") live[0] else live[1] }, { "must-not-send" },
        )
        try {
            try {
                withTimeout(5000) { source.historyChoices.first { choices ->
                    !choices.labelsLoading && !choices.labelsFailed && choices.lines.size == (historical + live).distinct().size &&
                        choices.lines.all { it.label != null } && choices.lines.count { it.sendable } == 2
                } }
            } catch (timeout: TimeoutCancellationException) {
                val state = source.historyChoices.value
                fail("labels loading=${state.labelsLoading} failed=${state.labelsFailed} lines=${state.lines.size} " +
                    "started=${labelsStarted.get()} completed=${labelsCompleted.get()} cancelled=${labelsCancelled.get()} " +
                    "requested=${requestedSize.get()} returned=${returnedSize.get()} owners=${ownerReads.get()} summaries=${summaryReads.get()} exception=${exception.get()}")
            }
            assertNull(exception.get())
            assertTrue(labelsCompleted.get() > 0)
            assertEquals(requestedSize.get(), returnedSize.get())
            source.selectHistoryOwner(live[1])
            val selected = withTimeout(5000) { source.state.first { it is MessageUiState.Inbox && it.ownerId == live[1] && !it.page.loading } } as MessageUiState.Inbox
            assertTrue(selected.composerEnabled)
            assertFalse(source.historyChoices.value.labelsLoading)
            assertEquals(34L, stored.countUnreadIncoming())
            assertEquals(0, wireA.sends.get() + wireB.sends.get())
        } finally { source.close(); stored.close(); app.deleteDatabase(name) }
    }
}

private class LabelStatusWire : EngineMessageTransport {
    override val events = MutableSharedFlow<EngineProtocolFrame.Event>()
    val sends = AtomicInteger()
    override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): EngineProtocolFrame.Response {
        if (method == "sendSms") sends.incrementAndGet()
        check(method == "getStatus")
        return EngineProtocolFrame.Response(1, buildJsonObject {
            put("tunnel", "CONNECTED"); put("reconnects", 0); put("ims", "REGISTERED")
            put("call", "IDLE"); put("transport", "UDP"); put("pcscf", JsonArray(emptyList()))
        }, null)
    }
}
