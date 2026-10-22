package com.vowifi.sw

import android.app.Application
import android.os.UserManager
import com.vowifi.sw.manager.data.AndroidIncomingCallNotificationCoordinator
import com.vowifi.sw.manager.data.AndroidObserverNotificationDefaults
import com.vowifi.sw.manager.data.AndroidDiagnosticLog
import com.vowifi.sw.manager.data.AndroidDiagnosticExport
import com.vowifi.sw.manager.data.SoftVoWiFiRuntime
import com.vowifi.sw.manager.data.SoftVoWiFiRuntimeHolder

class SoftVoWiFiApplication : Application(), ProcessRuntimeStarter {
    private lateinit var holder: SoftVoWiFiRuntimeHolder
    private var observerNotifications: AndroidIncomingCallNotificationCoordinator? = null
    private var observerNotificationDefaults: AndroidObserverNotificationDefaults? = null

    override fun onCreate() {
        super.onCreate()
        AndroidDiagnosticLog.start(this)
        // Reap only this feature's fixed temporary slot after a killed export; no root or log deletion.
        if(!isDeviceProtectedStorage && getSystemService(UserManager::class.java)?.isUserUnlocked==true) {
            Thread({ try { AndroidDiagnosticExport.cleanup(this) } catch (_: java.io.IOException) { }
                catch (_: SecurityException) { } }, "vowifi-export-cleanup").apply { isDaemon=true }.start()
        }
        observerNotificationDefaults = AndroidObserverNotificationDefaults(this)
        holder = SoftVoWiFiRuntimeHolder(this, MainActivity::class.java, R.drawable.ic_stat_message)
        holder.startFromApplication()
    }

    fun runtime(): SoftVoWiFiRuntime = holder.requireRuntime()
    override fun startRuntime() = holder.startFromApplication()
    override fun onObserverReady(service: ObserverService) {
        if (getSystemService(UserManager::class.java)?.isUserUnlocked != true || isDeviceProtectedStorage) return
        observerNotificationDefaults?.applyDefault()
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
