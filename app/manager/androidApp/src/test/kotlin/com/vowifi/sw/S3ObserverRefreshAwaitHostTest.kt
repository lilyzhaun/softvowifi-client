package com.vowifi.sw

import com.vowifi.sw.manager.data.RefreshResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// 新完成契约仍由实际 tail/parser/单消费者产出；不手动发送请求终态。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [S3ObserverTailShadow::class],
    instrumentedPackages = ["com.vowifi.sw.manager.data"])
class S3ObserverRefreshAwaitHostTest {
    @Test fun duplicateUsersJoinTheirReadAndDoNotAwaitTheTrailingAutomaticBatch() = runBlocking {
        S3ObserverIoFixture().use { fixture ->
            try {
                fixture.first.awaitEntered()
                val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshAndAwait() }
                val duplicate = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshAndAwait() }
                assertFalse(first.isCompleted)
                assertFalse(duplicate.isCompleted)
                assertEquals(1, S3ObserverTailShadow.reads.get())
                fixture.appendAndNotify(2) // 真 FileObserver 回调路径保留尾随信号。
                fixture.first.release()
                fixture.second.awaitEntered()
                assertEquals(RefreshResult.Completed, withTimeout(3000) { first.await() })
                assertEquals(first.await(), withTimeout(3000) { duplicate.await() })
                assertEquals(1L, fixture.latest()) // 用户终态发生在自己批次发布之后。
                assertEquals(2, S3ObserverTailShadow.reads.get())
                fixture.second.release()
                withTimeout(3000) {
                    fixture.source.state.first {
                        it is com.vowifi.sw.manager.model.RegistrationUiState.Data &&
                            it.snapshot.events.last().timestampMs == 2L
                    }
                }
                assertEquals(2, S3ObserverTailShadow.reads.get()) // 重复 User 未再发第三次读取。
            } finally { fixture.first.release(); fixture.second.release() }
        }
    }

    @Test fun sameSnapshotCompletesAndCloseReleasesAStillHeldBatchWaiter() = runBlocking {
        S3ObserverIoFixture().use { fixture ->
            try {
                fixture.first.awaitEntered()
                val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshAndAwait() }
                fixture.first.release()
                assertEquals(RefreshResult.Completed, withTimeout(3000) { first.await() })
                val before = fixture.source.state.value
                val unchanged = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshAndAwait() }
                fixture.second.awaitEntered()
                assertFalse(unchanged.isCompleted)
                fixture.second.release()
                assertEquals(RefreshResult.Completed, withTimeout(3000) { unchanged.await() })
                assertEquals(before, fixture.source.state.value)

                val lastHold = S3IoHold()
                S3ObserverTailShadow.holds.add(lastHold)
                try {
                    val closing = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshAndAwait() }
                    lastHold.awaitEntered()
                    fixture.source.close()
                    // close 明确取消等待者；未释放真实 tail 屏障也不得悬挂或返回成功。
                    try {
                        withTimeout(3000) { closing.await() }
                        fail("关闭源不得宣称读取完成")
                    } catch (error: CancellationException) {
                        assertFalse("超时不是正确的取消终态", error is TimeoutCancellationException)
                    }
                    assertEquals(RefreshResult.Unsupported, withTimeout(3000) { fixture.source.refreshAndAwait() })
                    assertEquals(before, fixture.source.state.value)
                } finally { lastHold.release() }
            } finally { fixture.first.release(); fixture.second.release() }
        }
    }
}
