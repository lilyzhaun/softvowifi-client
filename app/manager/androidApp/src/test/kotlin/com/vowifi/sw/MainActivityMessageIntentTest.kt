package com.vowifi.sw

import android.content.Intent
import android.net.Uri
import com.vowifi.sw.manager.data.AndroidIncomingCallNotificationCoordinator
import com.vowifi.sw.manager.data.AndroidMessageNotifications
import com.vowifi.sw.manager.data.AndroidMessageSource
import com.vowifi.sw.manager.data.SoftVoWiFiRuntime
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class, CallCoordinatorShadow::class, MessageSourceShadow::class])
class MainActivityMessageIntentTest {
    @Test fun arbitraryCallsDestinationNeverUnlocksManagementWindow() {
        Robolectric.buildActivity(MainActivity::class.java, calls()).use { controller ->
            controller.create().start().resume()
            assertFalse(shadowOf(controller.get()).showWhenLocked)
            assertFalse(shadowOf(controller.get()).turnScreenOn)
            controller.pause().stop().destroy()
        }
    }
    @Test fun warmCallsNeverGrantManagementWindowPrivilegesAfterMessage() {
        Robolectric.buildActivity(MainActivity::class.java, message()).use { controller ->
            controller.create().start().resume()
            assertFalse(shadowOf(controller.get()).showWhenLocked)
            controller.newIntent(calls())
            assertFalse(shadowOf(controller.get()).showWhenLocked)
            assertFalse(shadowOf(controller.get()).turnScreenOn)
            controller.pause().stop().destroy()
        }
    }

    @Test fun warmMessageClearsBothCallFlagsWithoutStopAndEmitsThreadOnce() {
        Robolectric.buildActivity(MainActivity::class.java, calls()).use { controller ->
            controller.create().start().resume()
            val activity = controller.get()
            assertFalse(shadowOf(activity).showWhenLocked)
            assertFalse(shadowOf(activity).turnScreenOn)
            controller.newIntent(message())
            assertFalse(shadowOf(activity).showWhenLocked)
            assertFalse(shadowOf(activity).turnScreenOn)
            val routes = ReflectionHelpers.getField<Channel<AppRoute>>(activity, "incomingRoutes")
            assertEquals("conversation-test", (routes.tryReceive().getOrNull() as AppRoute.MessageThread).conversation.value)
            assertTrue(routes.tryReceive().isFailure)
            controller.pause().stop().destroy()
        }
    }

    @Test fun malformedSmsCannotBorrowCallExtrasToKeepWindowPrivileges() {
        Robolectric.buildActivity(MainActivity::class.java, calls()).use { controller ->
            controller.create().start().resume()
            controller.newIntent(message().putExtra("destination", "calls"))
            assertFalse(shadowOf(controller.get()).showWhenLocked)
            assertFalse(shadowOf(controller.get()).turnScreenOn)
            controller.pause().stop().destroy()
        }
    }

    private fun calls() = Intent().putExtra("destination", "calls")
    private fun message() = Intent(AndroidMessageNotifications.ACTION)
        .setData(Uri.parse("softvowifi-message:conversation-test"))
        .putExtra(AndroidMessageNotifications.EXTRA_HANDLE, "conversation-test")
}

@Implements(SoftVoWiFiApplication::class)
class RuntimeApplicationShadow : org.robolectric.shadows.ShadowApplication() {
    var runtimeStarts = 0
        private set
    private val runtime by lazy { testShell(SoftVoWiFiRuntime::class.java).also {
        val fixture = callFixture ?: CallPlatformFixture()
        ReflectionHelpers.setField(it, "calls", fixture.facade(org.robolectric.RuntimeEnvironment.getApplication()))
        ReflectionHelpers.setField(it, "notifications", fixture.coordinator(org.robolectric.RuntimeEnvironment.getApplication()))
        ReflectionHelpers.setField(it, "messages", testShell(AndroidMessageSource::class.java))
        ReflectionHelpers.setField(it, "callNotificationSettings", com.vowifi.sw.manager.data.AndroidCallNotificationSettingsSource(
            org.robolectric.RuntimeEnvironment.getApplication(),
        ))
        ReflectionHelpers.setField(it, "messageNotifications", AndroidMessageNotifications(
            org.robolectric.RuntimeEnvironment.getApplication(), MainActivity::class.java, android.R.drawable.ic_dialog_info,
        ))
    } }
    @Implementation fun onCreate() = Unit
    @Implementation fun startRuntime() { runtimeStarts += 1 }
    @Implementation fun runtime(): SoftVoWiFiRuntime = runtime
    companion object { internal var callFixture: CallPlatformFixture? = null }
}

private fun <T : Any> testShell(type: Class<T>): T = requireNotNull(type.cast(
    sun.reflect.ReflectionFactory.getReflectionFactory()
        .newConstructorForSerialization(type, Any::class.java.getDeclaredConstructor()).newInstance(),
)).also { instance ->
    type.declaredMethods.singleOrNull { it.name == "\$\$robo\$init" }?.let { method ->
        method.isAccessible = true
        method.invoke(instance)
    }
}

@Implements(AndroidIncomingCallNotificationCoordinator::class)
class CallCoordinatorShadow {
    @Implementation fun setAppForeground(value: Boolean) = Unit
}

@Implements(AndroidMessageSource::class)
class MessageSourceShadow {
    @Implementation fun setResumed(value: Boolean) = Unit
    @Implementation fun selectConversation(handle: String?) {
        routeSource?.selectConversation(handle)
    }
    companion object {
        var routeSource: com.vowifi.sw.manager.data.EngineMessageSource? = null
    }
}
