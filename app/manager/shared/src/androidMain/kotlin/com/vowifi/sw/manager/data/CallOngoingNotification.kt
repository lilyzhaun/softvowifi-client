package com.vowifi.sw.manager.data

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vowifi.sw.manager.model.*

object CallOngoingNotification {
    const val ID = 101
    const val CHANNEL = "ongoing_calls"
    const val END_ACTION = "com.vowifi.sw.END_CURRENT_CALL"
    fun build(context: Context, key: String?, state: CallUiState.Ready?): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "当前通话", NotificationManager.IMPORTANCE_LOW))
        val text = when {
            state?.sync != null && state.sync != CallSyncState.Ready -> "通话状态待确认"
            state?.phase == CallPhase.InCall -> "通话中"
            state?.phase == CallPhase.Ringing -> "来电"
            state?.phase == CallPhase.Connecting -> "正在接通"
            state?.phase == CallPhase.Canceling -> "正在取消"
            state?.phase == CallPhase.Ending -> "正在挂断"
            state?.phase == CallPhase.Dialing -> "正在拨号"
            else -> "正在准备通话"
        }
        val public = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("SoftVoWiFi").setContentText("有进行中的通话").build()
        val builder = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(state?.peer?.text() ?: "未知号码").setContentText(text).setOnlyAlertOnce(true).setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setShowWhen(false)
        if (state?.phase == CallPhase.InCall && state.sync == CallSyncState.Ready) {
            state.clock?.let { clock ->
                builder.setWhen(System.currentTimeMillis() - clock.elapsedMs()).setUsesChronometer(true).setShowWhen(true)
            }
        }
        if (key != null) {
            val open = Intent().setClassName(context, "com.vowifi.sw.CallActivity")
                .setData(Uri.parse("softvowifi-call:$key"))
                .putExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            builder.setContentIntent(PendingIntent.getActivity(context, 205, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            if (state?.sync == CallSyncState.Ready && state.phase in setOf(CallPhase.Connecting, CallPhase.InCall, CallPhase.Dialing)) {
                val end = Intent(END_ACTION).setClassName(context, "com.vowifi.sw.IncomingCallActionReceiver")
                    .setData(Uri.parse("softvowifi-call:$key"))
                    .putExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY, key)
                val hangup = PendingIntent.getBroadcast(context, 206, end, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                if (state.phase == CallPhase.InCall) {
                    val person = Person.Builder().setName(state.peer?.text() ?: "未知号码").build()
                    builder.setStyle(Notification.CallStyle.forOngoingCall(person, hangup))
                } else {
                    builder.addAction(Notification.Action.Builder(null, if (state.phase == CallPhase.Dialing) "取消" else "挂断", hangup).build())
                }
            }
        }
        return builder.build()
    }
}
