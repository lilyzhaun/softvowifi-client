@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.Manifest
import android.app.Activity
import android.app.Application
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MessageNotificationEffectsTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val row = MessageRecord.Incoming("row", "+12025550123", "Synthetic original", 100)
    private val conversation = StoredMessageConversation(owner, PeerNumberNormalizer.normalize(row.peerLabel))

    @Test fun queuedPostsCoalesceLatestPayloadWithoutLosingTheFreshAlert() {
        val dispatcher = QueuedDispatcher()
        val sink = RecordingSink()
        val adapter = adapter(sink, dispatcher)
        val fresh = row.copy(id = "new", preview = "Synthetic latest", timestampMs = 200)
        adapter.onSnapshot(owner, listOf(row), row.id)
        adapter.onSnapshot(owner, listOf(row.copy(preview = "Synthetic correction")), null)
        adapter.onSnapshot(owner, listOf(row, fresh), fresh.id)
        assertEquals(1, dispatcher.tasks.size)
        assertTrue(sink.posts.isEmpty())
        dispatcher.run()
        adapter.awaitNotifications()
        assertEquals(1, sink.posts.size)
        assertEquals(2, sink.posts.single().count)
        assertTrue(sink.posts.single().alert)
        assertEquals(fresh.preview, sink.posts.single().presentation?.messages?.last()?.preview)
    }

    @Test fun readBeforeDrainReplacesQueuedPostWithExactCancellation() {
        val dispatcher = QueuedDispatcher()
        val sink = RecordingSink()
        val adapter = adapter(sink, dispatcher)
        adapter.onSnapshot(owner, listOf(row), row.id)
        adapter.onRead(owner, setOf(row.id))
        dispatcher.run()
        adapter.awaitNotifications()
        assertTrue(sink.posts.isEmpty())
        assertEquals(listOf(conversation.handle), sink.cancellations)
    }

    @Test fun queuedBodiesAreBoundedWithoutDroppingCountsOrFreshAlerts() {
        val dispatcher = QueuedDispatcher()
        val sink = RecordingSink()
        val adapter = adapter(sink, dispatcher)
        val rows = (0..39).map { row.copy(id = "row-$it", peerLabel = "Example$it") }
        rows.indices.forEach { index -> adapter.onSnapshot(owner, rows.take(index + 1), rows[index].id) }
        assertEquals(1, dispatcher.tasks.size)
        dispatcher.run()
        adapter.awaitNotifications()
        assertEquals(40, sink.posts.size)
        assertEquals(32, sink.posts.count { it.presentation != null })
        assertTrue(sink.posts.all { it.count == 1 && it.alert })
    }

    @Test fun readQueuedDuringBlockedPlatformPostCancelsAfterItReturns() {
        val sink = RecordingSink(blockPost = true)
        val adapter = adapter(sink)
        try {
            adapter.onSnapshot(owner, listOf(row), row.id)
            assertTrue(sink.entered.await(3, TimeUnit.SECONDS))
            adapter.onRead(owner, setOf(row.id))
            assertTrue(sink.cancellations.isEmpty())
        } finally { sink.release.countDown(); adapter.awaitNotifications() }
        assertEquals(listOf(conversation.handle), sink.cancellations)
        assertTrue(sink.active.isEmpty())
    }

    @Test fun stopCannotDiscardReadCancellationWhenANewerPostWasCoalescedOverIt() {
        val dispatcher = QueuedDispatcher()
        val sink = RecordingSink()
        val adapter = adapter(sink, dispatcher)
        adapter.onSnapshot(owner, listOf(row), row.id)
        dispatcher.run()
        adapter.onRead(owner, setOf(row.id))
        val fresh = row.copy(id = "new")
        adapter.onSnapshot(owner, listOf(fresh), fresh.id)
        adapter.unbindPreviewLookup()
        dispatcher.run()
        adapter.awaitNotifications()
        assertTrue(sink.active.isEmpty())
        assertEquals(1, sink.posts.size)
    }

    @Test fun blockedActiveQueryCannotPublishAfterReadInvalidatesItsCommand() {
        val sink = RecordingSink()
        val adapter = adapter(sink)
        adapter.onSnapshot(owner, listOf(row), row.id)
        adapter.awaitNotifications()
        sink.blockActive = true
        try {
            adapter.onSnapshot(owner, listOf(row.copy(preview = "Synthetic correction")), null)
            assertTrue(sink.entered.await(3, TimeUnit.SECONDS))
            adapter.onRead(owner, setOf(row.id))
            adapter.visible(null)
            adapter.permissionsChanged()
        } finally { sink.release.countDown(); adapter.awaitNotifications() }
        assertEquals(1, sink.posts.size)
        assertTrue(sink.active.isEmpty())
    }

    @Test fun stopCompensatesOnlyInFlightPostAndAllowsSubsequentBinding() {
        val sink = RecordingSink(blockPost = true)
        val adapter = adapter(sink)
        try {
            adapter.onSnapshot(owner, listOf(row), row.id)
            assertTrue(sink.entered.await(3, TimeUnit.SECONDS))
            adapter.unbindPreviewLookup()
        } finally { sink.release.countDown(); adapter.awaitNotifications() }
        assertTrue(sink.active.isEmpty())
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        try {
            adapter.bindPreviewLookup(scope) { emptyList() }
            val fresh = row.copy(id = "new")
            adapter.onSnapshot(owner, listOf(fresh), fresh.id)
            adapter.awaitNotifications()
            assertEquals(setOf(conversation.handle), sink.active)
        } finally { adapter.unbindPreviewLookup(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    private fun adapter(sink: RecordingSink, dispatcher: CoroutineDispatcher? = null): AndroidMessageNotifications {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        return if (dispatcher == null) AndroidMessageNotifications(app, Activity::class.java, R.drawable.ic_stat_message, sink)
        else AndroidMessageNotifications(app, Activity::class.java, R.drawable.ic_stat_message, sink, dispatcher)
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun run() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private class Posted(val count: Int, val alert: Boolean, val presentation: MessageNotificationPresentation?)
    private class RecordingSink(private val blockPost: Boolean = false) : MessageNotificationSink {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val posts = mutableListOf<Posted>()
        val cancellations = mutableListOf<ConversationHandle>()
        val active = mutableSetOf<ConversationHandle>()
        @Volatile var blockActive = false
        override fun isActive(handle: ConversationHandle): Boolean {
            if (blockActive) waitForRelease()
            return handle in active
        }
        override fun post(handle: ConversationHandle, count: Int, alert: Boolean, presentation: MessageNotificationPresentation?) {
            if (blockPost) waitForRelease()
            posts += Posted(count, alert, presentation)
            active += handle
        }
        override fun cancel(handle: ConversationHandle) { cancellations += handle; active -= handle }
        private fun waitForRelease() { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
    }
}
