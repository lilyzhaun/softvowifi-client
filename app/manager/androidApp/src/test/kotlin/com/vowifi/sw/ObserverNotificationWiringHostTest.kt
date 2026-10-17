@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.vowifi.sw

import android.app.NotificationManager
import android.content.Intent
import android.os.UserManager
import com.vowifi.sw.manager.data.AndroidObserverNotificationDefaults
import com.vowifi.sw.manager.data.AndroidSuResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
class ObserverNotificationWiringHostTest {
    @Test fun readyObserverAppliesItsDefaultOnceWithoutReplacingItsForegroundHost() = checkWiring(true)
    @Test fun deniedRootDoesNotStopObservationOrItsCommunicationHost() = checkWiring(false)

    private fun checkWiring(success: Boolean) {
        val app = RuntimeEnvironment.getApplication() as SoftVoWiFiApplication
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        val dispatcher = HomeControlDispatcher()
        val job = SupervisorJob()
        var commands = 0
        val defaults = AndroidObserverNotificationDefaults(app, CoroutineScope(job + dispatcher)) {
            commands++
            AndroidSuResult(false, if (success) 0 else 1,
                if (success) "OBSERVER_CHANNEL_DEFAULT_READY" else "OBSERVER_CHANNEL_DEFAULT_UNAVAILABLE", false)
        }
        ReflectionHelpers.setField(app, "observerNotificationDefaults", defaults)
        val runtime = app.runtime()
        val controller = Robolectric.buildService(ObserverService::class.java)
        try {
            controller.create()
            dispatcher.runCurrent()
            val service = controller.get()
            assertEquals("真实Observer ready必须接默认迁移，不是只测未接线的helper", 1, commands)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertEquals("ims_observer", shadowOf(service).lastForegroundNotification.channelId)
            assertNotNull(app.getSystemService(NotificationManager::class.java).getNotificationChannel("ims_observer"))
            service.onStartCommand(Intent(), 0, 1)
            service.restoreObserverNotification()
            dispatcher.runCurrent()
            assertEquals(1, commands)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertSame(runtime, app.runtime())
            assertEquals("ims_observer", shadowOf(service).lastForegroundNotification.channelId)
        } finally {
            controller.destroy()
            job.cancel()
        }
    }
}
