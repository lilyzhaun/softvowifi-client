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

// 只驱动真实AndroidOperationalLogSource命令/IO边界，复用既有屏障与5s超时。
class OperationalLogRetryContinuityHostTest {
    @Test(timeout = 5000)
    fun sameFileIssueAndTailSurviveEnqueueAndRelistUntilTheRealReadEnds() = runBlocking {
        S3LogRefreshFixture().use { f ->
            f.warm()
            f.source.refresh(); f.drain()
            f.reply(f.next("catalog"), f.catalog())
            f.reply(f.next("read ${f.first}"), "invalid synthetic response")
            var previous = f.ready()
            assertNotNull(previous.loadInfo.issue); assertFalse(previous.loadInfo.reading)
            for (succeeds in listOf(false, true)) {
                val waiting = async(f.dispatcher, start = CoroutineStart.UNDISPATCHED) { f.source.refreshAndAwait() }
                assertEquals(previous.entries, f.ready().entries)
                assertEquals(previous.loadInfo.issue, f.ready().loadInfo.issue)
                assertTrue(f.ready().loadInfo.reading); assertFalse(waiting.isCompleted)
                f.drain(); f.reply(f.next("catalog"), f.catalog())
                val held = f.next("read ${f.first}")
                assertEquals(previous.entries, f.ready().entries)
                assertEquals(previous.loadInfo.issue, f.ready().loadInfo.issue)
                assertTrue(f.ready().loadInfo.reading); assertFalse(waiting.isCompleted)
                if (succeeds) f.reply(held, f.tail(f.first))
                else f.reply(held, """{"ok":false,"code":"IO_FAILED","message":"synthetic failure"}""", 1)
                val result = withTimeout(3000) { waiting.await() }
                assertFalse(f.ready().loadInfo.reading)
                assertEquals(previous.entries, f.ready().entries)
                if (succeeds) {
                    assertEquals(RefreshResult.Completed, result); assertNull(f.ready().loadInfo.issue)
                } else {
                    assertTrue(result is RefreshResult.Failed); assertNotNull(f.ready().loadInfo.issue)
                    assertNotEquals(previous.loadInfo.issue, f.ready().loadInfo.issue)
                }
                previous = f.ready()
            }
            f.assertNoQueuedCommand()
        }
    }

    @Test(timeout = 5000)
    fun fileSelectionDoesNotInheritIssueOrAcceptLateOldRead() = runBlocking {
        S3LogRefreshFixture(lateReplies = true).use { f ->
            f.warm(); f.source.refresh(); f.drain()
            f.reply(f.next("catalog"), f.catalog())
            f.reply(f.next("read ${f.first}"), "invalid synthetic response")
            assertNotNull(f.ready().loadInfo.issue)
            val old = async(f.dispatcher, start = CoroutineStart.UNDISPATCHED) { f.source.refreshAndAwait() }
            f.drain(); f.reply(f.next("catalog"), f.catalog())
            val late = f.next("read ${f.first}")
            f.source.selectFile(f.second); f.drain()
            assertEquals(f.second, f.ready().selectedFileId)
            assertTrue(f.ready().entries.isEmpty()); assertNull(f.ready().loadInfo.issue)
            f.reply(late, "invalid late synthetic response")
            assertEquals(RefreshResult.Cancelled, withTimeout(3000) { old.await() })
            assertEquals(f.second, f.ready().selectedFileId); assertNull(f.ready().loadInfo.issue)
            val current = f.next("read ${f.second}")
            assertTrue(f.ready().loadInfo.reading)
            f.reply(current, f.tail(f.second))
            assertEquals(f.second, f.ready().selectedFileId); assertNull(f.ready().loadInfo.issue)
            assertEquals(1, f.ready().entries.size); f.assertNoQueuedCommand()
        }
    }

    @Test(timeout = 5000)
    fun blockingCatalogFailureDoesNotBecomeLoadingWhileCatalogOrTailIsHeld() = runBlocking {
        for (permission in listOf(false, true)) S3LogRefreshFixture().use { f ->
            f.drain()
            if (permission) f.reply(f.next("catalog"), "Permission denied", 1)
            else f.reply(f.next("catalog"), "invalid synthetic catalog")
            val failure = f.source.state.value
            assertTrue(failure is OperationalLogUiState.Failure || failure is OperationalLogUiState.PermissionDenied)
            val waiting = async(f.dispatcher, start = CoroutineStart.UNDISPATCHED) { f.source.refreshAndAwait() }
            assertEquals(failure, f.source.state.value)
            f.drain(); assertFalse(waiting.isCompleted)
            f.reply(f.next("catalog"), f.catalog())
            assertEquals(failure, f.source.state.value)
            val held = f.next("read ${f.first}")
            assertFalse(waiting.isCompleted)
            f.reply(held, f.tail(f.first))
            assertEquals(RefreshResult.Completed, withTimeout(3000) { waiting.await() })
            assertEquals(1, f.ready().entries.size); assertNull(f.ready().loadInfo.issue)
            f.assertNoQueuedCommand()
        }
    }
}
