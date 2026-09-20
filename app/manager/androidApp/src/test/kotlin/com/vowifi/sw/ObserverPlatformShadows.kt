package com.vowifi.sw

import android.content.pm.PackageManager
import android.telephony.ims.ImsManager
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.RegistrationManager
import java.util.concurrent.Executor
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContextImpl
import org.robolectric.util.ReflectionHelpers

@Implements(className = "android.app.ContextImpl")
class ObserverPermissionShadow : ShadowContextImpl() {
    @Implementation fun checkSelfPermission(permission: String): Int =
        if (permission in granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED

    companion object { val granted = mutableSetOf<String>() }
}

@Implements(ImsManager::class)
class ObserverImsManagerShadow {
    @Implementation fun getImsMmTelManager(subscriptionId: Int): ImsMmTelManager {
        if (subscriptionId == failedSubscription) throw IllegalStateException(SENTINEL)
        return ReflectionHelpers.callStaticMethod(ImsMmTelManager::class.java, "createForSubscriptionId",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, subscriptionId))
    }
    companion object { var failedSubscription = -1 }
}

@Implements(ImsMmTelManager::class)
class ObserverMmTelShadow : org.robolectric.shadows.ShadowImsMmTelManager() {
    @Implementation override fun registerImsRegistrationCallback(
        executor: Executor, callback: RegistrationManager.RegistrationCallback,
    ) {
        if (registrationFailure) throw SecurityException(SENTINEL)
        super.registerImsRegistrationCallback(executor, callback)
        registered += subscriptionId
        callbacks += callback
    }

    @Implementation override fun unregisterImsRegistrationCallback(callback: RegistrationManager.RegistrationCallback) {
        closed += subscriptionId
        if (closed.size == 1 && closeFailure) throw SecurityException(SENTINEL)
        super.unregisterImsRegistrationCallback(callback)
    }

    companion object {
        var registrationFailure = false
        var closeFailure = false
        val registered = mutableListOf<Int>()
        val closed = mutableListOf<Int>()
        val callbacks = mutableListOf<RegistrationManager.RegistrationCallback>()
    }
}

internal const val SENTINEL = "PRIVATE_OBSERVER_SENTINEL"

@Implements(com.vowifi.sw.manager.data.AndroidCallSource::class)
class ObserverCallsShadow {
    var closeCalls = 0
    @Implementation fun close() { closeCalls += 1 }
}

@Implements(com.vowifi.sw.manager.data.AndroidMessageSource::class)
class ObserverMessagesShadow {
    var closeCalls = 0
    @Implementation fun close() { closeCalls += 1 }
}
