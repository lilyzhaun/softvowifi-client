package com.vowifi.sw.manager.data

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.vowifi.sw.manager.ui.navigation.ConversationHandle

internal class AndroidMessageNotificationSink(
    private val context: Context,
    private val activityClass: Class<*>,
    private val icon: Int,
) : MessageNotificationSink {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun canPost(): Boolean = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
        manager.areNotificationsEnabled() && manager.getNotificationChannel(AndroidMessageNotifications.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    override fun isActive(handle: ConversationHandle): Boolean {
        if (!canPost()) throw SecurityException()
        return manager.activeNotifications.any { it.tag == handle.value && it.id == 1 }
    }

    override fun post(handle: ConversationHandle, count: Int, alert: Boolean, presentation: MessageNotificationPresentation?) {
        if (!canPost()) throw SecurityException()
        val intent = Intent(context, activityClass).setAction(AndroidMessageNotifications.ACTION)
            .setData(Uri.parse("softvowifi-message:${handle.value}"))
            .putExtra(AndroidMessageNotifications.EXTRA_HANDLE, handle.value)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(handle.value, 1, buildMessageContentNotification(context, icon, pending, count, alert, presentation))
    }

    override fun cancel(handle: ConversationHandle) { manager.cancel(handle.value, 1) }
}
