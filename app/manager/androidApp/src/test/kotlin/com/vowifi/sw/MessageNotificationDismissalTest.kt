package com.vowifi.sw

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import com.vowifi.sw.manager.data.AndroidMessageNotifications
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.StoredMessageConversation
import com.vowifi.sw.manager.model.MessageRecord
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MessageNotificationDismissalTest {
    @Test fun scopedHistoryBaselineAndReadLeaveOtherPeerNotificationActive() {
        val fixture = fixture()
        val other = row("other").copy(peerLabel = "15550000002")
        fixture.adapter.onSnapshot(fixture.owner, listOf(row("one"), row("two"), other), other.id)
        val first = StoredMessageConversation(fixture.owner, "15550000001")
        val second = StoredMessageConversation(fixture.owner, "15550000002")
        fixture.adapter.onThreadSnapshot(first, listOf(row("one"), row("two")))
        fixture.adapter.awaitNotifications()
        assertEquals(setOf(first.handle.value, second.handle.value), fixture.manager.activeNotifications.map { it.tag }.toSet())
        fixture.adapter.onRead(fixture.owner, setOf("one", "two"))
        fixture.adapter.awaitNotifications()
        val remaining = fixture.manager.activeNotifications.single()
        assertEquals(second.handle.value, remaining.tag)
        assertEquals(1, remaining.notification.number)
    }

    @Test fun notificationTapRemovalDoesNotResurrectVisibleThreadOnPartialRead() {
        val fixture = fixture()
        val active = fixture.manager.activeNotifications.single()
        assertTrue(active.notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val handle = requireNotNull(AndroidMessageNotifications.consume(Intent(shadowOf(active.notification.contentIntent).savedIntent)))
        fixture.manager.cancel(active.tag, active.id)
        fixture.adapter.visible(handle)
        fixture.adapter.onRead(fixture.owner, setOf("one"))
        fixture.adapter.visible(null)
        fixture.adapter.onSnapshot(fixture.owner, listOf(row("two")), null)
        fixture.adapter.awaitNotifications()
        assertEquals(0, fixture.manager.activeNotifications.size)
    }

    @Test fun appEntryPartialReadUpdatesExistingTagSilently() {
        val fixture = fixture()
        val tag = fixture.manager.activeNotifications.single().tag
        val notification = fixture.manager.activeNotifications.single().notification
        val handle = requireNotNull(AndroidMessageNotifications.consume(Intent(shadowOf(notification.contentIntent).savedIntent)))
        fixture.adapter.visible(handle)
        fixture.adapter.onRead(fixture.owner, setOf("one"))
        fixture.adapter.awaitNotifications()
        val remaining = fixture.manager.activeNotifications.single()
        assertEquals(tag, remaining.tag)
        assertEquals(1, remaining.notification.number)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, remaining.notification.groupAlertBehavior)
        fixture.adapter.onRead(fixture.owner, setOf("two"))
        fixture.adapter.awaitNotifications()
        assertEquals(0, fixture.manager.activeNotifications.size)
    }

    @Test fun removedNotificationDoesNotResurrectOnReadButNewMtCanAlert() {
        val fixture = fixture()
        val active = fixture.manager.activeNotifications.single()
        fixture.manager.cancel(active.tag, active.id)
        fixture.adapter.onRead(fixture.owner, setOf("one"))
        fixture.adapter.awaitNotifications()
        assertEquals(0, fixture.manager.activeNotifications.size)
        fixture.adapter.onSnapshot(fixture.owner, listOf(row("two")), null)
        fixture.adapter.awaitNotifications()
        assertEquals(0, fixture.manager.activeNotifications.size)
        fixture.adapter.onSnapshot(fixture.owner, listOf(row("two"), row("three")), "three")
        fixture.adapter.awaitNotifications()
        assertEquals(2, fixture.manager.activeNotifications.single().notification.number)
    }

    private fun fixture(): Fixture {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val adapter = AndroidMessageNotifications(app, MainActivity::class.java, R.drawable.ic_stat_message)
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        adapter.onSnapshot(owner, listOf(row("one"), row("two")), "two")
        adapter.awaitNotifications()
        return Fixture(adapter, owner, app.getSystemService(NotificationManager::class.java))
    }
    private fun row(id: String) = MessageRecord.Incoming(id, "+15550000001", "synthetic", 1)
    private class Fixture(val adapter: AndroidMessageNotifications, val owner: SimOwnerId, val manager: NotificationManager)
}
