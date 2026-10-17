@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.OperationalLogUiState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

// 真实源完成/取消回归；执行与回执由统筹负责。
// 引用旧 API 测试的 S3LogRefreshFixture，不增加源替身或调度器。
class OperationalLogRefreshAwaitHostTest {
    @Test(timeout = 5000)
    fun awaitJoinsAutomaticAndWaitsForCatalogThenTailAndDomainPublication() = runBlocking {
        S3LogRefreshFixture().use { fixture ->
            fixture.drain()
            val catalog = fixture.next("catalog") // 构造器 automatic 的真实 IO 已在途。
            val first = async(fixture.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                fixture.source.refreshAndAwait() to fixture.source.state.value
            }
            val duplicate = async(fixture.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                fixture.source.refreshAndAwait()
            }
            fixture.source.refresh()
            fixture.drain()
            assertFalse(first.isCompleted)
            assertFalse(duplicate.isCompleted)
            assertEquals(1, fixture.commands.size)

            fixture.reply(catalog, fixture.catalog())
            val tail = fixture.next("read ${fixture.first}")
            assertFalse(first.isCompleted)
            assertFalse(duplicate.isCompleted)
            fixture.reply(tail, fixture.tail(fixture.first))
            val (result, published) = withTimeout(3000) { first.await() }
            assertEquals(RefreshResult.Completed, result)
            assertEquals(fixture.ready(), published)
            assertEquals(1, (published as OperationalLogUiState.Ready).entries.size)
            assertEquals(RefreshResult.Completed, withTimeout(3000) { duplicate.await() })
            assertEquals(2, fixture.commands.size)
        }
    }

    @Test(timeout = 5000)
    fun cancellingOneWaiterDoesNotCancelSharedProducerOrOtherWaiter() = runBlocking {
        S3LogRefreshFixture().use { fixture ->
            fixture.drain()
            fixture.reply(fixture.next("catalog"), fixture.catalog())
            val tail = fixture.next("read ${fixture.first}")
            val leaving = async(fixture.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                fixture.source.refreshAndAwait()
            }
            val remaining = async(fixture.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                fixture.source.refreshAndAwait()
            }
            assertFalse("真实读未结束，等待者不能即刻返回", leaving.isCompleted)
            assertFalse(remaining.isCompleted)
            leaving.cancel()
            fixture.drain()
            assertTrue(leaving.isCancelled)
            assertFalse(tail.cancelled)
            assertFalse(remaining.isCompleted)
            assertEquals(2, fixture.commands.size)
            fixture.reply(tail, fixture.tail(fixture.first))
            assertEquals(RefreshResult.Completed, withTimeout(3000) { remaining.await() })
            assertEquals(2, fixture.commands.size)
        }
    }

    @Test(timeout = 5000)
    fun closeCancelsQueuedOrHeldWaiterAndDoesNotPublishALateTail() = runBlocking {
        listOf(false, true).forEach { startRead ->
            S3LogRefreshFixture(lateReplies = true).use { fixture ->
                val tail = if (startRead) {
                    fixture.drain()
                    fixture.reply(fixture.next("catalog"), fixture.catalog())
                    fixture.next("read ${fixture.first}")
                } else null
                val waiting = async(fixture.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                    fixture.source.refreshAndAwait()
                }
                assertFalse(waiting.isCompleted)
                val before = fixture.source.state.value
                fixture.source.close()
                fixture.drain()
                assertTrue(waiting.isCancelled)
                assertEquals(RefreshResult.Unsupported, fixture.source.refreshAndAwait())
                tail?.let { fixture.reply(it, fixture.tail(fixture.first)) }
                assertEquals(before, fixture.source.state.value)
                assertEquals(if (startRead) 2 else 0, fixture.commands.size)
            }
        }
    }

    @Test(timeout = 5000)
    fun equalSnapshotCompletesEachRequestAndFailureWithReadyIssueIsNotCompleted() = runBlocking {
        S3LogRefreshFixture().use { fixture ->
            fixture.warm()
            val before = fixture.ready()
            val equal = async(fixture.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                fixture.source.refreshAndAwait() to fixture.source.state.value
            }
            fixture.drain()
            assertFalse(equal.isCompleted)
            fixture.reply(fixture.next("catalog"), fixture.catalog())
            val tail = fixture.next("read ${fixture.first}")
            assertFalse(equal.isCompleted)
            fixture.reply(tail, fixture.tail(fixture.first))
            assertEquals(RefreshResult.Completed to before, withTimeout(3000) { equal.await() })

            val failed = async(fixture.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                fixture.source.refreshAndAwait()
            }
            fixture.drain()
            assertFalse("读取错误也必须等实际 IO，而非缺失队列前置失败", failed.isCompleted)
            fixture.reply(fixture.next("catalog"), fixture.catalog())
            fixture.reply(fixture.next("read ${fixture.first}"), "broken response")
            assertTrue(withTimeout(3000) { failed.await() } is RefreshResult.Failed)
            assertEquals(before.entries, fixture.ready().entries)
            assertNotNull(fixture.ready().loadInfo.issue)
            assertFalse(fixture.ready().loadInfo.reading)
            assertEquals(6, fixture.commands.size)
        }
    }

