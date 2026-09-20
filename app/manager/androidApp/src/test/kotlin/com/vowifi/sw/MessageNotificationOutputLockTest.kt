@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.Manifest
import android.app.Activity
import android.app.Application
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MessageNotificationOutputLockTest {
    @Test fun visibleAndPermissionStateReturnWhileNotificationOutputIsBlocked() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val sink = object : MessageNotificationSink {
            override fun isActive(handle: ConversationHandle) = true
            override fun cancel(handle: ConversationHandle) = Unit
            override fun post(handle: ConversationHandle, count: Int, alert: Boolean, presentation: MessageNotificationPresentation?) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val adapter = AndroidMessageNotifications(app, Activity::class.java, R.drawable.ic_stat_message, sink)
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val row = MessageRecord.Incoming("row", "+12025550123", "Synthetic text", 100)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val posting = executor.submit { adapter.onSnapshot(owner, listOf(row), row.id) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val visibility = executor.submit {
                adapter.visible(StoredMessageConversation(owner, PeerNumberNormalizer.normalize(row.peerLabel)).handle)
                adapter.permissionsChanged()
                returned.countDown()
            }
            assertTrue("State callbacks must not wait for blocked notification output", returned.await(500, TimeUnit.MILLISECONDS))
            release.countDown()
            posting.get(3, TimeUnit.SECONDS)
            visibility.get(3, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(3, TimeUnit.SECONDS)
            adapter.awaitNotifications()
        }
    }
}
