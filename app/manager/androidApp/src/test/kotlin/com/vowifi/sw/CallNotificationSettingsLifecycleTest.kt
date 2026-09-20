package com.vowifi.sw

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import com.vowifi.sw.manager.ui.NotificationPermissionStatus
import com.vowifi.sw.manager.ui.NotificationSnapshotFreshness
import com.vowifi.sw.manager.ui.SettingsAction
import com.vowifi.sw.manager.data.CallNotificationSettingsLaunchResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class,
    shadows = [RuntimeApplicationShadow::class, CallCoordinatorShadow::class, MessageSourceShadow::class])
class CallNotificationSettingsLifecycleTest {
    @Test fun returningToRealActivityRefreshesTheRuntimeOwnedSource() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.create().start().resume()
            val app = controller.get().application as SoftVoWiFiApplication
            val source = app.runtime().callNotificationSettings
            val manager = app.getSystemService(NotificationManager::class.java)
            assertEquals(CallNotificationSettingsLaunchResult.OPENED, source.openSettings(SettingsAction.ManageNotificationPermission))
            assertEquals(NotificationSnapshotFreshness.STALE, source.state.value.freshness)
            controller.pause()
            shadowOf(manager).setNotificationsEnabled(false)
            controller.resume()
            assertEquals(NotificationPermissionStatus.DENIED, source.state.value.permission)
            assertEquals(NotificationSnapshotFreshness.CURRENT, source.state.value.freshness)
            controller.pause()
            shadowOf(manager).setNotificationsEnabled(true)
            controller.resume()
            assertSame(source, app.runtime().callNotificationSettings)
            assertEquals(NotificationPermissionStatus.GRANTED, source.state.value.permission)
            controller.pause().stop().destroy()
        }
    }

    @Test fun grantedPermissionResultRefreshesWithoutAnotherResume() {
        val app = RuntimeEnvironment.getApplication() as SoftVoWiFiApplication
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.create().start().resume()
            val activity = controller.get()
            val request = shadowOf(activity).lastRequestedPermission
            assertNotNull(request)
            val manager = app.getSystemService(NotificationManager::class.java)
            shadowOf(manager).setNotificationsEnabled(false)
            app.runtime().callNotificationSettings.refresh()
            assertEquals(NotificationPermissionStatus.DENIED, app.runtime().callNotificationSettings.state.value.permission)
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            shadowOf(manager).setNotificationsEnabled(true)
            activity.onRequestPermissionsResult(request.requestCode, arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                intArrayOf(PackageManager.PERMISSION_GRANTED))
            assertEquals(NotificationPermissionStatus.GRANTED, app.runtime().callNotificationSettings.state.value.permission)
            controller.pause().stop().destroy()
        }
    }

    @Test fun permissionResultRefreshesWithoutAnotherResume() {
        val app = RuntimeEnvironment.getApplication() as SoftVoWiFiApplication
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.create().start().resume()
            val activity = controller.get()
            val request = shadowOf(activity).lastRequestedPermission
            assertNotNull(request)
            shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
            activity.onRequestPermissionsResult(request.requestCode, arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                intArrayOf(PackageManager.PERMISSION_DENIED))
            assertEquals(NotificationPermissionStatus.DENIED, app.runtime().callNotificationSettings.state.value.permission)
            controller.pause().stop().destroy()
        }
    }
}