    @Test(timeout = 5000)
    fun selectionReplacesUnconsumedRefreshAndItsWaiterTerminatesWithoutOldIo() = runBlocking {
        S3LogRefreshFixture().use { fixture ->
            fixture.warm()
            val obsolete = async(fixture.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                fixture.source.refreshAndAwait()
            }
            // 不 pump 消费者：覆盖 Channel 中尚未开始的 refresh 请求。
            fixture.source.selectFile(fixture.second)
            fixture.drain()
            val selectedRead = fixture.next("read ${fixture.second}")
            assertEquals(RefreshResult.Cancelled, withTimeout(3000) { obsolete.await() })
            assertEquals(fixture.second, fixture.ready().selectedFileId)
            assertTrue(fixture.ready().loadInfo.reading)
            assertTrue(fixture.ready().entries.isEmpty())
            assertEquals(3, fixture.commands.size)
            fixture.reply(selectedRead, fixture.tail(fixture.second))
            assertEquals(fixture.second, fixture.ready().selectedFileId)
            assertFalse(fixture.ready().loadInfo.reading)
        }
    }

    @Test(timeout = 5000)
    fun lateOldGenerationCannotOverwriteSelectionOrFinishNewRefresh() = runBlocking {
        S3LogRefreshFixture(lateReplies = true).use { fixture ->
            fixture.warm()
            fixture.source.refresh()
            fixture.drain()
            fixture.reply(fixture.next("catalog"), fixture.catalog())
            val lateTail = fixture.next("read ${fixture.first}")
            // 先建立真实在途读，再让 await 加入，避免 Unsupported 导致空命令队列。
            val old = async(fixture.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                fixture.source.refreshAndAwait()
            }
            assertFalse(old.isCompleted)
            fixture.source.selectFile(fixture.second)
            val replacement = async(fixture.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                fixture.source.refreshAndAwait() to fixture.source.state.value
            }
            fixture.drain()
            assertFalse(replacement.isCompleted)
            fixture.reply(lateTail, fixture.tail(fixture.first))
            assertEquals(RefreshResult.Cancelled, withTimeout(3000) { old.await() })

            fixture.reply(fixture.next("catalog"), fixture.catalog())
            val currentTail = fixture.next("read ${fixture.second}")
            assertFalse("旧完成不能结束后继在途读", replacement.isCompleted)
            assertEquals(fixture.second, fixture.ready().selectedFileId)
            assertTrue(fixture.ready().loadInfo.reading)
            assertTrue("不同 file 不继承旧行", fixture.ready().entries.isEmpty())
            fixture.reply(currentTail, fixture.tail(fixture.second))
            val (result, published) = withTimeout(3000) { replacement.await() }
            assertEquals(RefreshResult.Completed, result)
            assertEquals(fixture.second, (published as OperationalLogUiState.Ready).selectedFileId)
            assertFalse(published.loadInfo.reading)
            fixture.assertNoQueuedCommand()
        }
    }

    @Test(timeout = 5000)
    fun emptyCatalogCompletesWithoutInventingATailRead() = runBlocking {
        S3LogRefreshFixture().use { fixture ->
            val result = async(fixture.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                fixture.source.refreshAndAwait() to fixture.source.state.value
            }
            fixture.drain()
            assertFalse(result.isCompleted)
            fixture.reply(fixture.next("catalog"), fixture.catalog(emptyList()))
            assertEquals(RefreshResult.Completed to OperationalLogUiState.Empty,
                withTimeout(3000) { result.await() })
            assertEquals(1, fixture.commands.size)
            fixture.assertNoQueuedCommand()
        }
    }

    @Test(timeout = 5000)
    fun successfullyReadTruncatedOrPartiallyParsedTailIsCompletedNotReadFailure() = runBlocking {
        listOf(true, false).forEach { truncated ->
            S3LogRefreshFixture().use { fixture ->
                fixture.drain()
                fixture.reply(fixture.next("catalog"), fixture.catalog())
                val tail = fixture.next("read ${fixture.first}")
                val result = async(fixture.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                    fixture.source.refreshAndAwait() to fixture.source.state.value
                }
                assertFalse(result.isCompleted)
                val extra = if (truncated) "" else ",\"safe malformed event\""
                fixture.reply(tail, """{"ok":true,"key":"${fixture.first}","truncated":$truncated,"lines":["2026-09-11 12:00:00.000 [INFO] [ims] [0001] safe event"$extra]}""")
                val (outcome, published) = withTimeout(3000) { result.await() }
                assertEquals(RefreshResult.Completed, outcome)
                val warning = published as OperationalLogUiState.Warning
                assertEquals(truncated, warning.truncated)
                assertEquals(!truncated, warning.issues.isNotEmpty())
                assertEquals(1, warning.entries.size)
                assertNull(warning.loadInfo.issue)
                assertFalse(warning.loadInfo.reading)
                assertEquals(2, fixture.commands.size)
            }
        }
    }
}
