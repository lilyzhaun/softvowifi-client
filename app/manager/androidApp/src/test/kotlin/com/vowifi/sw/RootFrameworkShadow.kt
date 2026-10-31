package com.vowifi.sw

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.SmsManager
import android.content.pm.PackageManager
import org.json.JSONObject
import com.vowifi.sw.root.RootBridge
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter

/** Fixture replacement for the Root process hop; audio/control/state machines stay real. */
@Implements(value = RootBridge::class, isInAndroidSdk = false)
class RootFrameworkShadow {
    companion object {
        private val watchers = java.util.concurrent.ConcurrentHashMap<Runnable, SubscriptionManager.OnSubscriptionsChangedListener>()

        @JvmStatic @Implementation fun watchSubscriptions(watcher: Runnable) {
            val app = RuntimeEnvironment.getApplication()
            val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() = watcher.run()
            }
            watchers[watcher] = listener
            app.getSystemService(SubscriptionManager::class.java).addOnSubscriptionsChangedListener(app.mainExecutor, listener)
        }

        @JvmStatic @Implementation fun unwatchSubscriptions(watcher: Runnable) {
            watchers.remove(watcher)?.let {
                RuntimeEnvironment.getApplication().getSystemService(SubscriptionManager::class.java)
                    .removeOnSubscriptionsChangedListener(it)
            }
        }

        @JvmStatic @Resetter fun reset() { watchers.clear() }

        @JvmStatic @Implementation fun activeSubscriptions(): List<SubscriptionInfo> =
            RuntimeEnvironment.getApplication().getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList.orEmpty()

        @JvmStatic @Implementation fun nativeCallsIdle(): Boolean =
            RuntimeEnvironment.getApplication().getSystemService(TelecomManager::class.java)?.isInCall == false

        @JvmStatic @Implementation fun subscriptionExtras(subId: Int): JSONObject {
            val app = RuntimeEnvironment.getApplication()
            val result = JSONObject()
            try { result.put("line1", app.getSystemService(SubscriptionManager::class.java).getPhoneNumber(subId)) }
            catch (_: RuntimeException) { /* Optional framework field remains absent. */ }
            if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
                try { result.put("smsc", SmsManager.getSmsManagerForSubscriptionId(subId).smscAddress) }
                catch (_: RuntimeException) { /* Optional framework field remains absent. */ }
            }
            return result
        }

        @JvmStatic @Implementation fun openSocket(path: String): LocalSocket = LocalSocket().apply {
            connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        }
    }
}
