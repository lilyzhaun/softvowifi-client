package com.vowifi.sw.root

import android.content.Context
import android.os.Process
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.vowifi.sw.RegistrationObserver
import java.util.function.Consumer
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowProcess
import org.robolectric.util.ReflectionHelpers

/** Replace the process/Binder hop only; exercise the real Root observer and callback lifecycle. */
@Implements(value = RootBridge::class, isInAndroidSdk = false)
class ObserverRootBridgeShadow {
    companion object {
        var available = true
        var rootUid = 0
        var context: Context? = null
        private var observation: RootNativeObservation? = null

        val observers: Map<Int, RegistrationObserver> get() = observation?.let {
            ReflectionHelpers.getField<List<RegistrationObserver>>(it, "observers").associateBy { observer ->
                ReflectionHelpers.getField<Int>(observer, "subId")
            }
        }.orEmpty()

        @JvmStatic @Implementation fun bind(value: Context, ready: Runnable) {
            context = value
            if (available) {
                if (observation == null) observation = RootNativeObservation(value)
                ready.run()
            }
        }

        @JvmStatic @Implementation fun observeNativeRegistration(events: Consumer<String>?) {
            if (!available) throw IllegalStateException("PRIVATE_OBSERVER_SENTINEL")
            val uid = Process.myUid()
            ShadowProcess.setUid(rootUid)
            try {
                observation?.observe(events?.let { sink -> object : INativeRegistrationEvents.Stub() {
                    override fun onEvent(event: String) = sink.accept(event)
                } })
            } finally { ShadowProcess.setUid(uid) }
        }

        fun reset() {
            val uid = Process.myUid()
            ShadowProcess.setUid(0)
            try { observation?.close() } finally { ShadowProcess.setUid(uid) }
            observation = null
            context = null
            available = true
            rootUid = 0
        }
    }
}

@Implements(value = RootTelephony::class, isInAndroidSdk = false)
class ObserverRootTelephonyShadow {
    companion object {
        @JvmStatic @Implementation fun activeSubscriptions(): List<SubscriptionInfo> =
            ObserverRootBridgeShadow.context!!.getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList.orEmpty()
    }
}
