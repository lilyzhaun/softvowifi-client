package com.vowifi.sw.manager.data

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

internal class IncomingCallNotificationBuilder(
    private val context: Context,
    private val actionReceiverClass: Class<*>,
    private val callsActivityClass: Class<*>,
) {
    private val appContext = context.applicationContext

    fun post(
        peerName: String,
        notificationKey: String,
        policy: NotificationPolicyDecision,
        notify: (Notification) -> Unit,
        foregroundNotify: ((Notification) -> Unit)?,
    ): Boolean {
        val answer = buildAnswerPendingIntent(notificationKey)
        val reject = buildRejectPendingIntent(notificationKey)
        val notification = build(peerName, notificationKey, answer, reject, policy)
        try {
            notify(notification)
        } catch (error: IllegalArgumentException) {
            if (error.message?.contains("CallStyle notifications must be for a foreground service") != true) throw error
            // HyperOS 4 CN 的 MIUIOP(10021) 拒绝会清空 FSI，且不标记标准 FSI 拒绝。
            // 由已运行的 ObserverService 真实持有同一 CallStyle，不伪造 FGS flag 或降为普通模板。
            // 若厂商保留标准拒绝标志且实机验证可直接发布，这条准入处理即可删除。
            val publish = foregroundNotify ?: return false
            publish(notification)
        }
        return true
    }

    fun build(
        peerName: String,
        notificationKey: String,
        answerPending: PendingIntent,
        rejectPending: PendingIntent,
        policy: NotificationPolicyDecision,
    ): Notification {
        val person = Person.Builder()
            .setName(peerName)
            .build()

        val builder = Notification.Builder(appContext, AndroidIncomingCallNotificationCoordinator.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(peerName)
            .setContentText("来电")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(appContext, AndroidIncomingCallNotificationCoordinator.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_call).setContentTitle("SoftVoWiFi").setContentText("有来电").build())
            .setAutoCancel(false)
            .setContentIntent(buildOpenCallsPending(notificationKey))

        builder.setStyle(Notification.CallStyle.forIncomingCall(person, rejectPending, answerPending))

        if (shouldPostIncomingCallNotification(policy)) {
            builder.setFullScreenIntent(buildFullScreenPending(notificationKey), true)
        }

        when (policy) {
            NotificationPolicyDecision.HEADS_UP_FSI_DENIED -> {
                builder.addAction(
                    Notification.Action.Builder(
                        null,
                        "开启全屏通知",
                        buildOpenFullScreenSettingsPending(),
                    ).build(),
                )
            }
            NotificationPolicyDecision.IN_APP_CALLS,
            NotificationPolicyDecision.HEADS_UP,
            NotificationPolicyDecision.FULL_SCREEN,
            NotificationPolicyDecision.PERMISSION_DENIED,
            -> Unit
        }

        return builder.build()
    }

    fun buildAnswerPendingIntent(notificationKey: String): PendingIntent {
        return PendingIntent.getActivity(
            appContext,
            AndroidIncomingCallNotificationCoordinator.ANSWER_REQUEST_CODE,
            buildOpenCallsIntent(notificationKey).apply {
                action = IncomingCallAction.ANSWER.actionName
                putExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY, notificationKey)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun buildRejectPendingIntent(notificationKey: String): PendingIntent {
        return PendingIntent.getBroadcast(
            appContext,
            AndroidIncomingCallNotificationCoordinator.REJECT_REQUEST_CODE,
            buildActionIntent(notificationKey, IncomingCallAction.REJECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildActionIntent(notificationKey: String, action: IncomingCallAction): Intent {
        return Intent(appContext, actionReceiverClass).apply {
            this.action = action.actionName
            data = Uri.parse("softvowifi-call:$notificationKey")
            putExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY, notificationKey)
        }
    }

    fun buildOpenCallsIntent(notificationKey: String): Intent {
        return Intent(appContext, callsActivityClass).apply {
            data = Uri.parse("softvowifi-call:$notificationKey")
            putExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY, notificationKey)
            putExtra(
                AndroidIncomingCallNotificationCoordinator.EXTRA_DESTINATION,
                AndroidIncomingCallNotificationCoordinator.DESTINATION_CALLS,
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
        }
    }

    private fun buildOpenCallsPending(notificationKey: String): PendingIntent {
        return PendingIntent.getActivity(
            appContext,
            AndroidIncomingCallNotificationCoordinator.OPEN_CALLS_REQUEST_CODE,
            buildOpenCallsIntent(notificationKey),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildFullScreenPending(notificationKey: String): PendingIntent {
        return PendingIntent.getActivity(
            appContext,
            AndroidIncomingCallNotificationCoordinator.FULL_SCREEN_REQUEST_CODE,
            buildOpenCallsIntent(notificationKey),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildOpenFullScreenSettingsPending(): PendingIntent {
        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.parse("package:${appContext.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            appContext,
            AndroidIncomingCallNotificationCoordinator.FSI_SETTINGS_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
