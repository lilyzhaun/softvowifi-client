package com.vowifi.sw

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import com.vowifi.sw.manager.data.AndroidMessageNotifications
import com.vowifi.sw.manager.data.PeerNumberNormalizer
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.StoredMessageConversation
import com.vowifi.sw.manager.model.MessageRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MessageNotificationPreviewRaceTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val conversation = StoredMessageConversation(owner, PeerNumberNormalizer.normalize("+12025550123"))
    private val row = MessageRecord.Incoming("row", "+12025550123", "Synthetic original", 100)

    @Test fun dismissedNotificationDoesNotResurrectWhenLookupFinishes() = pendingLookup { adapter, manager, finish ->
        manager.cancel(conversation.handle.value, 1)
        finish()
        assertTrue(manager.activeNotifications.isEmpty())
        adapter.onSnapshot(owner, listOf(row), null)
        adapter.awaitNotifications()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun readCancelsOnlyTaggedMessageAndLateLookupCannotRepost() = pendingLookup { adapter, manager, finish ->
        manager.notify(1, Notification.Builder(RuntimeEnvironment.getApplication(), AndroidMessageNotifications.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_message).setContentTitle("Observer sentinel").build())
        adapter.onRead(owner, setOf(row.id))
        finish()
        assertEquals(listOf<String?>(null), manager.activeNotifications.map { it.tag })
    }

    @Test fun freshArrivalSupersedesPendingPreviewAndDoesNotRollBackBody() = pendingLookup { adapter, manager, finish ->
        val fresh = row.copy(id = "new", preview = "Synthetic latest", timestampMs = 200)
        adapter.onSnapshot(owner, listOf(row, fresh), fresh.id)
        finish()
        assertEquals(fresh.preview, manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(2, manager.activeNotifications.single().notification.number)
    }

    @Test fun sameCountBodyChangeUpdatesActiveSilently() = pendingLookup { adapter, manager, finish ->
        val revised = row.copy(preview = "Synthetic revised")
        adapter.onSnapshot(owner, listOf(revised), null)
        finish()
        val notification = manager.activeNotifications.single().notification
        assertEquals(revised.preview, notification.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test fun visibilityChangeInvalidatesLookupEvenAfterLeavingThread() = pendingLookup { adapter, manager, finish ->
        adapter.visible(conversation.handle)
        adapter.visible(null)
        finish()
        assertEquals(row.preview, manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test fun visitingAnotherConversationDoesNotInvalidateThisPendingPreview() = pendingLookup { adapter, manager, finish ->
        val other = StoredMessageConversation(owner, PeerNumberNormalizer.normalize("+12025550124"))
        adapter.visible(other.handle)
        adapter.visible(null)
        finish()
        assertEquals("Late lookup", manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test fun emptyLookupKeepsUnreadCountButNeverKeepsOldPrivateBody() = pendingLookup(emptyList()) { adapter, manager, finish ->
        finish()
        val notification = manager.activeNotifications.single().notification
        assertEquals(1, notification.number)
        assertEquals("有 1 条未读短信", notification.extras.getString(Notification.EXTRA_TEXT))
        assertNull(Notification.Builder.recoverBuilder(RuntimeEnvironment.getApplication(), notification).style)
        assertFalse(row.isRead)
        adapter.onThreadUnread(conversation, setOf(row.id))
        adapter.awaitNotifications()
        assertEquals(1, manager.activeNotifications.single().notification.number)
    }

    @Test fun sourceUnbindClearsPreviewAndPreventsLateOrFurtherPosting() = pendingLookup { adapter, manager, finish ->
        adapter.javaClass.methods.single { it.name.startsWith("unbindPreviewLookup") }.invoke(adapter)
        finish()
        adapter.onSnapshot(owner, listOf(row.copy(preview = "After dispose")), row.id)
        adapter.awaitNotifications()
        assertEquals(row.preview, manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test fun deniedPermissionDuringLookupNeverPostsAndRestoresSilently() = pendingLookup { adapter, manager, finish ->
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        adapter.permissionsChanged()
        manager.cancel(conversation.handle.value, 1)
        finish()
        assertTrue(manager.activeNotifications.isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        adapter.permissionsChanged()
        adapter.awaitNotifications()
        assertTrue(manager.activeNotifications.single().notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    private fun pendingLookup(result: List<MessageRecord> = listOf(row.copy(preview = "Late lookup")), block: (AndroidMessageNotifications, NotificationManager, () -> Unit) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val adapter = AndroidMessageNotifications(app, Activity::class.java, R.drawable.ic_stat_message)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<List<MessageRecord>>()
        val lookup: suspend (StoredMessageConversation) -> List<MessageRecord> = {
            assertEquals(conversation, it)
            gate.await()
        }
        adapter.javaClass.methods.single { it.name.startsWith("bindPreviewLookup") }.invoke(adapter, scope, lookup)
        try {
            adapter.onSnapshot(owner, listOf(row), row.id)
            adapter.awaitNotifications()
            adapter.onThreadUnread(conversation, setOf(row.id))
            block(adapter, manager) { gate.complete(result); adapter.awaitNotifications() }
        } finally { scope.cancel(); adapter.awaitNotifications() }
    }
}
