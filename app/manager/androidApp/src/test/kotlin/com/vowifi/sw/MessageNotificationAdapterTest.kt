package com.vowifi.sw

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import com.vowifi.sw.manager.data.AndroidMessageNotifications
import com.vowifi.sw.manager.data.SimOwnerId
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
class MessageNotificationAdapterTest {
    @Test fun savedMtBuildsPrivateNotificationAndTapConsumesOpaqueRouteOnce() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val adapter = AndroidMessageNotifications(app, Activity::class.java, R.drawable.ic_stat_message)
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val row = MessageRecord.Incoming("synthetic-one", "+12025550123", "synthetic-private", 1)
        adapter.onSnapshot(owner, listOf(row), row.id)
        adapter.awaitNotifications()
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(R.drawable.ic_stat_message, notification.smallIcon.resId)
        assertEquals(0, notification.actions?.size ?: 0)
        assertEquals(row.preview, notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        val style = Notification.Builder.recoverBuilder(app, notification).style as? Notification.MessagingStyle
        assertNotNull(style)
        assertEquals(row.preview, requireNotNull(style).messages.single().text.toString())
        assertEquals(row.peerLabel, style.messages.single().senderPerson?.name?.toString())
        assertFalse(notification.publicVersion.extras.toString().contains(row.preview))
        assertFalse(notification.publicVersion.extras.toString().contains(row.peerLabel))
        val tap = Intent(shadowOf(notification.contentIntent).savedIntent)
        assertTrue(notification.contentIntent.isImmutable)
        assertEquals(Activity::class.java.name, tap.component?.className)
        val handle = AndroidMessageNotifications.consume(tap)
        assertNotNull(handle)
        assertNull(AndroidMessageNotifications.consume(tap))
        adapter.onSnapshot(owner, listOf(row.copy(isRead = true)), null)
        adapter.awaitNotifications()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun deniedPermissionRetainsUnreadAndRecoveryPostsSilently() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val adapter = AndroidMessageNotifications(app, Activity::class.java, R.drawable.ic_stat_message)
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val row = MessageRecord.Incoming("synthetic-one", "+12025550123", "synthetic-private", 1)
        adapter.onSnapshot(owner, listOf(row), row.id)
        adapter.awaitNotifications()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        adapter.permissionsChanged()
        adapter.awaitNotifications()
        val restored = shadowOf(manager).allNotifications.single()
        assertEquals(row.preview, restored.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(restored.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        val wrong = Intent(shadowOf(restored.contentIntent).savedIntent).putExtra("unexpected", "value")
        assertNull(AndroidMessageNotifications.consume(wrong))
        adapter.openSettings()
        assertEquals("android.settings.CHANNEL_NOTIFICATION_SETTINGS", shadowOf(app).nextStartedActivity.action)
    }

    @Test fun duplicateAndHistoryDoNotRepostAndOwnersHaveDistinctContentIntents() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val adapter = AndroidMessageNotifications(app, Activity::class.java, R.drawable.ic_stat_message)
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val other = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
        val row = MessageRecord.Incoming("first", "+12025550123", "Owner A synthetic", 100)
        adapter.onSnapshot(owner, listOf(row), null)
        adapter.awaitNotifications()
        assertTrue(manager.activeNotifications.isEmpty())
        adapter.onSnapshot(owner, listOf(row), row.id)
        adapter.awaitNotifications()
        val original = manager.activeNotifications.single().notification
        adapter.onSnapshot(owner, listOf(row), null)
        adapter.awaitNotifications()
        assertSame(original, manager.activeNotifications.single().notification)
        val second = row.copy(id = "second", preview = "Owner B synthetic")
        adapter.onSnapshot(other, listOf(second), second.id)
        adapter.awaitNotifications()
        val notifications = manager.activeNotifications.map { it.notification }
        assertEquals(setOf(row.preview, second.preview), notifications.map { it.extras.getString(Notification.EXTRA_TEXT) }.toSet())
        assertNotEquals(notifications[0].contentIntent, notifications[1].contentIntent)
        notifications.forEach {
            val intent = Intent(shadowOf(it.contentIntent).savedIntent)
            assertEquals(setOf(AndroidMessageNotifications.EXTRA_HANDLE), intent.extras?.keySet())
            assertNotNull(AndroidMessageNotifications.consume(intent))
            assertFalse(it.publicVersion.extras.toString().contains("synthetic"))
        }
        adapter.onRead(owner, setOf(row.id))
        adapter.awaitNotifications()
        assertEquals(second.preview, manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test fun alphanumericOriginatorShowsItsLabelButPublicVersionRemainsGeneric() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val adapter = AndroidMessageNotifications(app, Activity::class.java, R.drawable.ic_stat_message)
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val row = MessageRecord.Incoming("alpha", "ExampleBank", "Synthetic text", 100)
        adapter.onSnapshot(owner, listOf(row), row.id)
        adapter.awaitNotifications()
        val notification = manager.activeNotifications.single().notification
        val style = Notification.Builder.recoverBuilder(app, notification).style as Notification.MessagingStyle
        assertEquals("ExampleBank", style.messages.single().senderPerson?.name?.toString())
        assertFalse(notification.extras.getString(Notification.EXTRA_TITLE).orEmpty().contains("raw:"))
        assertEquals("SoftVoWiFi", notification.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("收到新短信", notification.publicVersion.extras.getString(Notification.EXTRA_TEXT))
        assertFalse(notification.publicVersion.extras.toString().contains(row.peerLabel))
        assertFalse(notification.publicVersion.extras.toString().contains(row.preview))
    }
}
