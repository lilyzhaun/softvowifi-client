package com.vowifi.sw

import android.app.Service
import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.CallUiState

class CallMicrophoneService : Service() {
    private class ActiveStart(val key: String, var lastStartId: Int)
    private var activeStart: ActiveStart? = null
    private var runtime: SoftVoWiFiRuntime? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = (application as SoftVoWiFiApplication).runtime()
        runtime = current
        val key = intent?.getStringExtra(AndroidCallLifecycle.EXTRA_REQUEST)
        if (key == null || !current.calls.lifecycle.acceptsService(key)) {
            val owner = activeStart
            if (owner != null) {
                owner.lastStartId = startId
            } else {
                try {
                    promote(CallOngoingNotification.build(this, null, null))
                    retire(STOP_FOREGROUND_REMOVE)
                } catch (_: SecurityException) {
                    current.calls.lifecycle.feedback.value = "系统拒绝麦克风服务，请检查权限"
                } catch (_: IllegalStateException) {
                    current.calls.lifecycle.feedback.value = "系统不允许当前启动麦克风服务，请返回前台重试"
                } finally { stopSelf(startId) }
            }
            return START_NOT_STICKY
        }
        try {
            promote(CallOngoingNotification.build(this, current.notifications.keyForCurrent(),
                current.calls.state.value as? CallUiState.Ready))
            val owner = ActiveStart(key, startId)
            activeStart = owner
            if (!current.calls.lifecycle.serviceStarted(key) {
                    if (activeStart === owner) {
                        activeStart = null
                        // DETACH can force a deferred CallStyle to be reposted after its FGS
                        // owner is gone. Remove that notification; Coordinator owns the plain one.
                        retire(STOP_FOREGROUND_REMOVE)
                        stopSelf(owner.lastStartId)
                        current.notifications.setMicrophoneRunning(false)
                    }
                }) { retire(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
            else current.notifications.setMicrophoneRunning(true)
        } catch (_: SecurityException) {
            current.calls.lifecycle.serviceFailed(key); stopSelf(startId)
        } catch (_: IllegalStateException) {
            current.calls.lifecycle.serviceFailed(key); stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    private fun promote(notification: Notification) = ForegroundNotificationDiagnostics.trace(this,
        NotificationDiagnosticOwner.MICROPHONE, NotificationDiagnosticAction.PROMOTE, CallOngoingNotification.ID, notification) {
        startForeground(CallOngoingNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }
    private fun retire(flags: Int) = ForegroundNotificationDiagnostics.trace(this,
        NotificationDiagnosticOwner.MICROPHONE,
        if (flags and STOP_FOREGROUND_DETACH != 0) NotificationDiagnosticAction.DETACH else NotificationDiagnosticAction.RETIRE,
        CallOngoingNotification.ID) {
        stopForeground(flags)
    }
    override fun onDestroy() {
        activeStart?.let { owner ->
            runtime?.calls?.lifecycle?.serviceDestroyed(owner.key)
            runtime?.notifications?.setMicrophoneRunning(false)
        }
        super.onDestroy()
    }
}
