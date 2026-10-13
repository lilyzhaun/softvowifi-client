package com.vowifi.sw

import android.app.Application
import android.os.UserManager
import com.vowifi.sw.manager.data.AndroidIncomingCallNotificationCoordinator
import com.vowifi.sw.manager.data.SoftVoWiFiRuntime
import com.vowifi.sw.manager.data.SoftVoWiFiRuntimeHolder

class SoftVoWiFiApplication : Application(), ProcessRuntimeStarter {
    private lateinit var holder: SoftVoWiFiRuntimeHolder
    private var observerNotifications: AndroidIncomingCallNotificationCoordinator? = null

    override fun onCreate() {
        super.onCreate()
        holder = SoftVoWiFiRuntimeHolder(this, MainActivity::class.java, R.drawable.ic_stat_message)
        holder.startFromApplication()
    }

    fun runtime(): SoftVoWiFiRuntime = holder.requireRuntime()
    override fun startRuntime() = holder.startFromApplication()
    override fun onObserverReady(service: ObserverService) {
        if (getSystemService(UserManager::class.java)?.isUserUnlocked != true || isDeviceProtectedStorage) return
        val notifications = runtime().notifications
        runtime().observeNetworks()
        observerNotifications = notifications
        notifications.attachForegroundHost(service,
            publish = { service.startForeground(AndroidIncomingCallNotificationCoordinator.NOTIFICATION_ID, it) },
            restore = service::restoreObserverNotification,
        )
    }
    override fun onObserverDestroyed(service: ObserverService) {
        observerNotifications?.detachForegroundHost(service)
        runtime().stopObservingNetworks()
    }
}
