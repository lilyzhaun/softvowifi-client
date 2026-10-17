package com.vowifi.sw

import android.app.Application
import android.app.NotificationManager
import com.vowifi.sw.manager.data.AndroidCallNotificationSettingsSource
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.data.UnsupportedCallNotificationSettingsSource
import com.vowifi.sw.manager.ui.IncomingCallChannelStatus
import com.vowifi.sw.manager.ui.NotificationPermissionStatus
import com.vowifi.sw.manager.ui.NotificationSnapshotFreshness
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

// 真实同步读取/错误分类回归；执行与回执由统筹负责。
// 不人为 held 通知源，不引入 scope/锁/delay，不用 StateFlow 变值判完成。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [S3NotificationReadShadow::class])
class CallNotificationRefreshAwaitHostTest {
    @Before fun resetReadBoundary() {
        S3NotificationReadShadow.reads = 0
        S3NotificationReadShadow.failRead = false
        S3NotificationReadShadow.crashRead = false
    }

    @Test(timeout = 5000)
    fun deniedMissingChannelAndSameValueCompleteSynchronouslyWithARealReread() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        val source = AndroidCallNotificationSettingsSource(context)
        val beforeReads = S3NotificationReadShadow.reads
        shadowOf(manager).setNotificationsEnabled(false)

        val first = async(start = CoroutineStart.UNDISPATCHED) { source.refreshAndAwait() }
        assertTrue("同步系统读取不应变成异步或人为 busy", first.isCompleted)
        assertEquals(RefreshResult.Completed, withTimeout(3000) { first.await() })
        val denied = source.state.value
        assertEquals(NotificationPermissionStatus.DENIED, denied.permission)
        assertEquals(IncomingCallChannelStatus.MISSING, denied.channel)
        assertEquals(NotificationSnapshotFreshness.CURRENT, denied.freshness)
        assertEquals(beforeReads + 1, S3NotificationReadShadow.reads)

        val same = async(start = CoroutineStart.UNDISPATCHED) { source.refreshAndAwait() }
        assertTrue(same.isCompleted)
        assertEquals(RefreshResult.Completed, withTimeout(3000) { same.await() })
        assertEquals(denied, source.state.value)
        assertEquals(beforeReads + 2, S3NotificationReadShadow.reads)
        assertNull("刷新不创建缺失渠道", manager.getNotificationChannel("incoming_calls"))
        source.refresh() // 旧 automatic 入口同样立即执行真实读。
        assertEquals(beforeReads + 3, S3NotificationReadShadow.reads)
        assertEquals(denied, source.state.value)
    }

    @Test(timeout = 5000)
    fun knownSystemReadFailurePreservesSnapshotButMarksStaleAndCanActuallyRetry() = runBlocking {
        val source = AndroidCallNotificationSettingsSource(RuntimeEnvironment.getApplication())
        val before = source.state.value
        val beforeReads = S3NotificationReadShadow.reads
        S3NotificationReadShadow.failRead = true
        val result = withTimeout(3000) { source.refreshAndAwait() }
        assertTrue(result is RefreshResult.Failed)
        assertFalse(result.toString().contains("private-system-detail"))
        assertEquals(beforeReads + 1, S3NotificationReadShadow.reads)
        assertEquals(before.copy(freshness = NotificationSnapshotFreshness.STALE), source.state.value)

        S3NotificationReadShadow.failRead = false
        assertEquals(RefreshResult.Completed, withTimeout(3000) { source.refreshAndAwait() })
        assertEquals(before, source.state.value)
        assertEquals(beforeReads + 2, S3NotificationReadShadow.reads)
        assertEquals(RefreshResult.Unsupported,
            withTimeout(3000) { UnsupportedCallNotificationSettingsSource.refreshAndAwait() })
    }
    @Test(timeout = 5000)
    fun firstReadSecurityFailureIsStaleUnavailableAndAutomaticRetryIsStillSynchronous() {
        S3NotificationReadShadow.failRead = true
        val source = AndroidCallNotificationSettingsSource(RuntimeEnvironment.getApplication())
        assertEquals(NotificationPermissionStatus.UNAVAILABLE, source.state.value.permission)
        assertEquals(NotificationSnapshotFreshness.STALE, source.state.value.freshness)
        assertEquals(1, S3NotificationReadShadow.reads)
        S3NotificationReadShadow.failRead = false
        source.refresh()
        assertEquals(NotificationPermissionStatus.GRANTED, source.state.value.permission)
        assertEquals(NotificationSnapshotFreshness.CURRENT, source.state.value.freshness)
        assertEquals(2, S3NotificationReadShadow.reads)
        S3NotificationReadShadow.failRead = true
        source.refresh()
        assertEquals(NotificationPermissionStatus.GRANTED, source.state.value.permission)
        assertEquals(NotificationSnapshotFreshness.STALE, source.state.value.freshness)
        assertEquals(3, S3NotificationReadShadow.reads)
    }

    @Test(timeout = 5000)
    fun unexpectedProgrammingErrorIsNotMappedToARefreshFailure() = runBlocking {
        val source = AndroidCallNotificationSettingsSource(RuntimeEnvironment.getApplication())
        val before = source.state.value
        S3NotificationReadShadow.crashRead = true
        try {
            source.refreshAndAwait()
            fail("编程异常不能被吞成刷新结果")
        } catch (error: IllegalStateException) {
            assertEquals("notification programming error", error.message)
            assertEquals(before, source.state.value)
        } finally { S3NotificationReadShadow.crashRead = false }
        assertEquals(RefreshResult.Completed, source.refreshAndAwait())
    }
}

// 复用原系统 shadow 的权限/渠道语义；仅在实际 areNotificationsEnabled 边界观察/抛错。
@Implements(NotificationManager::class)
class S3NotificationReadShadow : ShadowNotificationManager() {
    @Implementation
    override fun areNotificationsEnabled(): Boolean {
        reads++
        if (failRead) throw SecurityException("private-system-detail")
        if (crashRead) throw IllegalStateException("notification programming error")
        return super.areNotificationsEnabled()
    }
    companion object {
        var reads = 0
        var failRead = false
        var crashRead = false
    }
}
