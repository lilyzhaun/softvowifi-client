package com.vowifi.sw.manager.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import java.util.concurrent.atomic.AtomicInteger

/** Observe existing calls only; never repairs a channel, changes a notification or swallows failure. */
object ForegroundNotificationDiagnostics {
    private val attempts = AtomicInteger()

    fun trace(context: Context, owner: NotificationDiagnosticOwner, action: NotificationDiagnosticAction,
              id: Int, notification: Notification? = null, operation: () -> Unit) {
        val snapshot = snapshot(context, owner, action, id, notification)
        val attempt = attempts.updateAndGet { if (it >= 1_000_000_000) 1 else it + 1 }
        fun record(result: DiagnosticResult, reason: DiagnosticReason) {
            AndroidDiagnosticLog.emit(DiagnosticEvent(DiagnosticComponent.APP,
                if (result == DiagnosticResult.FAILURE) DiagnosticLevel.ERROR else DiagnosticLevel.INFO,
                DiagnosticName.OBSERVED, DiagnosticStage.NOTIFICATION, result, reason,
                attempt = attempt, notification = snapshot))
        }
        record(DiagnosticResult.STARTED, DiagnosticReason.NONE)
        var returned = false
        try { operation(); returned = true }
        finally {
            // Binder return is not an acknowledgement of the system's deferred notification post.
            // Original exceptions continue to the original caller, unchanged.
            record(if (returned) DiagnosticResult.OBSERVED else DiagnosticResult.FAILURE,
                if (returned) DiagnosticReason.UNCONFIRMED else DiagnosticReason.UNKNOWN)
        }
    }

    private fun snapshot(context: Context, owner: NotificationDiagnosticOwner, action: NotificationDiagnosticAction,
                         id: Int, notification: Notification?): NotificationDiagnostic {
        if (notification == null) return NotificationDiagnostic(owner, action, id)
        val channel = when (notification.channelId) {
            "ims_observer" -> NotificationDiagnosticChannel.OBSERVER
            "incoming_calls" -> NotificationDiagnosticChannel.INCOMING
            "ongoing_calls" -> NotificationDiagnosticChannel.ONGOING
            else -> NotificationDiagnosticChannel.OTHER
        }
        val icon = notification.smallIcon
        var exists: Boolean? = null
        var importance: Int? = null
        var loadable: Boolean? = null
        try {
            context.getSystemService(NotificationManager::class.java)?.let { manager ->
                val registered = manager.getNotificationChannel(notification.channelId)
                exists = registered != null; importance = registered?.importance
            }
        } catch (_: RuntimeException) { /* Diagnostic read unavailable; do not replace the real operation. */ }
        try { loadable = icon?.loadDrawable(context) != null }
        catch (_: RuntimeException) { /* Diagnostic resource load unavailable, never notification content. */ }
        return NotificationDiagnostic(owner, action, id, channel, exists, importance, icon != null, loadable,
            notification.extras.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification\$CallStyle",
            notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0, notification.fullScreenIntent != null)
    }
}
