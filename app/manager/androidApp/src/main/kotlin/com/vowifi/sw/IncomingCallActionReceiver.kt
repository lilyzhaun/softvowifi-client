package com.vowifi.sw

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vowifi.sw.manager.data.AndroidIncomingCallNotificationCoordinator
import com.vowifi.sw.manager.data.IncomingCallAction

class IncomingCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IncomingCallAction.REJECT.actionName && intent.action != com.vowifi.sw.manager.data.CallOngoingNotification.END_ACTION) return
        val notificationKey = intent.getStringExtra(
            AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY,
        ) ?: return

        val runtime = try {
            (context.applicationContext as SoftVoWiFiApplication).runtime()
        } catch (_: Exception) {
            return
        }
        val coordinator = runtime.notifications

        if (intent.action == com.vowifi.sw.manager.data.CallOngoingNotification.END_ACTION) {
            val binding = coordinator.resolveKey(notificationKey) ?: return
            val ready = runtime.calls.state.value as? com.vowifi.sw.manager.model.CallUiState.Ready ?: return
            val action = when (ready.phase) {
                com.vowifi.sw.manager.model.CallPhase.Dialing -> com.vowifi.sw.manager.model.CallAction.Cancel
                com.vowifi.sw.manager.model.CallPhase.Connecting,
                com.vowifi.sw.manager.model.CallPhase.InCall -> com.vowifi.sw.manager.model.CallAction.Hangup
                else -> return
            }
            runtime.calls.act(binding, action)
        } else coordinator.handleAction(IncomingCallAction.REJECT, notificationKey)
    }
}
