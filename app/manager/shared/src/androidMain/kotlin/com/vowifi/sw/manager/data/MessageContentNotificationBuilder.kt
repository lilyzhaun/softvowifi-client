package com.vowifi.sw.manager.data

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context

internal fun buildMessageContentNotification(
    context: Context,
    icon: Int,
    pending: PendingIntent,
    count: Int,
    alert: Boolean,
    presentation: MessageNotificationPresentation?,
): Notification {
    val publicVersion = Notification.Builder(context, AndroidMessageNotifications.CHANNEL).setSmallIcon(icon)
        .setContentTitle("SoftVoWiFi").setContentText("收到新短信").build()
    val builder = Notification.Builder(context, AndroidMessageNotifications.CHANNEL).setSmallIcon(icon)
        .setContentTitle(presentation?.peer ?: "SoftVoWiFi")
        .setContentText(presentation?.messages?.lastOrNull()?.preview ?: "有 $count 条未读短信")
        .setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE)
        .setPublicVersion(publicVersion).setContentIntent(pending).setAutoCancel(true)
        .setOnlyAlertOnce(!alert).setNumber(count)
        .setGroup(if (alert) null else "message-silent-recovery")
        .setGroupAlertBehavior(if (alert) Notification.GROUP_ALERT_ALL else Notification.GROUP_ALERT_SUMMARY)
    if (presentation != null) {
        val peer = Person.Builder().setName(presentation.peer).build()
        val style = Notification.MessagingStyle(Person.Builder().setName("本机").build()).setGroupConversation(false)
        presentation.messages.forEach { style.addMessage(it.preview, it.timestampMs, peer) }
        builder.setStyle(style).setWhen(presentation.messages.last().timestampMs)
        if (count > presentation.messages.size) builder.setSubText("$count 条未读 · 最近 ${presentation.messages.size} 条")
    }
    return builder.build()
}
