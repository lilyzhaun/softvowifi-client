package com.vowifi.sw

import android.app.Application
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.EngineMessageRuntime
import com.vowifi.sw.manager.data.EngineMessageSource
import com.vowifi.sw.manager.data.MessageHistoryRepository
import com.vowifi.sw.manager.data.MessageRepository
import com.vowifi.sw.manager.data.MessageThreadCursor
import com.vowifi.sw.manager.data.MessageThreadSlice
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.StoredMessageConversation
import com.vowifi.sw.manager.model.MessageUiState
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

// S4/R6 后继草案：沿用 MessagePagingRoomSourceTest 的真实 Room/公开 Source seam。
// 首次仅因getApplication泛型编译失败，修正后待真实执行；不冒称设备/运营商证据。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class S4MessagePagingRoomReadLifecycleTest {
    @Test(timeout = 30_000)
    fun productionSourceLetsIssuedRoomPageFinishWithoutReplacingNewOwnerThread() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        val name = "s4-read-lifecycle-${UUID.randomUUID()}.db"
        val saved = openRepository(name)
        val ownerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val ownerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
        val threadA = StoredMessageConversation(ownerA, "1666")
        val threadB = StoredMessageConversation(ownerB, "1666")
        val entered = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Result<MessageThreadSlice>>()
        var source: EngineMessageSource? = null
        try {
            withTimeout(20_000) {
                // 仅复用既有 MessagePagingRoomSourceTest 的合成 1666 / synthetic 样本。
                repeat(70) { index ->
                    saved.insertIncomingIfAbsent(ownerA, "a-$index", "ea-$index", threadA.key, "synthetic", index.toLong(), 1)
                }
                saved.insertIncomingIfAbsent(ownerB, "b-only", "eb-only", threadB.key, "synthetic", 1, 1)
                // Room lastOwner 只读 metadata，不能拿“时间最新的 owner”猜启动完成前提。
                saved.setLastOwner(ownerA)
                assertEquals(ownerA, saved.lastOwner())
                val repository = object : MessageRepository by saved {
                    override val history = object : MessageHistoryRepository by saved.history {
                        override suspend fun thread(identity: StoredMessageConversation, cursor: MessageThreadCursor?): MessageThreadSlice {
                            if (identity != threadA || cursor == null) return saved.history.thread(identity, cursor)
                            entered.complete(requireNotNull(currentCoroutineContext()[Job]))
                            try {
                                withTimeout(5_000) { release.await() }
                                val actual = saved.history.thread(identity, cursor)
                                observed.complete(Result.success(actual))
                                return actual
                            } catch (cancelled: CancellationException) {
                                observed.complete(Result.failure(cancelled))
                                throw cancelled
                            } catch (error: Exception) {
                                observed.complete(Result.failure(error))
                                throw error
                            }
                        }
                    }
                }
                val listed = CompletableDeferred<Unit>()
                // Source 归当前 runBlocking 事件循环，Room 查询沿原 IO 单 worker；不使用池并发读屏。
                val running = EngineMessageSource(
                    EngineMessageRuntime({ listed.complete(Unit); emptyList() }, { error("No engine permitted in this test") }, { 1L }),
                    coroutineContext, repository, { null }, { "unused" },
                )
                source = running
                withTimeout(5_000) { listed.await() }
                val root = withTimeout(5_000) {
                    running.state.first {
                        it is MessageUiState.Inbox && !it.page.loading && it.ownerId == ownerA &&
                            it.attachmentNotice == "当前没有运行中的引擎"
                    }
                } as MessageUiState.Inbox
                assertEquals(listOf(threadA), root.page.items.map { it.identity })
                assertFalse(root.composerEnabled)
                running.selectConversation(threadA.handle.value)
                val latest = running.awaitReady(threadA)
                assertEquals(50, latest.records.size)
                assertTrue(latest.window?.older != null)
                running.loadOlderMessages()
                val issuedRead = withTimeout(5_000) { entered.await() }

                running.selectConversation(null)
                running.selectHistoryOwner(ownerB)
                running.selectConversation(threadB.handle.value)
                val newScope = running.awaitReady(threadB)
                assertEquals(listOf("b-only"), newScope.records.map { it.id })
                assertFalse(newScope.composerEnabled)
                release.complete(Unit)
                val actualRead = withTimeout(5_000) { observed.await() }
                withTimeout(5_000) { issuedRead.join() }
                // Result 只记录真正的 Room 返回/异常，不向 Source 手工发布任何成功终态。
                assertTrue("离页取消了已发出的可取消 Room 读取", actualRead.isSuccess)
                assertEquals(threadA, actualRead.getOrThrow().identity)
                assertEquals(20, actualRead.getOrThrow().records.size)
                assertEquals(newScope, running.state.value)
                assertEquals(listOf("b-only"), saved.history.thread(threadB).records.map { it.id })
                assertEquals(71L, saved.countUnreadIncoming())
            }
        } finally {
            try {
                release.complete(Unit)
                source?.close()
                // NonCancellable 仅保护有界 finally；被测试的读取始终可取消。
                withContext(NonCancellable) {
                    withTimeout(2_000) { if (entered.isCompleted) entered.await().join() }
                }
            } finally {
                saved.close()
                application.deleteDatabase(name)
            }
        }
    }

    private suspend fun EngineMessageSource.awaitReady(identity: StoredMessageConversation): MessageUiState.Ready =
        withTimeout(5_000) {
            state.first { state ->
                state is MessageUiState.Ready && state.window?.let { window ->
                    window.identity == identity && !window.loading
                } == true
            }
        } as MessageUiState.Ready

    private fun openRepository(name: String): MessageRepository {
        val type = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase").asSubclass(RoomDatabase::class.java)
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), type, name)
            .setDriver(AndroidSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1))
            .build()
        return Class.forName("com.vowifi.sw.manager.data.AndroidMessageRepository")
            .getConstructor(type).newInstance(database) as MessageRepository
    }
}
