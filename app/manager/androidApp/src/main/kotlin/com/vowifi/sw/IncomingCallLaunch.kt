package com.vowifi.sw

import android.content.Intent
import androidx.activity.ComponentActivity
import com.vowifi.sw.manager.data.AndroidIncomingCallNotificationCoordinator
import com.vowifi.sw.manager.data.IncomingCallAction

internal fun ComponentActivity.applyIncomingCallLaunch(intent: Intent, coordinator: AndroidIncomingCallNotificationCoordinator) {
    setShowWhenLocked(false)
    setTurnScreenOn(false)
    val key = intent.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY) ?: return
    if (coordinator.resolveKey(key) != null) startActivity(coordinator.openIntent(key, intent.action == IncomingCallAction.ANSWER.actionName))
}
