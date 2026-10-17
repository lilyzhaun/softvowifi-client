package com.vowifi.sw

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// 只验证真实Service创建参数和主机渠道保全，不模拟NMS升档或宣称实机通知消失。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ObserverNotificationRepostHostTest {
    @Test fun freshObserverChannelIsBlockableAcrossThreeCreateAndRestoreCycles() = checkReposts(null)

    @Test fun repostsPreserveExplicitLowImportance() = checkReposts(NotificationManager.IMPORTANCE_LOW)

    @Test fun repostsPreserveExplicitHighImportance() = checkReposts(NotificationManager.IMPORTANCE_HIGH)

    @Test fun repostsPreservePreviouslyHiddenImportance() = checkReposts(NotificationManager.IMPORTANCE_NONE)

    private fun checkReposts(selectedImportance: Int?) {
        val app = RuntimeEnvironment.getApplication() as Application
        val manager = app.getSystemService(NotificationManager::class.java)
        for (id in listOf("messages", "incoming_calls", "mic")) {
            manager.createNotificationChannel(NotificationChannel(id, id, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "synthetic-$id"
                setShowBadge(false)
            })
        }
        val otherChannels = listOf("messages", "incoming_calls", "mic").associateWith {
            bytes(requireNotNull(manager.getNotificationChannel(it)))
        }
        if (selectedImportance != null) {
            val selected = NotificationChannel("ims_observer", "Observer", selectedImportance)
            NotificationChannel::class.java.getMethod("setBlockable", Boolean::class.javaPrimitiveType)
                .invoke(selected, true)
            val importanceLock = NotificationChannel::class.java.getDeclaredField("USER_LOCKED_IMPORTANCE").getInt(null)
            NotificationChannel::class.java.getMethod("lockFields", Int::class.javaPrimitiveType)
                .invoke(selected, importanceLock)
            manager.createNotificationChannel(selected)
            val existing = requireNotNull(manager.getNotificationChannel("ims_observer"))
            assertEquals("前提：既有重要度已进入真实NotificationManager", selectedImportance.toInt(), existing.importance)
            assertTrue("前提：主机渠道已持有显式重要度锁", existing.hasUserSetImportance())
        } else {
            assertNull("前提：首次创建不能沿用旧渠道", manager.getNotificationChannel("ims_observer"))
        }
        repeat(3) {
            val controller = Robolectric.buildService(ObserverService::class.java)
            try {
                controller.create()
                val service = controller.get()
                fun assertPreserved() {
                    val channel = requireNotNull(manager.getNotificationChannel("ims_observer"))
                    assertTrue("每次真实create/restore必须声明渠道可阻止",
                        NotificationChannel::class.java.getMethod("isBlockable").invoke(channel) as Boolean)
                    assertEquals("重发不能循环覆盖用户重要度",
                        selectedImportance ?: NotificationManager.IMPORTANCE_LOW, channel.importance)
                    if (selectedImportance != null) assertTrue("用户重要度锁必须保留", channel.hasUserSetImportance())
                    assertFalse("观察服务不能以停止冒隐藏", shadowOf(service).isStoppedBySelf)
                    assertFalse("不得撤销真实前台服务状态", shadowOf(service).isForegroundStopped)
                    assertTrue("原前台通知必须仍由服务持有", shadowOf(service).isLastForegroundNotificationAttached)
                    assertEquals("原前台宿主不能被替换", 1, shadowOf(service).lastForegroundNotificationId)
                    assertEquals("ims_observer", shadowOf(service).lastForegroundNotification.channelId)
                    for ((id, before) in otherChannels) {
                        assertArrayEquals("不能修改其它通知渠道$id", before,
                            bytes(requireNotNull(manager.getNotificationChannel(id))))
                    }
                }
                assertPreserved()
                repeat(3) {
                    service.restoreObserverNotification()
                    assertPreserved()
                }
            } finally {
                controller.destroy()
            }
        }
    }

    private fun bytes(channel: NotificationChannel): ByteArray {
        val parcel = Parcel.obtain()
        return try {
            channel.writeToParcel(parcel, 0)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }
}
